package me.doubaoime.blur

import android.app.Dialog
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

internal object ImeBlurHook {
    private const val TAG = "DoubaoImeBlur"
    private const val MONET_BG_ID = "keyboard_whole"

    /**
     * 底板自己已经是 0x66 强调色，这些子面板再叠一层就成了双层强调色（明显变深）。
     * asr_visualizer_ll 就是语音识别完的"识别优化中"那条，必须透明。
     */
    private val TRANSPARENT_BG_IDS = setOf(
        "native_candidate_bar",
        "keyboard_main_container_area",
        "keyboard_whole_board_area",
        "idle_tip_content",
        "idle_tip_status",
        "status_content",
        "status_message",
        "asr_visualizer_ll",
        // 剪贴板 / 常用语是工具栏那一行里的同一块，同样会叠第二层
        "clipboard_common_phrase_toolbar",
        "common_phrase_toolbar",
        "common_phrase_empty",
        // 手写找字界面
        "lookup_tool_bar_placeholder",
        "lookup_write_filler",
        "lookup_btn_key_layout",
    )
    private const val BLUR_RADIUS = 20
    private const val CORNER_RADIUS = 0f
    private var keyboardWholeBlurred = false
    private var toolbarBackgroundApplying = false

    fun install(loader: ClassLoader, module: XposedModule, apkPath: String) {
        if (Build.VERSION.SDK_INT < 31) {
            module.log(Log.INFO, TAG, "BackgroundBlurDrawable requires Android 12+")
            return
        }
        try {
            val ctx = loader.loadClass("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? android.content.Context
            if (ctx != null) MonetEngine.ensureInitialized(ctx)
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "MonetEngine init skipped (lazy init will retry): $t")
        }
        HookFinder.init(apkPath)
        val imeService = loader.loadClass("com.bytedance.android.doubaoime.ImeService")
        hookResourceColors(module)
        // 每个 hook 独立容错：任何一个失配都不能中断后面所有 hook 的安装。
        // 之前 r2/c(...) 这些 1.5.3 已不存在的方法一抛异常就把后面的全吞了。
        listOf<Pair<String, () -> Unit>>(
            "fillRect" to { hookKeyboardViewFillRect(module, loader) },
            "shadow" to { hookKeyboardViewShadow(module, loader) },
            "bubble" to { hookKeyboardViewTouchBubble(module, loader) },
            "toolboxView" to { hookToolboxKeyboardView(module, loader) },
            "toolboxItem" to { hookToolboxItemView(module, loader) },
            "candidate" to { hookCandidateItemSelected(module, loader) },
            "optionItem" to { hookOptionItemToolbarRefresh(module, loader) },
            "toolboxProbe" to { hookInputViewRootToolboxProbe(module, loader) },
            "toolboxToggle" to { hookToolboxToggle(module, loader) },
            "emojiToggle" to { hookEmojiToggle(module, loader) },
            "emojiBar" to { hookEmojiSegmentedBar(module, loader) },
            "clipboardDismiss" to { hookClipboardDismiss(module, loader) },
            "overlayVisibility" to { hookOverlayVisibility(module, loader) },
        ).forEach { (name, block) ->
            try { block() } catch (t: Throwable) {
                module.log(Log.WARN, TAG, "$name hook skipped: $t")
            }
        }
        hookCreateInputView(module, imeService)
        hookStartInputView(module, imeService)
        hookToolbarBackgroundProbe(module)
        hookToolbarBackgroundColorOverride(module)
        hookToolbarBackgroundResourceOverride(module)
    }

    private fun hookToolbarBackgroundProbe(module: XposedModule) {
        val method = View::class.java.getDeclaredMethod("setBackground", Drawable::class.java).apply { isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val view = chain.thisObject as View
                if (lookupKeyViews.contains(view)) {
                    // 找字界面的键自己会在按下/抬起时重设键帽，这里换成 Monet 版本，避免按住露出灰底
                    return@intercept chain.proceed(arrayOf<Any>(lookupKeyDrawable(view)))
                }
                val drawable = chain.getArg(0) as? Drawable
                val color = (drawable as? ColorDrawable)?.color
                val idName = try {
                    if (view.id == View.NO_ID) "-" else view.resources.getResourceEntryName(view.id)
                } catch (_: Throwable) { "?" }
                val cls = view.javaClass.name
                // 记录所有 ColorDrawable 背景（不限 ID），找出按键背景设置路径
                if (color != null && idName !in setOf("-", "")) {
                    module.log(Log.INFO, TAG, "setBackground view=$cls id=$idName color=#${Integer.toHexString(color)}")
                }
                if (idName == "native_candidate_bar") watchClipboardToolbar(view, module)
                if (idName in TRANSPARENT_BG_IDS) {
                    module.log(Log.INFO, TAG, "kept bg transparent over Monet body id=$idName")
                    chain.proceed(arrayOf<Any>(ColorDrawable(0x00000000)))
                } else if (color == 0xff222222.toInt()) {
                    val replacement = globalMonetSurface(view.resources)
                    module.log(Log.INFO, TAG, "setBackground #222222 -> #${Integer.toHexString(replacement)} view=$cls id=$idName")
                    chain.proceed(arrayOf<Any>(ColorDrawable(replacement)))
                } else {
                    chain.proceed()
                }
            }
        module.log(Log.INFO, TAG, "toolbar background probe installed")
    }

    /**
     * 剪贴板提示整行自己也带着 0x66 强调色（它的 #222222 被 globalMonetSurface 换掉了），
     * 叠在 keyboard_whole 同一层 0x66 上就成了双层，看着明显更深。
     * 这一行只留底板那层，把 alpha=0x66 的重复上色清掉；分隔线是 0x33 的白色，不动。
     */
    private fun clearDoubleTint(v: View, module: XposedModule): Int {
        var n = 0
        val bg = v.background
        if (bg is ColorDrawable && (bg.color ushr 24) == 0x66) {
            v.background = ColorDrawable(0x00000000)
            n++
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) n += clearDoubleTint(v.getChildAt(i), module)
        return n
    }

    private val lookupKeyViews = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())
    private var clipBar: View? = null
    private var clipContainer: View? = null

    /**
     * 复制内容后输入法会在工具栏那一行弹出剪贴板提示（ImeToolBarContainer），
     * 它和 native_candidate_bar 是同一个容器的兄弟、同一位置同高度 —— 两个都可见
     * 就变成重影 + 两层底色叠加。提示显示期间把底下的候选栏 alpha 归零，收起再恢复。
     *
     * 注意容器和候选栏会被重新创建（换应用/二次弹出），所以引用要每次都刷新，
     * 不能只挂一次监听，否则第二次弹出就失效。
     */
    private fun watchClipboardToolbar(bar: View, module: XposedModule) {
        val parent = bar.parent as? ViewGroup ?: return
        val container = (0 until parent.childCount)
            .map { parent.getChildAt(it) }
            .firstOrNull { it !== bar && it.javaClass.simpleName == "ImeToolBarContainer" } ?: return

        val firstSeen = container !== clipContainer
        clipBar = bar
        clipContainer = container

        if (firstSeen) {
            container.viewTreeObserver.addOnGlobalLayoutListener { syncClipboardToolbar(module) }
        }
        syncClipboardToolbar(module)

    }

    private fun syncClipboardToolbar(module: XposedModule) {
        val bar = clipBar ?: return
        val container = clipContainer ?: return
        val closeId = container.resources.getIdentifier("toolbar_clipboard_close", "id", container.context.packageName)
        val closeView = if (closeId != 0) container.findViewById<View>(closeId) else null
        val showing = container.visibility == View.VISIBLE && closeView != null && closeView.visibility == View.VISIBLE
        val target = if (showing) 0f else 1f
        if (bar.alpha != target) {
            bar.alpha = target
            val cleared = if (showing) clearDoubleTint(container, module) else 0
            module.log(Log.INFO, TAG, "clipboard toolbar showing=$showing candidateBarAlpha=$target clearedTint=$cleared")
        }
    }

    /**
     * 统一的浮层可见性钩子：剪贴板提示条、手写找字面板都会走 View.setVisibility。
     * 手写找字面板（HandWritingLookupBoardView）和 soft_view 是 keyboard_main_container_area
     * 的兄弟且同尺寸，半透明时下面键盘按键全透上来，所以显示期间要把兄弟清掉。
     */
    private fun hookOverlayVisibility(module: XposedModule, loader: ClassLoader) {
        val lookupCls = loader.loadClass(HookPoints.HANDWRITING_LOOKUP)
        val moreCls = loader.loadClass(HookPoints.MORE_CANDIDATE)
        val method = View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType).apply { isAccessible = true }
        module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val self = chain.thisObject
            val result = chain.proceed()
            val v = self as? View
            if (v != null) {
                if (lookupCls.isInstance(v)) {
                    val show = v.visibility == View.VISIBLE
                    isolateSiblings(v, show, module, "lookup", keep = setOf("native_candidate_bar"))
                    if (show) clearLookupSurface(v, module)
                } else if (moreCls.isInstance(v)) {
                    val show = v.visibility == View.VISIBLE
                    isolateSiblings(v, show, module, "moreCandidate")
                    if (show) clearMoreCandidateSurface(v, module)
                } else if (v === clipContainer) {
                    syncClipboardToolbar(module)
                }
            }
            result
        }
        module.log(Log.INFO, TAG, "overlay visibility hook installed")
    }

    private fun isolateSiblings(
        target: View,
        show: Boolean,
        module: XposedModule,
        tag: String,
        keep: Set<String> = emptySet(),
    ) {
        val container = target.parent as? ViewGroup ?: return
        for (i in 0 until container.childCount) {
            val c = container.getChildAt(i)
            if (c === target) continue
            val name = try { if (c.id == View.NO_ID) "" else c.resources.getResourceEntryName(c.id) } catch (_: Throwable) { "" }
            // keep 里的（候选字栏）不但不能藏，还要强制还原
            hideOrRestoreAlpha(c, if (name in keep) false else show)
        }
        module.log(Log.INFO, TAG, "$tag isolate show=$show hidden=${hiddenSiblingAlpha.size}")
    }

    /** 找字界面底部那排键（返回/重输/空格/删除/确定）的键帽是 LayerDrawable，换成 Monet 强调色。 */
    private fun tintLookupKey(view: View, module: XposedModule) {
        try {
            lookupKeyViews.add(view)
            view.background = lookupKeyDrawable(view)
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "lookup key tint failed: $t")
        }
    }

    private fun lookupKeyDrawable(view: View): Drawable {
        val dark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val raw = monetColor(view, if (dark) "system_accent1_800" else "system_accent1_100")
        val base = if (raw == 0) {
            if (dark) 0xff303030.toInt() else 0xfff0f0f0.toInt()
        } else raw
        return GradientDrawable().apply {
            setShape(GradientDrawable.RECTANGLE)
            cornerRadius = view.resources.displayMetrics.density * 8f
            setColor(withAlpha(base, if (view.isPressed) 0x99 else 0x66))
        }
    }

    /**
     * 展开候选字界面（MoreCandidateView）的底色是 more_candidate_root 上的 GradientDrawable，
     * 不是 ColorDrawable，所以全局 #222222 替换抓不到它 —— 结果就是实心深黑。
     * 这里直接清成透明，露出底板的 0x66 Monet + 模糊。
     */
    private fun clearMoreCandidateSurface(root: View, module: XposedModule) {
        try {
            var n = 0
            listOf("more_candidate_root", "more_candidate_tab_container", "more_candidate_tab_area").forEach { name ->
                val id = root.resources.getIdentifier(name, "id", root.context.packageName)
                if (id != 0) {
                    root.findViewById<View>(id)?.let { v ->
                        if (v.background != null) {
                            v.background = ColorDrawable(0x00000000)
                            n++
                        }
                    }
                }
            }
            module.log(Log.INFO, TAG, "moreCandidate surface cleared n=$n")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "moreCandidate surface failed: $t")
        }
    }

    private fun clearLookupSurface(root: View, module: XposedModule) {
        try {
            listOf("lookup_tool_bar_placeholder", "lookup_write_filler", "lookup_btn_key_layout").forEach { name ->
                val id = root.resources.getIdentifier(name, "id", root.context.packageName)
                if (id != 0) root.findViewById<View>(id)?.background = ColorDrawable(0x00000000)
            }
            val keysId = root.resources.getIdentifier("lookup_btn_key_layout", "id", root.context.packageName)
            if (keysId != 0) {
                val keys = root.findViewById<ViewGroup>(keysId)
                if (keys != null) {
                    // 此时按键还没测量完（width=0），等一帧布局过完再上色
                    Handler(Looper.getMainLooper()).postDelayed({
                        var n = 0
                        for (i in 0 until keys.childCount) {
                            val c = keys.getChildAt(i)
                            if (c.width == 0) continue
                            tintLookupKey(c, module)
                            n++
                        }
                        module.log(Log.INFO, TAG, "lookup keys tinted n=$n")
                    }, 80)
                }
            }
            module.log(Log.INFO, TAG, "lookup surface cleared")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "lookup surface failed: $t")
        }
    }

    private fun hookClipboardDismiss(module: XposedModule, loader: ClassLoader) {
        val cls = loader.loadClass("com.bytedance.android.input.keyboard.toolbar.clipboard.ToolbarClipboardLayoutImpl")
        val method = cls.getDeclaredMethod("dismiss").apply { isAccessible = true }
        module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val result = chain.proceed()
            try {
                restoreCandidateBar(chain.thisObject as View, module)
            } catch (_: Throwable) {
            }
            result
        }
        module.log(Log.INFO, TAG, "clipboard dismiss hook installed")
    }

    private fun restoreCandidateBar(from: View, module: XposedModule) {
        var p: View? = from
        while (p != null) {
            val n = try { if (p.id == View.NO_ID) "" else p.resources.getResourceEntryName(p.id) } catch (_: Throwable) { "" }
            if (n == "keyboard_main_container_area") {
                val bar = findViewByName(p, "native_candidate_bar")
                if (bar != null && bar.alpha != 1f) {
                    bar.alpha = 1f
                    module.log(Log.INFO, TAG, "candidate bar restored after clipboard dismiss")
                }
                return
            }
            p = p.parent as? View
        }
    }

    private fun hookToolbarBackgroundColorOverride(module: XposedModule) {
        val method = View::class.java.getDeclaredMethod("setBackgroundColor", Int::class.javaPrimitiveType).apply { isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val view = chain.thisObject as View
                val originalColor = chain.getArg(0) as Int
                val earlyId = try { view.resources.getResourceEntryName(view.id) } catch (_: Throwable) { "" }
                val color = if (earlyId in TRANSPARENT_BG_IDS && earlyId != MONET_BG_ID) {
                    0
                } else if (originalColor == 0xff222222.toInt()) {
                    val idName = try { view.resources.getResourceEntryName(view.id) } catch (_: Throwable) { "" }
                    val replacement = globalMonetSurface(view.resources)
                    module.log(Log.INFO, TAG, "intercepted #222222 View.setBackgroundColor id=$idName class=${view.javaClass.name}")
                    replacement
                } else originalColor
                val result = if (color == originalColor) chain.proceed() else chain.proceed(arrayOf(color))
                val idName = try { view.resources.getResourceEntryName(view.id) } catch (_: Throwable) { "" }
                if (idName == MONET_BG_ID || idName in TRANSPARENT_BG_IDS) {
                    if (idName == MONET_BG_ID) {
                        view.background = createMonetColor(view)
                    } else {
                        view.background = ColorDrawable(0x00000000)
                    }
                    if (idName == "native_candidate_bar") {
                        module.log(Log.INFO, TAG, "kept candidate toolbar transparent over Monet body")
                    }
                    module.log(Log.INFO, TAG, "restored Monet after setBackgroundColor id=$idName")
                }
                result
            }
        module.log(Log.INFO, TAG, "toolbar background color override installed")
    }

    private fun hookToolbarBackgroundResourceOverride(module: XposedModule) {
        val method = View::class.java.getDeclaredMethod("setBackgroundResource", Int::class.javaPrimitiveType).apply { isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val view = chain.thisObject as View
                val idName = try { view.resources.getResourceEntryName(view.id) } catch (_: Throwable) { "" }
                if (idName == MONET_BG_ID || idName in TRANSPARENT_BG_IDS) {
                    view.background = if (idName == MONET_BG_ID) createMonetColor(view) else ColorDrawable(0x00000000)
                    module.log(Log.INFO, TAG, "restored Monet after setBackgroundResource id=$idName")
                }
                result
            }
        module.log(Log.INFO, TAG, "toolbar background resource override installed")
    }

    private fun hookInputViewRootToolboxProbe(module: XposedModule, loader: ClassLoader) {
        val rootClass = loader.loadClass(HookPoints.INPUT_VIEW_ROOT)
        // 1.5.3 里对应 setNativeCandidateBarVisible(Z,String)；m2 已不存在。
        // 按结构（bool + String → void）解析，不认名字。
        val method = HookFinder.method(loader, HookPoints.INPUT_VIEW_ROOT, HookPoints.nativeCandidateBarVisible())
            ?: run {
                module.log(Log.WARN, TAG, "nativeCandidateBarVisible not found, skip toolbox probe")
                return
            }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                if (chain.getArg(0) == true) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            // 字段名混淆（旧版 X0），只能按类型猜。
                            // 绝不能取「第一个 View 字段」——那是键盘/全屏容器，
                            // 给它铺半透明强调色就会把整屏染青。
                            // 这里只接受尺寸明显小于整屏、且含 toolbox 子 id 的容器。
                            val candidates = rootClass.declaredFields
                                .filter { View::class.java.isAssignableFrom(it.type) }
                                .mapNotNull { f ->
                                    f.isAccessible = true
                                    (try { f.get(chain.thisObject) } catch (_: Throwable) { null }) as? View
                                }
                            val screenH = chain.thisObject.let { (it as View).resources.displayMetrics.heightPixels }
                            val toolbox = candidates.firstOrNull { v ->
                                v.height in 1..(screenH / 2) &&
                                    v.resources.getIdentifier("toolbox_content_container", "id", v.context.packageName) != 0
                            }
                            if (toolbox != null) applyToolboxSurface(toolbox, module)
                        } catch (t: Throwable) {
                            module.log(Log.WARN, TAG, "toolbox probe failed: $t")
                        }
                    }, 250)
                }
                result
            }
        module.log(Log.INFO, TAG, "InputViewRoot toolbox probe installed")
    }

    private fun hookCreateInputView(module: XposedModule, imeService: Class<*>) {
        val target = imeService.getDeclaredMethod("onCreateInputView").apply { isAccessible = true }
        module.hook(target)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                schedule(chain.thisObject, module)
                result
            }
    }

    private fun hookStartInputView(module: XposedModule, imeService: Class<*>) {
        val target = imeService.getDeclaredMethod("onStartInputView", EditorInfo::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        module.hook(target)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                schedule(chain.thisObject, module)
                result
            }
    }

    private fun schedule(service: Any, module: XposedModule) {
        val handler = Handler(Looper.getMainLooper())
        intArrayOf(100, 300, 700, 1200).forEach { delay ->
            handler.postDelayed({ applyToKeyboardWhole(service, module) }, delay.toLong())
        }
    }

    private fun hookResourceColors(module: XposedModule) {
        hookColor(module, Resources::class.java.getDeclaredMethod("getColor", Int::class.javaPrimitiveType))
        hookColor(module, Resources::class.java.getDeclaredMethod("getColor", Int::class.javaPrimitiveType, Resources.Theme::class.java))
        hookColorStateList(module, Resources::class.java.getDeclaredMethod("getColorStateList", Int::class.javaPrimitiveType))
        hookColorStateList(module, Resources::class.java.getDeclaredMethod("getColorStateList", Int::class.javaPrimitiveType, Resources.Theme::class.java))
        hookGlobalDarkGray(module)
        module.log(Log.INFO, TAG, "resource key color hooks installed")
    }

    private fun hookGlobalDarkGray(module: XposedModule) {
        val colorMethods = listOf(
            Resources::class.java.getDeclaredMethod("getColor", Int::class.javaPrimitiveType),
            Resources::class.java.getDeclaredMethod("getColor", Int::class.javaPrimitiveType, Resources.Theme::class.java)
        )
        colorMethods.forEach { method ->
            method.isAccessible = true
            module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val value = chain.proceed() as Int
                if (value == 0xff222222.toInt()) {
                    val resources = chain.thisObject as Resources
                    val name = try { resources.getResourceEntryName(chain.getArg(0) as Int) } catch (_: Throwable) { "?" }
                    val replacement = globalMonetSurface(resources)
                    module.log(Log.INFO, TAG, "intercepted #222222 resource=$name -> #${Integer.toHexString(replacement)}")
                    replacement
                } else value
            }
        }
        val listMethods = listOf(
            Resources::class.java.getDeclaredMethod("getColorStateList", Int::class.javaPrimitiveType),
            Resources::class.java.getDeclaredMethod("getColorStateList", Int::class.javaPrimitiveType, Resources.Theme::class.java)
        )
        listMethods.forEach { method ->
            method.isAccessible = true
            module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val list = chain.proceed() as ColorStateList
                if (list.defaultColor == 0xff222222.toInt()) {
                    val resources = chain.thisObject as Resources
                    val name = try { resources.getResourceEntryName(chain.getArg(0) as Int) } catch (_: Throwable) { "?" }
                    val replacement = globalMonetSurface(resources)
                    module.log(Log.INFO, TAG, "intercepted #222222 list resource=$name -> #${Integer.toHexString(replacement)}")
                    ColorStateList.valueOf(replacement)
                } else list
            }
        }
        module.log(Log.INFO, TAG, "global #222222 probe installed")
    }

    private fun globalMonetSurface(resources: Resources): Int {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val name = if (dark) "system_accent1_800" else "system_accent1_100"
        val id = resources.getIdentifier(name, "color", "android")
        val base = if (id != 0) resources.getColor(id) else if (dark) 0xff4f5f9b.toInt() else 0xffd7e2ff.toInt()
        return withAlpha(base, 0x66)
    }

    private fun hookKeyboardViewFillRect(module: XposedModule, loader: ClassLoader) {
        val keyboardView = loader.loadClass("com.bytedance.android.input.keyboard.KeyboardView")
        // 官方按压/高亮效果（fillRect 7/10/11 参数，含 SRC_OUT 挖空）一律保留原样，
        // 不再 hook —— 用户要求恢复官方按压行为，不做任何替换。
        // 捕获所有圆角矩形绘制——普通键/功能键背景由原生引擎直接画 Canvas
        try {
            val drawRoundRect = android.graphics.Canvas::class.java.getDeclaredMethod(
                "drawRoundRect", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                android.graphics.Paint::class.java
            )
            module.hook(drawRoundRect)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val paint = chain.getArg(6) as android.graphics.Paint
                    val color = paint.color
                    val left = chain.getArg(0) as Float
                    val top = chain.getArg(1) as Float
                    val right = chain.getArg(2) as Float
                    val bottom = chain.getArg(3) as Float
                    val w = right - left
                    val h = bottom - top
                    val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
                    val isBlue = b > r + 20 && b > g + 10
                    if (w in 40f..1000f && h in 30f..500f && color != 0 && Color.alpha(color) > 0x30) {
                        // 官方按压高亮（蓝色系+低透明）保留；品牌蓝色（高透明）替换为功能键色
                        val alpha = Color.alpha(color)
                        val isPressHighlight = isBlue && alpha < 0x80
                        if (!isPressHighlight) {
                            val repl = translucentGboardKeyColor(color, w.toInt(), left.toInt(), top.toInt(), Resources.getSystem())
                            // 关键：每次按键绘制都清 shader/colorFilter/maskFilter/xfermode，并用 SRC 直接覆盖——
                            // 普通键画在键盘模糊 buffer 上，SRC 直接覆盖像素不叠加，才能和功能键（单层）一致，
                            // 否则半透明+SRC_OVER 在浅色宿主上会叠出浑浊色！
                            paint.shader = null
                            paint.colorFilter = null
                            paint.maskFilter = null
                            paint.xfermode = null
                            if (Build.VERSION.SDK_INT >= 29) {
                                paint.blendMode = BlendMode.SRC
                            } else {
                                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
                            }
                            if (repl != color) {
                                paint.color = repl
                            }
                        }
                    }
                    chain.proceed()
                }
            module.log(Log.INFO, TAG, "Canvas.drawRoundRect key color hook installed")
        } catch (_: Throwable) {}

        // 顺便 hook drawRoundRect(RectF, float, float, Paint) —— 搜索键可能走这个变体
        try {
            val drawRoundRect4 = android.graphics.Canvas::class.java.getDeclaredMethod(
                "drawRoundRect", android.graphics.RectF::class.java,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                android.graphics.Paint::class.java
            )
            module.hook(drawRoundRect4)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val rf = chain.getArg(0) as android.graphics.RectF
                    val paint = chain.getArg(3) as android.graphics.Paint
                    val color = paint.color
                    val w = rf.right - rf.left
                    val h = rf.bottom - rf.top
                    val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
                    val isBlue = b > r + 20 && b > g + 10
                    if (w in 40f..1000f && h in 30f..500f && color != 0 && Color.alpha(color) > 0x30) {
                        val alpha = Color.alpha(color)
                        val isPressHighlight = isBlue && alpha < 0x80
                        if (!isPressHighlight) {
                            val repl = translucentGboardKeyColor(color, w.toInt(), rf.left.toInt(), rf.top.toInt(), Resources.getSystem())
                            // 每次绘制都清 shader/colorFilter/maskFilter，并 SRC 直接覆盖
                            paint.shader = null
                            paint.colorFilter = null
                            paint.maskFilter = null
                            paint.xfermode = null
                            if (Build.VERSION.SDK_INT >= 29) {
                                paint.blendMode = BlendMode.SRC
                            } else {
                                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
                            }
                            if (repl != color) {
                                paint.color = repl
                            }
                        }
                    }
                    chain.proceed()
                }
            module.log(Log.INFO, TAG, "Canvas.drawRoundRect4 key color hook installed")
        } catch (_: Throwable) {}
        module.log(Log.INFO, TAG, "KeyboardView drawRoundRect hook installed (fillRect 官方按压保留)")

    }

    private fun hookKeyboardViewShadow(module: XposedModule, loader: ClassLoader) {
        val method = HookFinder.method(loader, HookPoints.KEYBOARD_VIEW, HookPoints.keyboardShadow()) ?: run {
            module.log(Log.WARN, TAG, "drawShadow not found")
            return
        }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val args = chain.args.toTypedArray()
                args[8] = (args[8] as Int and 0x00ffffff) or (0x20 shl 24)
                chain.proceed(args)
            }
        module.log(Log.INFO, TAG, "KeyboardView shadow softened via ${method.name}")
    }

    private fun hookKeyboardViewTouchBubble(module: XposedModule, loader: ClassLoader) {
        val bubble = HookFinder.method(loader, HookPoints.KEYBOARD_VIEW, HookPoints.touchBubble())
        if (bubble != null) {
            module.hook(bubble)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val args = chain.args.toTypedArray()
                    val monet = monetPopupColor(args[10] as Int, (chain.thisObject as View).resources)
                    args[10] = (monet and 0x00ffffff) or (0x88 shl 24)
                    chain.proceed(args)
                }
        } else module.log(Log.WARN, TAG, "touch bubble not found")

        val shadow = HookFinder.method(loader, HookPoints.KEYBOARD_VIEW, HookPoints.touchBubbleShadow())
        if (shadow != null) {
            module.hook(shadow)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val args = chain.args.toTypedArray()
                    args[10] = (args[10] as Int and 0x00ffffff) or (0x28 shl 24)
                    chain.proceed(args)
                }
        } else module.log(Log.WARN, TAG, "touch bubble shadow not found")
        module.log(Log.INFO, TAG, "long-press bubble hooks resolved")
    }

    private fun hookAnimatedStatusBackground(module: XposedModule, loader: ClassLoader) {
        val status = loader.loadClass("com.bytedance.common_biz.ui.widget.ImeAnimatedStatusView")
        val method = status.getDeclaredMethod("c", status, CharSequence::class.java, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Integer::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val view = chain.getArg(0) as View
                view.background = ColorDrawable(0x00000000)
                module.log(Log.INFO, TAG, "kept animated status background transparent")
                result
            }
        module.log(Log.INFO, TAG, "animated status background hook installed")
    }

    private fun hookAsrStatusSurface(module: XposedModule, loader: ClassLoader) {
        val asr = loader.loadClass("com.bytedance.android.input.speech.view.AsrWaveView")
        val method = asr.getDeclaredMethod("g", String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java, Float::class.javaPrimitiveType).apply { isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val view = chain.thisObject as View
                view.background = ColorDrawable(0x00000000)
                var parent = view.parent as? View
                while (parent != null) {
                    parent.background = ColorDrawable(0x00000000)
                    val parentId = try { parent.resources.getResourceEntryName(parent.id) } catch (_: Throwable) { "" }
                    if (parentId == "native_candidate_bar" || parentId == "keyboard_whole") break
                    parent = parent.parent as? View
                }
                module.log(Log.INFO, TAG, "kept ASR status surface transparent")
                result
            }
        module.log(Log.INFO, TAG, "ASR status surface hook installed")
    }

    private fun hookInputViewAsrStatus(module: XposedModule, loader: ClassLoader) {
        val rootClass = loader.loadClass("com.bytedance.android.input.keyboard.areacontrol.InputViewRoot")
        val method = rootClass.getDeclaredMethod("r2", String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java, Float::class.javaPrimitiveType).apply { isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val root = chain.thisObject as View
                val native = rootClass.getDeclaredField("E").apply { isAccessible = true }.get(chain.thisObject) as? View
                native?.background = ColorDrawable(0x00000000)
                module.log(Log.INFO, TAG, "restored ASR toolbar transparency after r2")
                result
            }
        module.log(Log.INFO, TAG, "InputViewRoot ASR status hook installed")
    }

    /**
     * 第一个候选字的背景改成系统强调色。
     *
     * 1.5.3 的选中态方法 b(I)：index==1 时用 ContextCompat.getDrawable
     * (bg_candidate_item_highlighted) 设背景，深色模式下该 drawable 是品牌蓝 #cc4f84ff。
     *
     * 关键：不能用 view.background = ...，那会重入被 hook 的 View.setBackground，
     * 造成递归并最终把强调色刷满整屏。这里只给现有 drawable 加 tint。
     */
    private fun hookCandidateItemSelected(module: XposedModule, loader: ClassLoader) {
        val method = HookFinder.method(loader, HookPoints.CANDIDATE_ITEM, HookPoints.candidateSelected())
        if (method == null) {
            module.log(Log.WARN, TAG, "candidate selected method not found")
            return
        }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    if (chain.getArg(0) as Int == 1) {
                        val view = chain.thisObject as View
                        MonetEngine.ensureInitialized(view.context)
                        val accent = MonetEngine.accentColor
                        val bg = view.background
                        if (bg != null) {
                            // tint 不经过 setBackground，不会重入
                            bg.setTint(accent)
                            bg.invalidateSelf()
                        }
                    }
                } catch (t: Throwable) {
                    module.log(Log.WARN, TAG, "candidate accent failed: $t")
                }
                result
            }
        module.log(Log.INFO, TAG, "candidate #1 accent hook installed via ${method.name}")
    }

    private fun hookToolbarPageBlur(module: XposedModule, loader: ClassLoader) {
        val adapter = loader.loadClass("com.bytedance.android.input.keyboard.CustomToolbar.ToolbarPagerAdapter")
        val holder = loader.loadClass("androidx.recyclerview.widget.RecyclerView\$ViewHolder")
        val method = adapter.getDeclaredMethod("onCreateViewHolder", ViewGroup::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val result = chain.proceed()
            val itemView = holder.getDeclaredField("itemView").apply { isAccessible = true }.get(result) as? View
            if (itemView != null) itemView.post { applyToolbarPageSurface(itemView, module) }
            result
        }
        val bind = adapter.getDeclaredMethod("onBindViewHolder", holder, Int::class.javaPrimitiveType).apply { isAccessible = true }
        module.hook(bind).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val result = chain.proceed()
            val itemView = holder.getDeclaredField("itemView").apply { isAccessible = true }.get(chain.getArg(0)) as? View
            if (itemView != null) itemView.post { applyToolbarPageSurface(itemView, module) }
            result
        }
        module.log(Log.INFO, TAG, "ToolbarPagerAdapter bind blur hook installed")
    }



    private fun scheduleToolbarRefresh(view: View, module: XposedModule) {
        var parent = view.parent as? View
        while (parent != null && parent.parent != null) {
            val name = parent.javaClass.name
            if (name.contains("RecyclerView") || name.contains("ViewPager2")) break
            parent = parent.parent as? View
        }
        if (parent == null) return
        longArrayOf(0L, 50L, 200L, 500L).forEach { delay ->
            parent.postDelayed({ applyToolbarPageSurface(parent, module) }, delay)
        }
    }

    private fun hookOptionItemToolbarRefresh(module: XposedModule, loader: ClassLoader) {
        listOf(
            HookPoints.setAdded() to "setAdded",
            HookPoints.setIcon() to "setIcon",
            HookPoints.setLabel() to "setLabel",
        ).forEach { (matcher, name) ->
            val m = HookFinder.method(loader, HookPoints.OPTION_ITEM, matcher) ?: run {
                module.log(Log.WARN, TAG, "$name not found in OptionItemView")
                return@forEach
            }
            module.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    scheduleToolbarRefresh(chain.thisObject as View, module)
                    result
                }
        }
        module.log(Log.INFO, TAG, "OptionItemView icon/label refresh hooks installed")
    }

    /**
     * 四叶草工具页：把同容器里的键盘兄弟视图从视觉上清掉。
     *
     * 背景：ToolboxKeyboardView 和键盘本体是 InputViewRoot 里同一个 RelativeLayout 的兄弟节点，
     * BackgroundBlurDrawable 只模糊「窗口背后」的宿主内容，抓不到同窗口的兄弟，
     * 所以半透明面板会让键盘按键清晰透出来、和工具内容重叠。
     *
     * 这里不动面板透明度（用户要求保留透明+模糊），而是在工具页可见期间
     * 把这些兄弟 View 的 alpha 置 0 —— 键盘内容消失，露出键盘那层 0x66 Monet 底 + 模糊；
     * 工具页关闭时逐个恢复。
     */
    private fun hookToolboxToggle(module: XposedModule, loader: ClassLoader) {
        val method = HookFinder.method(loader, HookPoints.INPUT_VIEW_ROOT, HookPoints.toolboxToggle()) ?: run {
            module.log(Log.WARN, TAG, "toolbox toggle not found, skip isolate")
            return
        }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    val show = chain.getArg(1) == true
                    val root = chain.thisObject
                    Handler(Looper.getMainLooper()).post { isolateKeyboardForToolbox(root, show, module) }
                } catch (t: Throwable) {
                    module.log(Log.WARN, TAG, "toolbox isolate post failed: $t")
                }
                result
            }
        module.log(Log.INFO, TAG, "toolbox toggle hook installed via ${method.name}")
    }

    private val hiddenSiblingAlpha = HashMap<View, Float>()

    private fun hookEmojiToggle(module: XposedModule, loader: ClassLoader) {
        val method = HookFinder.method(loader, HookPoints.INPUT_VIEW_ROOT, HookPoints.emojiToggle()) ?: run {
            module.log(Log.WARN, TAG, "emoji toggle not found, skip isolate")
            return
        }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    val show = chain.getArg(0) == true
                    val root = chain.thisObject
                    Handler(Looper.getMainLooper()).post { isolateKeyboardForEmoji(root, show, module) }
                } catch (t: Throwable) {
                    module.log(Log.WARN, TAG, "emoji isolate post failed: $t")
                }
                result
            }
        module.log(Log.INFO, TAG, "emoji toggle hook installed via ${method.name}")
    }

    /**
     * 表情面板底部那一栏（笑脸/手势/人物/爱心…）用 SegmentedBar，
     * 原生背景是灰不透明的 bg_segmented_bar（#0f000000 / night #0f2f2f2f 之上再叠底色）。
     * 统一成和键盘一致的 Monet 半透明底。
     */
    private fun hookEmojiSegmentedBar(module: XposedModule, loader: ClassLoader) {
        val clazz = try { loader.loadClass("com.bytedance.android.input.keyboard.component.SegmentedBar") } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "SegmentedBar class missing: $t")
            return
        }
        // 初始化完成点：setItems(List) —— 每次绑定分类列表都会走到
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "setItems" && it.parameterTypes.size == 1 &&
                List::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: run {
            module.log(Log.WARN, TAG, "SegmentedBar.setItems not found")
            return
        }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val bar = chain.thisObject as View
                Handler(Looper.getMainLooper()).post { applySegmentedBarSurface(bar, module) }
                result
            }
        module.log(Log.INFO, TAG, "SegmentedBar surface hook installed")
    }

    private fun applySegmentedBarSurface(bar: View, module: XposedModule) {
        try {
            val dark = bar.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val suffix = if (dark) "800" else "100"
            val raw = monetColor(bar, "system_accent1_$suffix")
            val base = if (raw == 0) {
                if (dark) 0xff303030.toInt() else 0xfff0f0f0.toInt()
            } else raw
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = bar.resources.displayMetrics.density * 20f
                setColor(withAlpha(base, 0x66))
            }
            bar.background = bg
            module.log(Log.INFO, TAG, "segmented bar surface dark=$dark")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "segmented bar surface failed: $t")
        }
    }

    /**
     * 表情面板整体底色。
     *
     * emoji_layout 的根 ConstraintLayout 在 XML 里写死 `background=@color/navigation_bar_normal`
     * （night = #ff222222，完全不透明），inflate 走 setBackgroundResource 不经过 Resources.getColor，
     * 所以 globalDarkGray 那套替换抓不到它 —— 结果就是面板底下那一条是死灰色。
     * 这里把它清成透明，让 keyboard_whole 的 0x66 Monet 底 + 模糊透上来。
     */
    private fun applyEmojiSurface(emoji: View, module: XposedModule) {
        try {
            if (emoji is ViewGroup) {
                for (i in 0 until emoji.childCount) {
                    val child = emoji.getChildAt(i)
                    if (child.background != null) child.background = ColorDrawable(0x00000000)
                }
            }
            emoji.background = ColorDrawable(0x00000000)
            val barId = emoji.resources.getIdentifier("segmented_bar", "id", emoji.context.packageName)
            if (barId != 0) emoji.findViewById<View>(barId)?.let { applySegmentedBarSurface(it, module) }

            // 分类 tab 栏（笑脸/手势/人物/爱心…）是 TabLayout，不是 SegmentedBar。
            // 它自身和每个 tab 内的自定义 TextView 都挂着 drawable/emoji_btn_tab —— 
            // 一个深灰不透明 selector（默认 #ff2f2f2f / 选中 #ff434343），
            // 所以整条栏是死的灰色。这里全部清掉，只留键盘那层 0x66 Monet 底。
            listOf("emoji_tab", "kaomoji_tab").forEach { name ->
                val id = emoji.resources.getIdentifier(name, "id", emoji.context.packageName)
                if (id == 0) return@forEach
                (emoji.findViewById<View>(id) as? ViewGroup)?.let { clearTabBarBackground(it, module) }
            }
            // 同一行的时钟格子挂着同一颗 selector，单独清一下
            val tabId = emoji.resources.getIdentifier("emoji_tab", "id", emoji.context.packageName)
            val tab = if (tabId != 0) emoji.findViewById<View>(tabId) else null
            val row = tab?.parent as? ViewGroup
            if (row != null) {
                for (i in 0 until row.childCount) {
                    val c = row.getChildAt(i)
                    if (c !== tab && kotlin.math.abs(c.top - tab.top) < 4) tintEmojiButton(c, module)
                }
            }
            // 删除键：底色和图标都换成强调色
            val delId = emoji.resources.getIdentifier("btn_backspace", "id", emoji.context.packageName)
            if (delId != 0) emoji.findViewById<View>(delId)?.let { tintEmojiButton(it, module) }
            module.log(Log.INFO, TAG, "emoji surface cleared")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "emoji surface failed: $t")
        }
    }

    /**
     * 清掉 TabLayout 整条分类栏的背景（含每个 tab 内的自定义 view）。
     *
     * 只改 TabView.background 不够：TabLayout 自己缓存了 tabBackgroundResId /
     * tabBackgroundDrawable，任何一次 updateTabViews()（切分类、横向滚动、重新布局）
     * 都会把那个不透明 selector 原样套回去 —— 表现就是"改完还是灰的"。
     * 所以先把这两个源头字段废掉，再清现存 View。
     */
    private fun clearTabBarBackground(tabBar: ViewGroup, module: XposedModule) {
        tabBar.background = ColorDrawable(0x00000000)
        var killed = 0
        for (f in tabBar.javaClass.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            if (!f.name.lowercase().contains("tabbackground")) continue
            try {
                f.isAccessible = true
                when {
                    f.type == Int::class.javaPrimitiveType -> { f.setInt(tabBar, 0); killed++ }
                    Drawable::class.java.isAssignableFrom(f.type) -> { f.set(tabBar, null); killed++ }
                }
            } catch (_: Throwable) {
            }
        }
        if (killed > 0) Log.i(TAG, "tab bar background source killed n=$killed")
        for (i in 0 until tabBar.childCount) {
            clearTabItemBackground(tabBar.getChildAt(i))
        }
        // TabView 是 TabLayout 内部类，custom view 常延迟创建，补一次
        tabBar.post {
            var n = 0
            for (i in 0 until tabBar.childCount) n += clearTabItemBackground(tabBar.getChildAt(i))
            if (n > 0) module.log(Log.INFO, TAG, "tab selector tinted n=$n")
        }
    }

    /**
     * 递归清掉 tab 子树背景。
     *
     * 那颗灰在最深一层：TabLayout -> SlidingTabIndicator -> TabView -> TextView，
     * TabView 的 TextView 挂着 StateListDrawable(emoji_btn_tab)，
     * 默认 #ff2f2f2f / 选中 #ff434343，正好铺满 108x68 一整格 —— 就是那条死灰。
     * 直接抹平会丢掉"当前选中"的反馈，所以换成 Monet 半透明版本：
     * 未选中全透明，选中是强调色 40% 的圆角块。
     */
    private fun clearTabItemBackground(view: View): Int {
        var n = 0
        when (val bg = view.background) {
            null -> {}
            is StateListDrawable -> {
                view.background = monetTabSelector(view)
                n++
            }
            is ColorDrawable -> if (bg.color != Color.TRANSPARENT) {
                view.background = ColorDrawable(0x00000000)
                n++
            }
            else -> {
                view.background = ColorDrawable(0x00000000)
                n++
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) n += clearTabItemBackground(view.getChildAt(i))
        return n
    }

    /** 表情面板里的功能键（时钟、删除）：底色和图标统一换成 Monet 强调色。 */
    private fun tintEmojiButton(view: View, module: XposedModule) {
        try {
            val dark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val raw = monetColor(view, if (dark) "system_accent1_200" else "system_accent1_600")
            val base = if (raw == 0) {
                if (dark) 0xff9fb0e8.toInt() else 0xff3f4f8f.toInt()
            } else raw
            view.background = GradientDrawable().apply {
                setShape(GradientDrawable.RECTANGLE)
                cornerRadius = view.resources.displayMetrics.density * 24f
                setColor(withAlpha(base, 0x33))
            }
            if (view is android.widget.TextView) {
                view.setTextColor(base)
                val cur = view.compoundDrawables
                val ds: Array<Drawable?> = Array(4) { i -> cur.getOrNull(i)?.mutate()?.apply { setTint(base) } }
                if (ds.any { it != null }) view.setCompoundDrawablesWithIntrinsicBounds(ds[0], ds[1], ds[2], ds[3])
            }
            if (view is android.widget.ImageView) view.imageTintList = ColorStateList.valueOf(base)
            val name = try { view.resources.getResourceEntryName(view.id) } catch (_: Throwable) { "?" }
            module.log(Log.INFO, TAG, "emoji button tinted #$name -> #" + Integer.toHexString(base))
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "emoji button tint failed: $t")
        }
    }

    private fun monetTabSelector(view: View): Drawable {
        val dark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val raw = monetColor(view, if (dark) "system_accent1_800" else "system_accent1_100")
        val base = if (raw == 0) {
            if (dark) 0xff303030.toInt() else 0xfff0f0f0.toInt()
        } else raw
        val radius = view.resources.displayMetrics.density * 16f
        fun shape(color: Int) = GradientDrawable().apply {
            setShape(GradientDrawable.RECTANGLE)
            cornerRadius = radius
            setColor(color)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), shape(withAlpha(base, 0x66)))
            addState(intArrayOf(), shape(Color.TRANSPARENT))
        }
    }

    private fun isolateKeyboardForToolbox(root: Any, show: Boolean, module: XposedModule) {
        isolateOverlay(root, HookPoints.TOOLBOX, show, module, "toolbox")
    }

    /**
     * 表情面板：和工具页同一套隔离逻辑。
     *
     * EmojiLayout 也是加进 mKeyboardMainContainerArea 的兄弟节点，
     * 半透明时底下键盘和顶部工具栏都会透上来 —— 显示期间把这些兄弟 alpha 置 0，
     * 只留表情面板本身，露出键盘那层 0x66 Monet 底 + 模糊。
     */
    private fun isolateKeyboardForEmoji(root: Any, show: Boolean, module: XposedModule) {
        isolateOverlay(root, HookPoints.EMOJI_LAYOUT, show, module, "emoji")
    }

    private fun isolateOverlay(
        root: Any,
        targetClass: String,
        show: Boolean,
        module: XposedModule,
        tag: String,
    ) {
        try {
            val host = findOverlayHost(root, targetClass) ?: run {
                module.log(Log.WARN, TAG, "$tag host not found")
                return
            }
            val (container, target) = host
            var count = 0
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child === target) continue
                hideOrRestoreAlpha(child, show)
                count++
            }
            // 顶部工具栏在另一个容器里，单独处理
            val bar = findViewByName(root as? View, "native_candidate_bar")
            if (bar != null && bar !== target) {
                hideOrRestoreAlpha(bar, show)
                count++
            }
            if (show && targetClass == HookPoints.TOOLBOX) applyToolboxSurface(target, module)
            if (show && targetClass == HookPoints.EMOJI_LAYOUT) applyEmojiSurface(target, module)
            module.log(
                Log.INFO, TAG,
                "$tag isolate show=$show container=${container.childCount} touched=$count hidden=${hiddenSiblingAlpha.size}"
            )
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "$tag isolate failed: $t")
        }
    }

    /**
     * 隐藏兄弟视图。只把 alpha 置 0 是不够的 —— alpha=0 的 View 照样吃触摸事件，
     * 表现为"点关闭键却触发底下那层的按钮"。所以同时设 INVISIBLE：
     * 布局占位不变（不像 GONE 会重排），但不再参与事件分发。
     */
    private fun hideOrRestoreAlpha(view: View, show: Boolean) {
        if (show) {
            if (view.visibility == View.VISIBLE) {
                hiddenSiblingAlpha[view] = view.alpha
                view.alpha = 0f
                view.visibility = View.INVISIBLE
            }
        } else {
            hiddenSiblingAlpha.remove(view)?.let {
                view.alpha = it
                view.visibility = View.VISIBLE
            }
        }
    }

    private fun findViewByName(root: View?, name: String): View? {
        val anchor = root ?: return null
        val id = anchor.resources.getIdentifier(name, "id", anchor.context.packageName)
        if (id == 0) return null
        return anchor.rootView.findViewById(id)
    }

    /** 反射扫 InputViewRoot 的 ViewGroup 字段，找直接包含 [targetClass] 实例的那个容器。 */
    private fun findOverlayHost(root: Any, targetClass: String): Pair<ViewGroup, View>? {
        for (f in root.javaClass.declaredFields) {
            if (!ViewGroup::class.java.isAssignableFrom(f.type)) continue
            f.isAccessible = true
            val group = try { f.get(root) as? ViewGroup } catch (_: Throwable) { null } ?: continue
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child.javaClass.name == targetClass) return group to child
            }
        }
        return null
    }

    private fun hookToolboxKeyboardView(module: XposedModule, loader: ClassLoader) {
        val method = HookFinder.method(loader, HookPoints.TOOLBOX, HookPoints.toolboxRefresh()) ?: run {
            module.log(Log.WARN, TAG, "ToolboxKeyboardView refresh not found")
            return
        }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                applyToolboxSurface(chain.thisObject as View, module)
                result
            }
        // 注意：绝不能 hook View.onAttachedToWindow —— 那对进程里每一个 View 都生效，
        // 而 applyToolboxSurface 会给 thisObject 铺半透明强调色背景，等于把整屏染色。
        // 只保留上面针对 ToolboxKeyboardView 自身方法的 hook。
        module.log(Log.INFO, TAG, "ToolboxKeyboardView refresh hook installed via ${method.name}")
    }

    private fun hookToolboxItemView(module: XposedModule, loader: ClassLoader) {
        val method = HookFinder.method(loader, HookPoints.TOOLBOX_ITEM, HookPoints.toolboxItemRefresh()) ?: run {
            module.log(Log.WARN, TAG, "ToolboxItemView refresh not found")
            return
        }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                applyToolboxItemSurface(chain.thisObject as View, module)
                result
            }
        module.log(Log.INFO, TAG, "ToolboxItemView surface hook installed")
    }

    private fun applyToolboxItemSurface(view: View, module: XposedModule) {
        val dark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val raw = monetColor(view, if (dark) "system_neutral1_700" else "system_neutral1_100")
        val color = (if (raw == 0) {
            if (dark) 0xff454746.toInt() else 0xffe1e3e1.toInt()
        } else raw) and 0x00ffffff or (0x99 shl 24)
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = view.resources.displayMetrics.density * 14f
            setColor(color)
        }
        val id = view.resources.getIdentifier("toolbox_item_content", "id", view.context.packageName)
        view.findViewById<View>(id)?.background = background
        module.log(Log.INFO, TAG, "applied toolbox item surface dark=$dark")
    }
    private fun applyToolboxSurface(root: View, module: XposedModule) {
        val dark = root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val suffix = if (dark) "800" else "100"
        val raw = monetColor(root, "system_accent1_$suffix")
        val color = if (raw == 0) {
            if (dark) 0xff303030.toInt() else 0xfff0f0f0.toInt()
        } else {
            (raw and 0x00ffffff) or (0x99 shl 24)
        }
        // 这个 View 位于键盘背景（0x66 Monet 底）之上。
        // 再铺一层 0x99 的色会叠加成更深的颜色，导致和键盘背景不一致 ——
        // 所以这里保持透明，直接让键盘那层背景透上来，颜色和模糊自然一致。
        root.background = ColorDrawable(0x00000000)
        if (root is ViewGroup && root.childCount > 0) root.getChildAt(0).background = null
        val packageName = root.context.packageName
        listOf("toolbox_header_container", "toolbox_content_container").forEach { name ->
            val id = root.resources.getIdentifier(name, "id", packageName)
            if (id != 0) root.findViewById<View>(id)?.background = null
        }
        module.log(Log.INFO, TAG, "kept toolbox surface transparent ${root.width}x${root.height} dark=$dark")
    }

    private fun hookPopupWindowBlur(module: XposedModule) {
        val wm = Class.forName("android.view.WindowManagerImpl")
        val addView = wm.getMethod("addView", View::class.java, WindowManager.LayoutParams::class.java)
            .apply { isAccessible = true }
        module.hook(addView)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val view = chain.getArg(0) as? View
                if (view?.context?.packageName == "com.bytedance.android.doubaoime") {
                    view.postDelayed({ applyBlurToPopupRoot(view, module) }, 80)
                }
                result
            }
        module.log(Log.INFO, TAG, "WindowManager popup blur hook installed")
    }

    private fun clearToolbarContainers(view: View) {
        if (view !is ViewGroup) return
        view.background = null
        for (i in 0 until view.childCount) clearToolbarContainers(view.getChildAt(i))
    }

    private fun applyToolbarPageSurface(root: View, module: XposedModule) {
        val dark = root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val suffix = if (dark) "800" else "100"
        val raw = monetColor(root, "system_accent1_$suffix")
        val color = if (raw == 0) {
            if (dark) 0xff303030.toInt() else 0xfff0f0f0.toInt()
        } else {
            (raw and 0x00ffffff) or (0x99 shl 24)
        }
        val surface = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = root.resources.displayMetrics.density * 16f
            setColor(color)
        }
        clearToolbarContainers(root)
        root.background = surface
        module.log(Log.INFO, TAG, "applied toolbar Monet surface ${root.width}x${root.height} dark=$dark")
    }

    private fun applyBlurToPopupRoot(root: View, module: XposedModule) {
        try {
            val getViewRootImpl = View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }
            val viewRootImpl = getViewRootImpl.invoke(root.rootView) ?: return
            val viewRootClass = Class.forName("android.view.ViewRootImpl")
            val create = viewRootClass.getDeclaredMethod("createBackgroundBlurDrawable").apply { isAccessible = true }
            val blur = create.invoke(viewRootImpl) as? Drawable ?: return
            val blurClass = blur.javaClass
            blurClass.getDeclaredMethod("setBlurRadius", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(blur, BLUR_RADIUS)
            blurClass.getDeclaredMethod("setCornerRadius", Float::class.javaPrimitiveType).apply { isAccessible = true }.invoke(blur, CORNER_RADIUS)
            val monet = createMonetColor(root)
            root.background = if (monet == null) blur else LayerDrawable(arrayOf(blur, monet))
            module.log(Log.INFO, TAG, "installed popup blur class=${root.javaClass.name} bounds=${root.width}x${root.height}")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "popup blur skipped: $t")
        }
    }

    private fun monetPopupColor(original: Int, resources: Resources): Int {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val r = Color.red(original)
        val g = Color.green(original)
        val b = Color.blue(original)
        val blue = b > r + 20 && b > g + 10
        val name = when {
            blue -> if (dark) "system_accent1_700" else "system_accent1_600"
            dark -> "system_neutral1_700"
            else -> "system_neutral1_100"
        }
        val system = Resources.getSystem()
        val id = system.getIdentifier(name, "color", "android")
        val base = if (id == 0) original else system.getColor(id)
        return (base and 0x00ffffff) or (0xb8 shl 24)
    }

    /** 从 Resources 反射获取 Context（避免 null），供延迟初始化用 */
    private fun contextFromResources(resources: Resources): android.content.Context {
        try {
            // 1. 尝试 ActivityThread.currentApplication()
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getMethod("currentApplication").invoke(null) as? android.content.Context
            if (app != null) return app
        } catch (_: Throwable) {}
        // 2. Resources.getSystem() 反射拿 mResources 字段 → mContext
        try {
            val f = resources.javaClass.getDeclaredField("mResourcesImpl")
            f.isAccessible = true
            val impl = f.get(resources)
            val cf = impl.javaClass.getDeclaredField("mContext")
            cf.isAccessible = true
            val ctx = cf.get(impl) as? android.content.Context
            if (ctx != null) return ctx
        } catch (_: Throwable) {}
        // 3. 兜底：getSystem() 的 Resources 反射
        try {
            val sys = Resources.getSystem()
            val f = sys.javaClass.getDeclaredField("mResourcesImpl")
            f.isAccessible = true
            val impl = f.get(sys)
            val cf = impl.javaClass.getDeclaredField("mContext")
            cf.isAccessible = true
            val ctx = cf.get(impl) as? android.content.Context
            if (ctx != null) return ctx
        } catch (_: Throwable) {}
        throw IllegalStateException("cannot get context from Resources")
    }

    /** 按键角色 */
    private enum class KeyRole { FUNCTION, NORMAL }

    /**
     * 一对一精确映射：豆包拼音全拼键盘每个按键的位置（left, top）→ 角色。
     * 依据 drawKey 日志实测布局：
     *   第1行 (top=160)：Q W E R T Y U I O P          （全部普通键）
     *   第2行 (top=304)：A S D F G H J K L            （全部普通键）
     *   第3行 (top=448)：Shift Z X C V B N M Delete    （两端功能键，中间普通键）
     *   第4行 (top=592)：符号 123 逗号 空格 句号 中英 完成
     * 功能键（共6个）：Shift[13,448] Delete[874,448] 符号[13,592] 123[146,592] 中英[758,592] 完成[874,592]
     * 普通键：字母/逗号[261,592]/空格[361,592]/句号[659,592]
     */
    private fun keyRoleFor(left: Int, top: Int): KeyRole {
        // 功能键坐标集合（每个键的左上角，误差容限 ±15px）
        val functionKeys = arrayOf(
            // 第3行两端
            intArrayOf(13, 448),    // 大小写 Shift
            intArrayOf(874, 448),   // 删除 Delete
            // 第4行
            intArrayOf(13, 592),    // 符号
            intArrayOf(146, 592),   // 123
            intArrayOf(758, 592),   // 中英切换
            // 注意：[874,592] 搜索/完成键已由品牌蓝颜色判断处理为透明，不在此列表，避免二次绘制涂成莫奈色
        )
        for (k in functionKeys) {
            if (Math.abs(left - k[0]) <= 15 && Math.abs(top - k[1]) <= 15) {
                return KeyRole.FUNCTION
            }
        }
        return KeyRole.NORMAL
    }

    private fun translucentGboardKeyColor(original: Int, width: Int, left: Int, top: Int, resources: Resources): Int {
        // 只在未初始化时才反射找 context：每次绘制都反射会直接卡住打字
        if (!MonetEngine.isReady()) {
            try {
                MonetEngine.ensureInitialized(contextFromResources(resources))
            } catch (_: Throwable) {}
        }
        val r = Color.red(original)
        val g = Color.green(original)
        val b = Color.blue(original)
        val a = Color.alpha(original)
        val dark = MonetEngine.isDarkMode ||
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)

        // 品牌蓝（搜索/完成键，orig=#ff4f84ff）→ 透明色（和普通键一致）
        val isBrandBlue = (b > r + 20 && b > g + 10) && a >= 0x80
        if (isBrandBlue) {
            return if (dark) MonetEngine.keyNormalDark else MonetEngine.keyNormalLight
        }

        // 一对一映射分组：功能键 → 莫奈色；普通键 → 透明（豆包原生）
        return when (keyRoleFor(left, top)) {
            KeyRole.FUNCTION -> if (dark) MonetEngine.keyFunctionDark else MonetEngine.keyFunctionLight
            KeyRole.NORMAL -> if (dark) MonetEngine.keyNormalDark else MonetEngine.keyNormalLight
        }
    }

    private fun hookColor(module: XposedModule, method: Method) {
        method.isAccessible = true
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val resources = chain.thisObject as Resources
                val replacement = resolveKeyColor(resources, chain.getArg(0) as Int)
                if (replacement != null) replacement else chain.proceed()
            }
    }

    private fun hookColorStateList(module: XposedModule, method: Method) {
        method.isAccessible = true
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val resources = chain.thisObject as Resources
                val replacement = resolveKeyColor(resources, chain.getArg(0) as Int)
                if (replacement != null) ColorStateList.valueOf(replacement) else chain.proceed()
            }
    }

    /** 资源 id → 角色缓存。getColor 是输入法最热的 API，每次 JNI 查名会拖垮打字。 */
    private val roleCache = HashMap<Int, Role?>()

    private fun resolveKeyColor(resources: Resources, id: Int): Int? {
        val role = if (roleCache.containsKey(id)) roleCache[id] else {
            val name = try { resources.getResourceEntryName(id) } catch (_: Throwable) { null }
            val r = when (name) {
            "ime_animated_status_bg" -> return 0x00000000
            "ime_key_normal_bg_color" -> Role.KEY
            "ime_key_normal_press_bg_color" -> Role.KEY_PRESSED
            "ime_key_gray_clickable_bg_color", "ime_key_gray_un_clickable_bg_color" -> Role.FUNCTION
            "ime_key_gray_clickable_press_bg_color", "ime_key_gray_un_clickable_press_bg_color" -> Role.FUNCTION_PRESSED
            "ime_key_blue_clickable_bg_color" -> Role.ACCENT
            "ime_key_blue_clickable_press_bg_color" -> Role.ACCENT_PRESSED
            "ime_key_text_clickable_color" -> Role.FOREGROUND
            "ime_key_text_un_clickable_color", "ime_key_hint_text_color", "ime_space_button_text_color" -> Role.FOREGROUND_MUTED
            "ime_key_shadow_color" -> Role.SHADOW
            else -> null
            }
            roleCache[id] = r
            r
        }
        return role?.color(resources)
    }

    private enum class Role {
        KEY, KEY_PRESSED, FUNCTION, FUNCTION_PRESSED, ACCENT, ACCENT_PRESSED,
        FOREGROUND, FOREGROUND_MUTED, SHADOW;

        fun color(resources: Resources): Int {
            val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            // 功能键 FUNCTION / 操作键 ACCENT：直接用 MonetEngine 莫奈色（secondaryContainer #364954）
            if (this == FUNCTION || this == ACCENT) {
                return if (dark) MonetEngine.keyFunctionDark else MonetEngine.keyFunctionLight
            }
            if (this == FUNCTION_PRESSED || this == ACCENT_PRESSED) {
                return if (dark) MonetEngine.keyFunctionDark else MonetEngine.keyFunctionLight
            }
            // 普通键按压 KEY_PRESSED：变莫奈色（和功能键一致）
            if (this == KEY_PRESSED) {
                return if (dark) MonetEngine.keyFunctionDark else MonetEngine.keyFunctionLight
            }
            // 普通键未按压 KEY：透明（豆包原生 #47ffffff）
            if (this == KEY) {
                return if (dark) 0x47FFFFFF.toInt() else 0xFFFFFFFF.toInt()
            }
            val name = when (this) {
                FOREGROUND -> if (dark) "system_neutral1_10" else "system_neutral1_900"
                FOREGROUND_MUTED -> if (dark) "system_neutral2_100" else "system_neutral2_700"
                SHADOW -> if (dark) "system_neutral1_900" else "system_neutral1_100"
                else -> "system_neutral1_800"
            }
            val id = resources.getIdentifier(name, "color", "android")
            val base = if (id == 0) 0 else Resources.getSystem().getColor(id)
            return base
        }
    }

    private fun clearOverlayBackground(decor: View, name: String) {
        val id = decor.resources.getIdentifier(name, "id", "com.bytedance.android.doubaoime")
        val view = if (id == 0) null else decor.findViewById<View>(id)
        view?.background = ColorDrawable(0x00000000)
    }

    private fun createMonetColor(decor: View): Drawable? {
        val dark = decor.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val suffix = if (dark) "800" else "100"
        val raw = monetColor(decor, "system_accent1_$suffix")
        return if (raw == 0) null else ColorDrawable(withAlpha(raw, 0x66))
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00ffffff) or (alpha shl 24)

    // HSV 饱和度增强：把中性偏灰的 Monet 色变得鲜艳强调
    private fun boostSaturation(color: Int, targetS: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = targetS
        val boosted = Color.HSVToColor(hsv)
        return (boosted and 0x00ffffff) or (0xFF shl 24)
    }

    private fun monetColor(decor: View, name: String): Int {
        val id = decor.resources.getIdentifier(name, "color", "android")
        if (id == 0) return 0
        return try { decor.context.getColor(id) } catch (_: Throwable) { 0 }
    }

    private fun applyToKeyboardWhole(service: Any, module: XposedModule) {
        try {
            if (keyboardWholeBlurred) return
            val getWindow = service.javaClass.getMethod("getWindow")
            val dialog = getWindow.invoke(service) as? Dialog ?: return
            val window: Window = dialog.window ?: return
            val decor = window.decorView
            val id = decor.resources.getIdentifier("keyboard_whole", "id", "com.bytedance.android.doubaoime")
            val keyboardWhole = decor.findViewById<View>(id) ?: run {
                module.log(Log.WARN, TAG, "keyboard_whole not attached yet, id=$id")
                return
            }
            clearOverlayBackground(decor, "native_candidate_bar")
            clearOverlayBackground(decor, "keyboard_main_container_area")
            clearOverlayBackground(decor, "keyboard_whole_llm")
            clearOverlayBackground(decor, "input_view_image_bg")
            val monet = createMonetColor(decor)
            // keyboardWhole.background = monet  // 测试：禁用 Monet 染色
            val toolbarId = decor.resources.getIdentifier("native_candidate_bar", "id", "com.bytedance.android.doubaoime")
            if (toolbarId != 0) decor.findViewById<View>(toolbarId)?.background = ColorDrawable(0x00000000)
            applyKeyboardWholeBlur(keyboardWhole, decor, module)
            keyboardWholeBlurred = true
            module.log(Log.INFO, TAG, "applied Monet keyboard and candidate toolbar with keyboard blur")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "keyboard_whole blur skipped: $t")
        }
    }

    // 参考魔改版 BlurEffectManager：把 BackgroundBlurDrawable 应用到键盘面板 keyboard_whole。
    // BackgroundBlurDrawable 的模糊区域跟随 View 的 bounds，所以只模糊键盘面板区域，
    // 键盘上方的宿主应用区域保持清晰。与半透明 Monet 组成 LayerDrawable。
    private fun applyKeyboardWholeBlur(keyboardWhole: View, decor: View, module: XposedModule) {
        try {
            val getViewRootImpl = View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }
            val viewRootImpl = getViewRootImpl.invoke(decor.rootView) ?: run {
                module.log(Log.WARN, TAG, "keyboard blur: no view root yet")
                return
            }
            val viewRootClass = Class.forName("android.view.ViewRootImpl")
            val create = viewRootClass.getDeclaredMethod("createBackgroundBlurDrawable").apply { isAccessible = true }
            val blur = create.invoke(viewRootImpl) as? Drawable ?: return
            val blurClass = blur.javaClass
            blurClass.getDeclaredMethod("setBlurRadius", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(blur, BLUR_RADIUS)
            val monet = createMonetColor(decor)
            keyboardWhole.background = if (monet == null) blur else LayerDrawable(arrayOf(blur, monet))
            module.log(Log.INFO, TAG, "installed keyboard blur bounds=${keyboardWhole.width}x${keyboardWhole.height}")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "keyboard blur skipped: $t")
        }
    }
}
