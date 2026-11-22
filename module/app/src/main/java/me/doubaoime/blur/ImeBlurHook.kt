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
     * 调试开关。默认关闭：第一版把 View.setBackground / Resources.getColor 这类
     * 每次 inflate、每次按键都会走的热路径全量打日志，第一次弹键盘会因此明显卡顿。
     * 需要排查时再打开，开启后也只保留关键分支日志。
     */
    private const val DEBUG = false

    private inline fun dlog(module: XposedModule, msg: () -> String) {
        if (DEBUG) module.log(Log.DEBUG, TAG, msg())
    }

    /** getResourceEntryName 是 JNI 调用，热路径上按 id 缓存。 */
    private val entryNameCache = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private fun entryName(view: View): String = try {
        val id = view.id
        if (id == View.NO_ID || id == 0) "" else entryNameCache.getOrPut(id) {
            view.resources.getResourceEntryName(id)
        }
    } catch (_: Throwable) {
        ""
    }

    /** 资源 id 同样按名字缓存，避免每次弹键盘都走 AssetManager 查表。 */
    private val resIdCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun resId(resources: Resources, name: String): Int =
        resIdCache[name] ?: resources.getIdentifier(name, "id", "com.bytedance.android.doubaoime")
            .also { if (it != 0) resIdCache[name] = it }

    /**
     * 是否位于 keyboard_whole（0x66 Monet 底板）内部。
     * 底板已经铺过一层半透明强调色，子容器再吃一次 #222222→0x66 就会叠成双层，
     * 表现为工具栏/工具页某一块颜色明显更深。这种子容器应当保持透明。
     */
    private var keyboardWholeRef: java.lang.ref.WeakReference<View>? = null

    private fun keyboardWhole(view: View): View? {
        val cached = keyboardWholeRef?.get()
        if (cached != null && cached.rootView === view.rootView) return cached
        val id = resId(view.resources, "keyboard_whole")
        if (id == 0) return null
        val whole = try { view.rootView.findViewById<View>(id) } catch (_: Throwable) { null }
        if (whole != null) keyboardWholeRef = java.lang.ref.WeakReference(whole)
        return whole
    }

    /**
     * 是否压在 keyboard_whole 的 0x66 底板之上。
     *
     * 光看 `isInsideKeyboardWhole()` 不够：工具栏那一行（ImeToolBarContainer）挂在
     * input_view_root 上、和 keyboard_whole 是兄弟节点，屏幕位置却完全压在底板上。
     * 它内部子容器再吃一次 `#222222` → 0x66 就是用户看到的「工具栏被附身双层 Monet」。
     * 所以只要 rootView 里存在 keyboard_whole（即同一个输入法窗口），就当作在底板上方。
     */
    private fun isOverMonetBoard(view: View): Boolean {
        if (isInsideKeyboardWhole(view)) return true
        val wholeId = resId(view.resources, "keyboard_whole")
        if (wholeId == 0) return false
        return try { view.rootView.findViewById<View>(wholeId) != null } catch (_: Throwable) { false }
    }

    private fun isInsideKeyboardWhole(view: View): Boolean {
        val whole = keyboardWhole(view) ?: return false
        var p: View? = view
        while (p != null) {
            if (p === whole) return true
            p = p.parent as? View
        }
        return false
    }

    /**
     * 候选栏保护区。
     *
     * 应用不是"清空候选"，而是用不透明的空闲视图（CandidateIdleView / lookup_tool_bar_placeholder）
     * 盖住候选。我们把这一片做成透明后，遮罩的信息量就没了，旧候选一直留在屏幕上。
     * 所以这些 view 一律不染色、不透明化，保持原样。
     */
    private val CANDIDATE_PROTECTED_IDS = setOf("native_candidate_bar", "lookup_tool_bar_placeholder")

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
            "toolboxItem" to { hookToolboxItemView(module, loader) },
            "candidate" to { hookCandidateItemSelected(module, loader) },
            "optionItem" to { hookOptionItemToolbarRefresh(module, loader) },
            "toolboxProbe" to { hookInputViewRootToolboxProbe(module, loader) },
            "toolboxToggle" to { hookToolboxToggle(module, loader) },
            "emojiToggle" to { hookEmojiToggle(module, loader) },
            "emojiBar" to { hookEmojiSegmentedBar(module, loader) },
            "clipboardDismiss" to { hookClipboardDismiss(module, loader) },
            "superAssociation" to { hookSuperAssociationEditText(module, loader) },
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
        try {
            hookToastSurface(module)
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "toast surface hook skipped: $t")
        }
        try {
            hookToastBackgroundView(module, loader)
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "toast background hook skipped: $t")
        }
        try {
            hookCustomizeToolbarPopup(module)
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "customize popup hook skipped: $t")
        }
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
                val idName = entryName(view)
                if (DEBUG && color != null && idName.isNotEmpty()) {
                    // 只有显式打开 DEBUG 才逐 view 打日志：inflate 时这会刷几百行，
                    // 第一次弹键盘的卡顿主要就是它。
                    dlog(module) { "setBackground view=${view.javaClass.name} id=$idName color=#${Integer.toHexString(color)}" }
                }
                if (idName == "native_candidate_bar") watchClipboardToolbar(view, module)
                if (idName in TRANSPARENT_BG_IDS) {
                    dlog(module) { "kept bg transparent over Monet body id=$idName" }
                    chain.proceed(arrayOf<Any>(ColorDrawable(0x00000000)))
                } else if (color == 0xff222222.toInt()) {
                    // keyboard_whole 内部已经有 0x66 底板，子容器再叠一层就是双层 Monet。
                    val replacement = replacementForLegacy222(view)
                    dlog(module) { "setBackground #222222 -> #${Integer.toHexString(replacement)} id=$idName" }
                    if (replacement != 0) clearIfInsideBoardOnAttach(view, module)
                    chain.proceed(arrayOf<Any>(ColorDrawable(replacement)))
                } else {
                    chain.proceed()
                }
            }
        module.log(Log.INFO, TAG, "toolbar background probe installed")
    }

    /**
     * inflate 期间命中的 `#222222` 往往发生在视图还没挂到窗口树上，
     * 这时 `isInsideKeyboardWhole()` 只能返回 false，被当成“独立浮层”铺了 0x66。
     * 一旦它 attach 到 keyboard_whole 内部（工具栏页、翻译页、表情页都是这种），
     * 这层 0x66 就变成第二层 Monet，而之后再也没人清 —— 表现就是“先点表情再点翻译，
     * 双层 Monet 一直留着，只有重启输入法才掉”。
     * 所以这里补一个 attach 回调，挂上去之后确认在底板内部就把它清透明。
     */
    private fun clearIfInsideBoardOnAttach(view: View, module: XposedModule) {
        fun settle(v: View) {
            if (v.background !is ColorDrawable) return
            if (!isOverMonetBoard(v)) return
            v.background = ColorDrawable(0x00000000)
            dlog(module) { "cleared late Monet layer id=${entryName(v)}" }
        }
        if (view.isAttachedToWindow) {
            view.post { settle(view) }
            return
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                // 挂上去的当帧 rootView 可能还没建好底板，补几拍
                for (delay in longArrayOf(0L, 120L, 400L)) {
                    v.postDelayed({ settle(v) }, delay)
                }
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

    private val candidateBarWatched = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    /**
     * 候选/工具栏那一行（native_candidate_bar）必须保持透明。
     *
     * 它原生底色是 `#ff222222`，压在 keyboard_whole 的 0x66 底板上就是「黑灰色 / 双层 Monet」。
     * 应用不是每次都用 setBackground 重设，有时直接改现有 ColorDrawable 的颜色或重新 inflate，
     * 视图级的几个 hook 接不全。所以这里在浮层切换和这一行重新布局时兜底扫一遍，
     * 只要底色不透明就清成透明，让底板那层透上来。
     */
    private fun clearCandidateBarSurface(anchor: View, module: XposedModule) {
        val id = resId(anchor.resources, "native_candidate_bar")
        if (id == 0) return
        val bar = try { anchor.rootView.findViewById<View>(id) } catch (_: Throwable) { null } ?: return
        normalizeCandidateBar(bar)
        if (candidateBarWatched.add(bar)) {
            bar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> normalizeCandidateBar(v) }
        }
    }

    private fun normalizeCandidateBar(bar: View) {
        val bg = bar.background
        val color = when (bg) {
            null -> return
            is ColorDrawable -> bg.color
            is GradientDrawable -> bg.color?.defaultColor ?: return
            else -> return
        }
        if (color == 0) return
        bar.background = ColorDrawable(0x00000000)
    }

    /**
     * 复制文字后弹出的剪贴板提示条本身是透明的（它的 navigation_bar_normal 被我们清成了透明），
     * 而它和 native_candidate_bar 是同一个位置、同高度 —— 不管掉底下的候选/工具栏，
     * 就会看到提示文字和工具栏图标叠在一起。提示可见期间把候选栏 alpha 归零，收起再恢复。
     */
    private val clipTipWatched = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    private fun syncClipboardTip(anchor: View, module: XposedModule) {
        val closeId = resId(anchor.resources, "toolbar_clipboard_close")
        if (closeId == 0) return
        val close = try { anchor.rootView.findViewById<View>(closeId) } catch (_: Throwable) { null } ?: return
        val barId = resId(anchor.resources, "native_candidate_bar")
        if (barId == 0) return
        val bar = try { anchor.rootView.findViewById<View>(barId) } catch (_: Throwable) { null } ?: return

        val showing = close.isShown
        val target = if (showing) 0f else 1f
        if (bar.alpha != target) {
            bar.alpha = target
            dlog(module) { "clipboard tip showing=$showing candidateBarAlpha=$target" }
        }
        if (clipTipWatched.add(close)) {
            // 提示条是靠内部状态显隐的，布局变化时再对一次
            close.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val t = if (close.isShown) 0f else 1f
                if (bar.alpha != t) bar.alpha = t
            }
        }
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
            dlog(module) { "clipboard toolbar showing=$showing candidateBarAlpha=$target clearedTint=$cleared" }
        }
    }

    /**
     * 统一的浮层可见性钩子：剪贴板提示条、手写找字面板都会走 View.setVisibility。
     * 手写找字面板（HandWritingLookupBoardView）和 soft_view 是 keyboard_main_container_area
     * 的兄弟且同尺寸，半透明时下面键盘按键全透上来，所以显示期间要把兄弟清掉。
     */
    private fun hookOverlayVisibility(module: XposedModule, loader: ClassLoader) {
        val lookupCls = loader.loadClass(HookPoints.HANDWRITING_LOOKUP)
        val boardCls = loader.loadClass(HookPoints.HANDWRITING_BOARD)
        val moreCls = loader.loadClass(HookPoints.MORE_CANDIDATE)
        val toolbarContainerCls = try {
            loader.loadClass("com.bytedance.android.input.keyboard.toolbar.container.ImeToolBarContainer")
        } catch (_: Throwable) {
            null
        }
        val method = View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType).apply { isAccessible = true }
        module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val self = chain.thisObject
            val result = chain.proceed()
            val v = self as? View
            if (v != null) {
                if (lookupCls.isInstance(v) || boardCls.isInstance(v)) {
                    val show = v.visibility == View.VISIBLE
                    isolateSiblings(v, show, module, "handwrite", keep = setOf("native_candidate_bar"))
                    if (show) clearHandwritingSurface(v, module)
                } else if (moreCls.isInstance(v)) {
                    val show = v.visibility == View.VISIBLE
                    isolateSiblings(v, show, module, "moreCandidate")
                    if (show) clearMoreCandidateSurface(v, module)
                } else if (v.javaClass.simpleName == "ToolboxKeyboardView") {
                    // 笔画/其他布局下打开工具箱不走 toggle 方法，只会显示这个 view，
                    // 所以隔离必须挂在这里，否则底下的键盘会一直透上来。
                    val show = v.visibility == View.VISIBLE
                    isolateSiblings(v, show, module, "toolbox")
                } else if (v.javaClass.simpleName == "ClipboardCommonPhraseContainer") {
                    val show = v.visibility == View.VISIBLE
                    isolateSiblings(v, show, module, "clipboardPanel")
                    if (show) scheduleCards(v, module)
                } else if (v === clipContainer) {
                    syncClipboardToolbar(module)
                }
                if (entryName(v) == "translate_view") {
                    for (delay in longArrayOf(0L, 120L, 400L, 900L)) {
                        v.postDelayed({ clearCandidateBarSurface(v, module) }, delay)
                    }
                }
                val changedName = entryName(v)
                if (changedName == "toolbar_clipboard_close" ||
                    changedName == "toolbar_clipboard_content_container" ||
                    changedName == "native_candidate_bar" ||
                    v.javaClass.simpleName == "ImeToolBarContainer"
                ) {
                    syncClipboardTip(v, module)
                }
            }
            result
        }
        module.log(Log.INFO, TAG, "overlay visibility hook installed")
    }

    private fun isCandidateProtected(v: View): Boolean {
        if (v.javaClass.simpleName == "CandidateIdleView") return true
        return entryName(v) in CANDIDATE_PROTECTED_IDS
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
            val name = entryName(c)
            // keep 里的（候选字栏）不但不能藏，还要强制还原
            hideOrRestoreAlpha(c, if (name in keep) false else show, tag)
        }
        dlog(module) { "$tag isolate show=$show hidden=${hiddenByTag[tag]?.size ?: 0}" }
    }

    private fun collectKeyComponents(v: View, out: MutableList<View>, depth: Int) {
        if (depth > 8) return
        if (v.width > 0 && v.javaClass.simpleName.endsWith("KeyComponent")) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collectKeyComponents(v.getChildAt(i), out, depth + 1)
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
    /** 键帽用的 Monet 状态选择器：默认 0x66，按下 0x99，圆角 8dp。 */
    private fun monetKeySelector(view: View): Drawable {
        val dark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val raw = monetColor(view, if (dark) "system_accent1_800" else "system_accent1_100")
        val base = if (raw == 0) {
            if (dark) 0xff303030.toInt() else 0xfff0f0f0.toInt()
        } else raw
        val radius = view.resources.displayMetrics.density * 8f
        fun shape(alpha: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(withAlpha(base, alpha))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(0x99))
            addState(intArrayOf(), shape(0x66))
        }
    }

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
            // 右下角删除键是 ImageView + bg_more_candidate_delete 状态选择器
            // （night 默认 #ff393939 / 按下 #ff5d5d5d），换成 Monet 强调色版本
            val delId = resId(root.resources, "more_candidate_delete")
            if (delId != 0) {
                root.findViewById<View>(delId)?.let { del ->
                    del.background = monetKeySelector(del)
                    n++
                }
            }
            dlog(module) { "moreCandidate surface cleared n=$n" }
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "moreCandidate surface failed: $t")
        }
    }

    private fun clearHandwritingSurface(root: View, module: XposedModule) {
        try {
            listOf("lookup_tool_bar_placeholder", "lookup_write_filler", "lookup_btn_key_layout").forEach { name ->
                val id = root.resources.getIdentifier(name, "id", root.context.packageName)
                if (id != 0) root.findViewById<View>(id)?.background = ColorDrawable(0x00000000)
            }
            // 手写板/找字板的底部按键都是 *KeyComponent，统一换成 Monet 强调色。
            // 首次调用时还没测量（width=0），等一帧再收集上色。
            Handler(Looper.getMainLooper()).postDelayed({
                val keys = ArrayList<View>()
                collectKeyComponents(root, keys, 0)
                keys.forEach { tintLookupKey(it, module) }
                dlog(module) { "handwriting keys tinted n=${keys.size}" }
            }, 80)
            dlog(module) { "handwriting surface cleared" }
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "handwriting surface failed: $t")
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
            val n = entryName(p)
            if (n == "keyboard_main_container_area") {
                val bar = findViewByName(p, "native_candidate_bar")
                if (bar != null && bar.alpha != 1f) {
                    bar.alpha = 1f
                    dlog(module) { "candidate bar restored after clipboard dismiss" }
                }
                return
            }
            p = p.parent as? View
        }
    }

    /**
     * 剪贴板 / 常用语面板里每条记录的卡片底色。
     *
     * 这些底色是写死在 shape drawable 里的颜色，inflate 时既不走 Resources.getColor，
     * 也不走 View.setBackgroundResource，所以前面所有颜色钩子都接不到，一直是原生灰：
     *   clipboard_history_list_item_bg  -> bg_clipboard_history_item -> #ff3d3d3d (night)
     *   common_phrase_list_item_bg      -> bg_common_phrase_normal   -> #ff3d3d3d (night)
     *   clipboard_history_word_segmentation_item_text（分词胶囊）-> #0f000000
     * 面板可见 / 列表滚动时按 id 扫一遍换成半透明 Monet 强调色，圆角沿用原 drawable。
     * 选中态是饱和蓝（#334f84ff），饱和度高的背景一律不动，保住选中反馈。
     */
    private val CARD_BG_IDS = setOf(
        "clipboard_history_list_item_bg",
        "common_phrase_list_item_bg",
        "clipboard_history_word_segmentation_item_text",
    )

    /** 卡片 -> 我们换上去的 drawable。应用自己重置底色（选中/取消选中）后能自动再覆盖一次。 */
    private val monetizedCards = java.util.WeakHashMap<View, Drawable>()
    private val cardScrollWatched = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    private fun scheduleCards(root: View, module: XposedModule) {
        val handler = Handler(Looper.getMainLooper())
        // 面板是先 setVisibility 再 bind，列表项会晚一拍才出现
        for (delay in longArrayOf(0L, 80L, 240L, 600L)) {
            handler.postDelayed({
                try {
                    refreshCards(root)
                } catch (t: Throwable) {
                    module.log(Log.WARN, TAG, "clipboard cards refresh failed: $t")
                }
            }, delay)
        }
    }

    private fun refreshCards(root: View) {
        if (root.visibility != View.VISIBLE) return
        if (cardScrollWatched.add(root)) {
            // 滚动复用 item 时会重新 inflate 出灰色卡片，跟着滚动补一遍
            root.viewTreeObserver.addOnScrollChangedListener {
                if (root.visibility == View.VISIBLE) root.post { refreshCards(root) }
            }
        }
        fun walk(v: View, depth: Int) {
            if (depth > 20) return
            if (entryName(v) in CARD_BG_IDS) monetizeCard(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(root, 0)
    }

    private fun monetizeCard(view: View): Boolean {
        val applied = monetizedCards[view]
        if (applied != null && view.background === applied) return false
        val gd = view.background as? GradientDrawable ?: return false
        val original = gd.color?.defaultColor ?: return false
        val hsv = FloatArray(3)
        Color.colorToHSV(original, hsv)
        // 选中态是饱和蓝，别动
        if (hsv[1] > 0.25f) return false

        val dark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val accent = monetColor(view, if (dark) "system_accent1_800" else "system_accent1_100")
        val base = if (accent == 0) {
            if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        } else accent
        // 不透明灰卡给厚一点，本来就半透明的分词胶囊保持更淡
        val alpha = if (Color.alpha(original) >= 0x80) 0x4d else 0x2e
        val radius = if (gd.cornerRadius > 0f) gd.cornerRadius else 10f * view.resources.displayMetrics.density

        val replacement = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(withAlpha(base, alpha))
        }
        view.background = replacement
        monetizedCards[view] = replacement
        return true
    }

    /**
     * 超级联想的辅助指令输入框（SuperAssociationEditText）。
     *
     * 它的背景在 XML 里写死 `bg_super_association_container` → `super_association_container_bg`
     * （night = #ff4a4a4a，完全不透明），inflate 不走 setBackgroundResource 也不走 getColor，
     * 所以在模糊面板上就是一块死灰。这里在构造完成、挂到窗口后换成半透明 Monet 圆角底。
     */
    private fun hookSuperAssociationEditText(module: XposedModule, loader: ClassLoader) {
        val cls = try {
            loader.loadClass("com.bytedance.android.input.keyboard.superassociation.SuperAssociationEditText")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "SuperAssociationEditText missing: $t")
            return
        }
        cls.declaredConstructors.forEach { ctor ->
            try {
                ctor.isAccessible = true
                module.hook(ctor)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        val view = (chain.thisObject as? View) ?: (result as? View)
                        if (view != null) view.post { applySuperAssociationSurface(view) }
                        result
                    }
            } catch (t: Throwable) {
                module.log(Log.WARN, TAG, "SuperAssociationEditText ctor hook skipped: $t")
            }
        }
        module.log(Log.INFO, TAG, "super association edit surface hook installed")
    }

    private fun applySuperAssociationSurface(view: View) {
        try {
            val dark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val raw = monetColor(view, if (dark) "system_accent1_800" else "system_accent1_100")
            val base = if (raw != 0) raw else if (dark) 0xff3a3a3a.toInt() else 0xffffffff.toInt()
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * view.resources.displayMetrics.density
                setColor(withAlpha(base, 0x3d))
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Toast 胶囊（比如“请先输入内容再整理”）用的是 layout_toast_window + bg_shape_toast，
     * 底色 sixty_percent_black（#99000000），在模糊键盘上就是一块不透明灰。
     * Toast 的 View 是 inflate 出来的，不经过 setBackground 那几个 hook，
     * 所以挂在 Toast.setView 上，设置完再把这层中性黑灰换成半透明 Monet 圆角。
     */
    private fun hookToastSurface(module: XposedModule) {
        val setView = android.widget.Toast::class.java.getDeclaredMethod("setView", View::class.java)
        setView.isAccessible = true
        module.hook(setView)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    monetizeToast(chain.getArg(0) as? View)
                } catch (_: Throwable) {
                }
                result
            }
        module.log(Log.INFO, TAG, "toast surface hook installed")
    }

    private fun monetizeToast(root: View?) {
        val anchor = root ?: return
        fun walk(v: View, depth: Int) {
            if (depth > 6) return
            val bg = v.background
            val color = when (bg) {
                is ColorDrawable -> bg.color
                is GradientDrawable -> bg.color?.defaultColor ?: -1
                else -> -1
            }
            if (color != -1) {
                val alpha = color ushr 24
                val hsv = FloatArray(3)
                Color.colorToHSV(color, hsv)
                // 只处理不透明的中性深色/浅色底，保留有彩色（错误红、成功绿）的原样
                if (alpha >= 0x80 && hsv[1] < 0.30f) {
                    val radius = (bg as? GradientDrawable)?.cornerRadius?.takeIf { it > 0f }
                        ?: (10f * v.resources.displayMetrics.density)
                    val dark = v.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                    val raw = monetColor(v, if (dark) "system_accent1_800" else "system_accent1_100")
                    val base = if (raw != 0) raw else if (dark) 0xff303030.toInt() else 0xffffffff.toInt()
                    v.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = radius
                        setColor(withAlpha(base, 0xcc))
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(anchor, 0)
    }

    /**
     * 输入法的 toast 胶囊（“请先输入内容再整理”）不是 android.widget.Toast，
     * 而是 OUI 的 ToastBackgroundView —— 它在 onDraw 里用 Paint 画圆角底，
     * 颜色来自 setBgColor(colorRes, colorRes) 里读到的 #99000000。
     * 背景不挂在 View 上，所以前面的 setBackground 钩子都碰不到，
     * 这里直接改它的 Paint 颜色，换成半透明 Monet。
     */
    private fun hookToastBackgroundView(module: XposedModule, loader: ClassLoader) {
        val cls = try { loader.loadClass("com.obric.oui.toast.ToastBackgroundView") } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "ToastBackgroundView missing: $t")
            return
        }
        val method = cls.declaredMethods.firstOrNull {
            it.name == "setBgColor" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: run {
            module.log(Log.WARN, TAG, "ToastBackgroundView.setBgColor not found")
            return
        }
        val paints = cls.declaredFields.filter { Paint::class.java.isAssignableFrom(it.type) }
            .onEach { it.isAccessible = true }
        method.isAccessible = true
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    val view = chain.thisObject as View
                    val dark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                    val raw = monetColor(view, if (dark) "system_accent1_800" else "system_accent1_100")
                    val base = if (raw != 0) raw else if (dark) 0xff303030.toInt() else 0xffffffff.toInt()
                    val color = withAlpha(base, 0xcc)
                    for (f in paints) (f.get(view) as? Paint)?.color = color
                    view.invalidate()
                } catch (_: Throwable) {
                }
                result
            }
        module.log(Log.INFO, TAG, "toast background view hook installed")
    }

    /**
     * “定制工具栏”是以 PopupWindow 盖在键盘上弹出的。
     *
     * 原生给这个浮窗铺的是不透明 #222222 面板；我们统一换成 0x66 Monet 之后：
     * 1) 下面的键盘键位会透出来，看起来像两层内容重叠；
     * 2) 浮窗 0x66 + 键盘底板 0x66 叠成双层 Monet（颜色明显更深）。
     *
     * 处理：浮窗弹出时把面板本身改透明，并临时隐藏键盘键位层（KeyboardView），
     * 只留键盘那一层 0x66 底板（含模糊）透上来 —— 单层 Monet、没有键位、工具栏行还在。
     * 应用进入定制态会重置 KeyboardView 的 alpha，所以用短周期看门狗持续压住；
     * 浮窗 dismiss 时立刻停止并恢复。
     */
    private fun hookCustomizeToolbarPopup(module: XposedModule) {
        val cls = android.widget.PopupWindow::class.java
        for (nm in arrayOf("showAtLocation", "showAsDropDown")) {
            cls.declaredMethods.filter { it.name == nm }.forEach { m ->
                try {
                    m.isAccessible = true
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val result = chain.proceed()
                            try {
                                val cv = (chain.thisObject as android.widget.PopupWindow).contentView
                                if (cv != null && isCustomizePopup(cv)) {
                                    hideKeyboardForCustomize(cv, module)
                                    cv.postDelayed({ hideKeyboardForCustomize(cv, module) }, 200)
                                }
                            } catch (_: Throwable) {
                            }
                            result
                        }
                } catch (_: Throwable) {
                }
            }
        }
        try {
            val dismiss = cls.getDeclaredMethod("dismiss").apply { isAccessible = true }
            module.hook(dismiss)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try { restoreKeyboardAfterCustomize() } catch (_: Throwable) {}
                    result
                }
        } catch (_: Throwable) {
        }
        module.log(Log.INFO, TAG, "customize toolbar popup hook installed")
    }

    private fun hookToolbarBackgroundColorOverride(module: XposedModule) {
        val method = View::class.java.getDeclaredMethod("setBackgroundColor", Int::class.javaPrimitiveType).apply { isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val view = chain.thisObject as View
                if (isCandidateProtected(view)) return@intercept chain.proceed()
                val originalColor = chain.getArg(0) as Int
                val idName = entryName(view)
                val color = if (idName in TRANSPARENT_BG_IDS && idName != MONET_BG_ID) {
                    0
                } else if (originalColor == 0xff222222.toInt()) {
                    val replacement = replacementForLegacy222(view)
                    dlog(module) { "intercepted #222222 View.setBackgroundColor id=$idName class=${view.javaClass.name}" }
                    if (replacement != 0) clearIfInsideBoardOnAttach(view, module)
                    replacement
                } else originalColor
                val result = if (color == originalColor) chain.proceed() else chain.proceed(arrayOf(color))
                if (idName == MONET_BG_ID || idName in TRANSPARENT_BG_IDS) {
                    if (idName == MONET_BG_ID) {
                        view.background = createMonetColor(view)
                    } else {
                        view.background = ColorDrawable(0x00000000)
                    }
                    if (idName == "native_candidate_bar") {
                        dlog(module) { "kept candidate toolbar transparent over Monet body" }
                    }
                    dlog(module) { "restored Monet after setBackgroundColor id=$idName" }
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
                val idName = entryName(view)
                if (idName == MONET_BG_ID || idName in TRANSPARENT_BG_IDS) {
                    view.background = if (idName == MONET_BG_ID) createMonetColor(view) else ColorDrawable(0x00000000)
                    dlog(module) { "restored Monet after setBackgroundResource id=$idName" }
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
        // 只在视图树刚 attach 的几个时间点兜底。原来 100/300/700/1200 四次，
        // 每次都会重新 getIdentifier + setBackground，首次弹键盘时白白多做一轮。
        intArrayOf(120, 400, 900).forEach { delay ->
            handler.postDelayed({ applyToKeyboardWhole(service, module) }, delay.toLong())
        }
    }

    private fun hookResourceColors(module: XposedModule) {
        hookColor(module, Resources::class.java.getDeclaredMethod("getColor", Int::class.javaPrimitiveType))
        hookColor(module, Resources::class.java.getDeclaredMethod("getColor", Int::class.javaPrimitiveType, Resources.Theme::class.java))
        hookColorStateList(module, Resources::class.java.getDeclaredMethod("getColorStateList", Int::class.javaPrimitiveType))
        hookColorStateList(module, Resources::class.java.getDeclaredMethod("getColorStateList", Int::class.javaPrimitiveType, Resources.Theme::class.java))
        module.log(Log.INFO, TAG, "resource key color hooks installed")
    }

    private fun globalMonetSurface(resources: Resources): Int {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val name = if (dark) "system_accent1_800" else "system_accent1_100"
        val id = resources.getIdentifier(name, "color", "android")
        val base = if (id != 0) resources.getColor(id) else if (dark) 0xff4f5f9b.toInt() else 0xffd7e2ff.toInt()
        return withAlpha(base, 0x66)
    }

    /** “定制工具栏”浮窗：内容里有工具项网格/页码指示器。 */
    private fun isCustomizePopup(cv: View): Boolean {
        return try {
            val res = cv.resources
            listOf("gridContainer", "indicatorLayout", "item_voice_big").any { name ->
                val id = resId(res, name)
                id != 0 && cv.findViewById<View>(id) != null
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 定制工具栏浮窗盖在键盘上。原生给浮窗铺不透明 #222222 面板，
     * 我们换成 0x66 Monet 之后：下面的键盘键位会透出来（重叠），
     * 而且浮窗 0x66 + 键盘底板 0x66 叠成双层 Monet。
     *
     * 处理：浮窗显示期间把键盘键位层（KeyboardView）临时隐藏、浮窗自己的面板改成透明，
     * 只留键盘那一层 0x66 底板（含模糊）透上来 —— 单层 Monet，也看不到键位。
     * 工具栏行（native_candidate_bar）保持可见。
     */
    private fun hideKeyboardForCustomize(popupContent: View, module: XposedModule) {
        try {
            customizePopupVisible = true
            // 浮窗自己别再铺一层 0x66，否则就是第二层 Monet
            if (popupContent.background != null) popupContent.background = ColorDrawable(0x00000000)
            // 应用进入定制态时会重建/重置 KeyboardView 的 alpha，单次设置会被盖回去，
            // 这里用短周期的看门狗持续压住，浮窗关闭时立刻停。
            if (customizeWatchdog == null) {
                val r = object : Runnable {
                    var ticks = 0
                    override fun run() {
                        ticks++
                        applyCustomizeHide()
                        if (customizePopupVisible && ticks < 100) customizeHandler.postDelayed(this, 120L)
                    }
                }
                customizeWatchdog = r
                customizeHandler.post(r)
            }
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "hide keyboard for customize failed: $t")
        }
    }

    private fun applyCustomizeHide() {
        try {
            val kw = keyboardWholeRef?.get() ?: return
            fun walk(node: View) {
                if (node.javaClass.simpleName == "KeyboardView") node.alpha = 0f
                if (node is ViewGroup) for (i in 0 until node.childCount) walk(node.getChildAt(i))
            }
            walk(kw.rootView)
        } catch (_: Throwable) {
        }
    }

    private fun restoreKeyboardAfterCustomize() {
        customizePopupVisible = false
        customizeWatchdog?.let { customizeHandler.removeCallbacks(it) }
        customizeWatchdog = null
        try {
            val kw = keyboardWholeRef?.get() ?: return
            fun walk(node: View) {
                if (node.javaClass.simpleName == "KeyboardView") node.alpha = 1f
                if (node is ViewGroup) for (i in 0 until node.childCount) walk(node.getChildAt(i))
            }
            walk(kw.rootView)
        } catch (_: Throwable) {
        }
    }

    private val customizeHandler = Handler(Looper.getMainLooper())
    private var customizeWatchdog: Runnable? = null
    private var customizePopupVisible = false

    /** setBackground/setBackgroundColor 里 #222222 的统一替换策略。 */
    private fun replacementForLegacy222(view: View): Int {
        return when {
            // 已经在 0x66 底板内部：清透明，避免双层
            isOverMonetBoard(view) -> 0
            // 其它情况（含独立浮窗）：统一 0x66 强调色。
            // “定制工具栏”那种盖在键盘上的浮窗，会由 hookCustomizePopup 临时把键盘隐藏，
            // 只留它自己这一层，从而不出现双层 Monet。
            else -> globalMonetSurface(view.resources)
        }
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
                            val svShader = paint.shader
                            val svFilter = paint.colorFilter
                            val svMask = paint.maskFilter
                            val svColor = paint.color
                            val svBlend = if (Build.VERSION.SDK_INT >= 29) paint.blendMode else null
                            val svXfer = if (Build.VERSION.SDK_INT >= 29) null else paint.xfermode
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
                            val drawn = chain.proceed()
                            // Paint 是调用方复用的，SRC 必须还原，否则会污染后续绘制
                            paint.color = svColor
                            paint.shader = svShader
                            paint.colorFilter = svFilter
                            paint.maskFilter = svMask
                            if (Build.VERSION.SDK_INT >= 29) paint.blendMode = svBlend else paint.xfermode = svXfer
                            return@intercept drawn
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
                            val svShader = paint.shader
                            val svFilter = paint.colorFilter
                            val svMask = paint.maskFilter
                            val svColor = paint.color
                            val svBlend = if (Build.VERSION.SDK_INT >= 29) paint.blendMode else null
                            val svXfer = if (Build.VERSION.SDK_INT >= 29) null else paint.xfermode
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
                            val drawn = chain.proceed()
                            paint.color = svColor
                            paint.shader = svShader
                            paint.colorFilter = svFilter
                            paint.maskFilter = svMask
                            if (Build.VERSION.SDK_INT >= 29) paint.blendMode = svBlend else paint.xfermode = svXfer
                            return@intercept drawn
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

    /**
     * 第一个候选字的背景改成系统强调色。
     *
     * 1.5.6 的选中态方法仍是 b(I)：index==1 时用 ContextCompat.getDrawable
     * (bg_candidate_item_highlighted) 设背景，深色模式下该 drawable 是品牌蓝 #cc4f84ff。
     * 1.5.3 里那三个直接写死 r2/c/g 的 ASR/状态栏 hook 在 1.5.6 已不存在，已删除。
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

    private fun scheduleToolbarRefresh(view: View, module: XposedModule) {
        var parent = view.parent as? View
        while (parent != null && parent.parent != null) {
            val name = parent.javaClass.name
            if (name.contains("RecyclerView") || name.contains("ViewPager2")) break
            parent = parent.parent as? View
        }
        if (parent == null) return
        longArrayOf(0L, 120L, 600L).forEach { delay ->
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

    // 按面板分桶记录：多个浮层共用一张表会互相踩（A 关掉时把 B 刚藏起来的键盘又恢复回来）
    private val hiddenByTag = HashMap<String, HashMap<View, Float>>()

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
                // 底板已经是 0x66 Monet，这里再给 0x66 就是实打实的第二层，
                // 所以只留一层很淡的强调色做分割（0x26），叠起来也不会发深。
                setColor(withAlpha(base, 0x26))
            }
            bar.background = bg
            dlog(module) { "segmented bar surface dark=$dark" }
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
            dlog(module) { "emoji surface cleared" }
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
        if (killed > 0) dlog(module) { "tab bar background source killed n=$killed" }
        for (i in 0 until tabBar.childCount) {
            clearTabItemBackground(tabBar.getChildAt(i))
        }
        // TabView 是 TabLayout 内部类，custom view 常延迟创建，补一次
        tabBar.post {
            var n = 0
            for (i in 0 until tabBar.childCount) n += clearTabItemBackground(tabBar.getChildAt(i))
            if (n > 0) dlog(module) { "tab selector tinted n=$n" }
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
            dlog(module) { "emoji button tinted #$name -> #" + Integer.toHexString(base) }
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
                hideOrRestoreAlpha(child, show, tag)
                count++
            }
            // 顶部工具栏在另一个容器里，单独处理
            val bar = findViewByName(root as? View, "native_candidate_bar")
            if (bar != null && bar !== target) {
                hideOrRestoreAlpha(bar, show, tag)
                count++
            }
            if (show && targetClass == HookPoints.TOOLBOX) applyToolboxSurface(target, module)
            if (show && targetClass == HookPoints.EMOJI_LAYOUT) applyEmojiSurface(target, module)
            // 浮层进出会重新 inflate 候选/工具栏那一行，补清它的不透明底色
            for (delay in longArrayOf(0L, 120L, 400L)) {
                (root as? View)?.postDelayed({ clearCandidateBarSurface(container, module) }, delay)
            }
            module.log(
                Log.INFO, TAG,
                "$tag isolate show=$show container=${container.childCount} touched=$count hidden=${hiddenByTag[tag]?.size ?: 0}"
            )
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "$tag isolate failed: $t")
        }
    }

    /**
     * 隐藏兄弟视图：只动 alpha，绝不动 visibility。
     *
     * 试过设 INVISIBLE，视觉是对的，但把应用的状态机搞坏了 —— 应用认为键盘一直可见，
     * 关掉手写时不会再调 setVisibility(VISIBLE)，我们的 INVISIBLE 就永久残留，
     * 后续触摸事件到不了键盘，表现为"松开语音键还在一直收音"。
     */
    private fun hideOrRestoreAlpha(view: View, show: Boolean, tag: String) {
        val bucket = hiddenByTag.getOrPut(tag) { HashMap() }
        val isKeyboard = view.javaClass.simpleName == "KeyboardView"
        if (show) {
            if (isKeyboard) {
                // 键盘只降 alpha：应用认为它一直可见，动 visibility 会让它的状态机错乱
                // （关掉手写后不再 setVisibility(VISIBLE)，UP 事件就再也到不了键盘）。
                if (view.alpha != 0f) {
                    bucket[view] = view.alpha
                    view.alpha = 0f
                }
            } else if (view.visibility == View.VISIBLE) {
                // 其它兄弟必须真正不可见，否则 alpha=0 仍然叠在上面吃触摸
                // —— 候选字点不动就是这么来的。
                bucket[view] = view.alpha
                view.alpha = 0f
                view.visibility = View.INVISIBLE
            }
        } else {
            bucket.remove(view)?.let {
                view.alpha = it
                if (!isKeyboard) view.visibility = View.VISIBLE
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
        dlog(module) { "applied toolbox item surface dark=$dark" }
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
        dlog(module) { "kept toolbox surface transparent ${root.width}x${root.height} dark=$dark" }
    }

    private fun clearToolbarContainers(view: View) {
        if (view !is ViewGroup) return
        view.background = null
        for (i in 0 until view.childCount) clearToolbarContainers(view.getChildAt(i))
    }

    private fun applyToolbarPageSurface(root: View, module: XposedModule) {
        // 工具栏页本身已经在 keyboard_whole 的 0x66 Monet 底之上。
        // 旧实现又铺了一层 0x99 强调色，叠起来就是用户看到的“双层 Monet”（更深一块）。
        // 这里和 applyToolboxSurface 一样只清背景，让键盘底板透上来。
        clearToolbarContainers(root)
        root.background = ColorDrawable(0x00000000)
        dlog(module) { "kept toolbar page transparent ${root.width}x${root.height}" }
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
        val id = resId(decor.resources, name)
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
            val id = resId(decor.resources, "keyboard_whole")
            val keyboardWhole = decor.findViewById<View>(id) ?: run {
                dlog(module) { "keyboard_whole not attached yet, id=$id" }
                return
            }
            keyboardWholeRef = java.lang.ref.WeakReference(keyboardWhole)
            clearOverlayBackground(decor, "native_candidate_bar")
            clearOverlayBackground(decor, "keyboard_main_container_area")
            clearOverlayBackground(decor, "keyboard_whole_llm")
            clearOverlayBackground(decor, "input_view_image_bg")
            val monet = createMonetColor(decor)
            // keyboardWhole.background = monet  // 测试：禁用 Monet 染色
            val toolbarId = resId(decor.resources, "native_candidate_bar")
            if (toolbarId != 0) decor.findViewById<View>(toolbarId)?.background = ColorDrawable(0x00000000)
            clearCandidateBarSurface(decor, module)
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
                dlog(module) { "keyboard blur: no view root yet" }
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
