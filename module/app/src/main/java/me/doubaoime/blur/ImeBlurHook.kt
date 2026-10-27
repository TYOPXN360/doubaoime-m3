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
        try {
            hookKeyboardViewFillRect(module, loader)
            hookKeyboardViewShadow(module, loader)
            hookKeyboardViewTouchBubble(module, loader)
            hookInputViewAsrStatus(module, loader)
            hookAnimatedStatusBackground(module, loader)
            hookToolboxKeyboardView(module, loader)
            hookToolboxItemView(module, loader)
            hookCandidateItemSelected(module, loader)
        hookOptionItemToolbarRefresh(module, loader)
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "KeyboardView key hook skipped: $t")
        }
        hookCreateInputView(module, imeService)
        hookStartInputView(module, imeService)
        hookToolbarBackgroundProbe(module)
        hookToolbarBackgroundColorOverride(module)
        hookToolbarBackgroundResourceOverride(module)
        hookInputViewRootToolboxProbe(module, loader)
    }

    private fun hookToolbarBackgroundProbe(module: XposedModule) {
        val method = View::class.java.getDeclaredMethod("setBackground", Drawable::class.java).apply { isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val view = chain.thisObject as View
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
                if (color == 0xff222222.toInt()) {
                    val replacement = globalMonetSurface(view.resources)
                    module.log(Log.INFO, TAG, "setBackground #222222 -> #${Integer.toHexString(replacement)} view=$cls id=$idName")
                    chain.proceed(arrayOf<Any>(ColorDrawable(replacement)))
                } else {
                    chain.proceed()
                }
            }
        module.log(Log.INFO, TAG, "toolbar background probe installed")
    }

    private fun hookToolbarBackgroundColorOverride(module: XposedModule) {
        val method = View::class.java.getDeclaredMethod("setBackgroundColor", Int::class.javaPrimitiveType).apply { isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val view = chain.thisObject as View
                val originalColor = chain.getArg(0) as Int
                val color = if (originalColor == 0xff222222.toInt()) {
                    val idName = try { view.resources.getResourceEntryName(view.id) } catch (_: Throwable) { "" }
                    val replacement = globalMonetSurface(view.resources)
                    module.log(Log.INFO, TAG, "intercepted #222222 View.setBackgroundColor id=$idName class=${view.javaClass.name}")
                    replacement
                } else originalColor
                val result = if (color == originalColor) chain.proceed() else chain.proceed(arrayOf(color))
                val idName = try { view.resources.getResourceEntryName(view.id) } catch (_: Throwable) { "" }
                if (idName == "keyboard_whole" || idName == "native_candidate_bar" || idName == "keyboard_main_container_area" || idName == "keyboard_whole_board_area" || idName == "idle_tip_content" || idName == "idle_tip_status" || idName == "status_content" || idName == "status_message") {
                    if (idName == "keyboard_whole") {
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
                if (idName == "keyboard_whole" || idName == "native_candidate_bar" || idName == "keyboard_main_container_area" || idName == "keyboard_whole_board_area" || idName == "idle_tip_content" || idName == "idle_tip_status" || idName == "status_content" || idName == "status_message") {
                    view.background = if (idName == "keyboard_whole") createMonetColor(view) else ColorDrawable(0x00000000)
                    module.log(Log.INFO, TAG, "restored Monet after setBackgroundResource id=$idName")
                }
                result
            }
        module.log(Log.INFO, TAG, "toolbar background resource override installed")
    }

    private fun hookInputViewRootToolboxProbe(module: XposedModule, loader: ClassLoader) {
        val rootClass = loader.loadClass("com.bytedance.android.input.keyboard.areacontrol.InputViewRoot")
        val method = rootClass.getDeclaredMethod("m2", Boolean::class.javaPrimitiveType, String::class.java).apply { isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                if (chain.getArg(0) == true) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            val field = rootClass.getDeclaredField("X0").apply { isAccessible = true }
                            val toolbox = field.get(chain.thisObject) as? View
                            if (toolbox != null) {
                                applyToolboxSurface(toolbox, module)
                            } else {
                                module.log(Log.WARN, TAG, "toolbox probe: X0 is null")
                            }
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
        val keyboardView = loader.loadClass("com.bytedance.android.input.keyboard.KeyboardView")
        val bubble = keyboardView.getDeclaredMethod(
            "drawTouchTipBubble",
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        module.hook(bubble)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val args = chain.args.toTypedArray()
                val monet = monetPopupColor(args[10] as Int, (chain.thisObject as View).resources)
                args[10] = (monet and 0x00ffffff) or (0x88 shl 24)
                chain.proceed(args)
            }
            val shadow = keyboardView.getDeclaredMethod(
                "drawTouchTipBubbleShadow",
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Float::class.javaPrimitiveType
        ).apply { isAccessible = true }
        module.hook(shadow)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val args = chain.args.toTypedArray()
                args[10] = (args[10] as Int and 0x00ffffff) or (0x28 shl 24)
                chain.proceed(args)
            }
        module.log(Log.INFO, TAG, "KeyboardView long-press bubble Monet hook installed")
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

    private fun hookCandidateItemSelected(module: XposedModule, loader: ClassLoader) {
        val itemClass = loader.loadClass("com.bytedance.common_biz.tool_bar.view.widgets.CandidateItemView")
        val method = itemClass.getDeclaredMethod("o", Int::class.javaPrimitiveType).apply { isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                try {
                    val view = chain.thisObject as View
                    val index = chain.getArg(0) as Int
                    if (index == 1) {
                        // 第一个选中词：背景改为 Monet 亮色高亮（accent1_200 亮蓝 #8FCFF3 风格），带圆角 + 半透明模糊
                        MonetEngine.ensureInitialized(view.context)
                        val color = MonetEngine.accentHighlightColor
                        val bg = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = 6f * view.resources.displayMetrics.density
                            setColor(color)
                        }
                        view.background = bg
                        module.log(Log.INFO, TAG, "candidate #1 highlight Monet color=#${Integer.toHexString(color)}")
                    }
                } catch (t: Throwable) {
                    module.log(Log.WARN, TAG, "candidate accent hook failed: $t")
                }
                result
            }
        module.log(Log.INFO, TAG, "candidate item accent hook installed")
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
        val option = loader.loadClass("com.bytedance.android.input.OptionItemView")
        val method = option.getDeclaredMethod("setAdded", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                scheduleToolbarRefresh(chain.thisObject as View, module)
                result
            }
        val icon = option.getDeclaredMethod("setIcon", Int::class.javaPrimitiveType).apply { isAccessible = true }
        module.hook(icon)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                scheduleToolbarRefresh(chain.thisObject as View, module)
                result
            }
        val label = option.getDeclaredMethod("setLabel", String::class.java).apply { isAccessible = true }
        module.hook(label)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                scheduleToolbarRefresh(chain.thisObject as View, module)
                result
            }
        module.log(Log.INFO, TAG, "OptionItemView icon/label refresh hooks installed")
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
        val attached = View::class.java.getDeclaredMethod("onAttachedToWindow")
        module.hook(attached)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                applyToolboxSurface(chain.thisObject as View, module)
                result
            }
        module.log(Log.INFO, TAG, "ToolboxKeyboardView attach blur hook installed")
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
        val surface = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
        }
        root.background = surface
        if (root is ViewGroup && root.childCount > 0) root.getChildAt(0).background = null
        val packageName = root.context.packageName
        listOf("toolbox_header_container", "toolbox_content_container").forEach { name ->
            val id = root.resources.getIdentifier(name, "id", packageName)
            if (id != 0) root.findViewById<View>(id)?.background = null
        }
        module.log(Log.INFO, TAG, "applied toolbox Monet surface ${root.width}x${root.height} dark=$dark")
        applyBlurToPopupRoot(root, module)
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
            keyboardWhole.background = monet
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
