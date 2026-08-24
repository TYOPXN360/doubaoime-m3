package me.doubaoime.blur

import android.app.Dialog
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Typeface
import android.graphics.Shader
import java.util.WeakHashMap
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.view.inputmethod.EditorInfo
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

internal object ImeBlurHook {
    private const val TAG = "DoubaoImeBlur"
    private const val BLUR_RADIUS = 20
    private const val CORNER_RADIUS = 0f

    fun install(loader: ClassLoader, module: XposedModule) {
        if (Build.VERSION.SDK_INT < 31) {
            module.log(Log.INFO, TAG, "BackgroundBlurDrawable requires Android 12+")
            return
        }
        val imeService = loader.loadClass("com.bytedance.android.doubaoime.ImeService")
        hookResourceColors(module)
        try {
            hookKeyboardViewFillRect(module, loader)
            hookKeyboardViewShadow(module, loader)
            hookKeyboardViewTouchBubble(module, loader)
            hookToolbarPageBlur(module, loader)
            hookToolboxKeyboardView(module, loader)
            hookToolboxItemView(module, loader)
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "KeyboardView key hook skipped: $t")
        }
        hookCreateInputView(module, imeService)
        hookStartInputView(module, imeService)
        hookInputViewRootToolboxProbe(module, loader)
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
                                dumpToolboxTree(toolbox, module, 0)
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

    private fun dumpToolboxTree(view: View, module: XposedModule, depth: Int) {
        if (depth > 3) return
        val background = view.background
        module.log(Log.INFO, TAG, "toolbox[$depth] ${view.javaClass.name} id=${view.id} visible=${view.visibility} size=${view.width}x${view.height} bg=${background?.javaClass?.name} color=${(background as? ColorDrawable)?.color}")
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) dumpToolboxTree(view.getChildAt(i), module, depth + 1)
        }
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
        module.log(Log.INFO, TAG, "resource key color hooks installed")
    }

    private fun hookKeyboardViewFillRect(module: XposedModule, loader: ClassLoader) {
        val keyboardView = loader.loadClass("com.bytedance.android.input.keyboard.KeyboardView")
        val method = keyboardView.getDeclaredMethod(
            "fillRect", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val args = chain.args.toTypedArray()
                val left = args[0] as Int
                val top = args[1] as Int
                val right = args[2] as Int
                val bottom = args[3] as Int
                val width = right - left
                val height = bottom - top
                if (width in 40..300 && height in 30..180) {
                    args[4] = translucentGboardKeyColor(args[4] as Int, (chain.thisObject as View).resources)
                    chain.proceed(args)
                } else {
                    chain.proceed()
                }
            }
        module.log(Log.INFO, TAG, "KeyboardView.fillRect key hook installed")
    }

    private fun hookKeyboardViewShadow(module: XposedModule, loader: ClassLoader) {
        val keyboardView = loader.loadClass("com.bytedance.android.input.keyboard.KeyboardView")
        val method = keyboardView.getDeclaredMethod(
            "drawShadow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Float::class.javaPrimitiveType
        ).apply { isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val args = chain.args.toTypedArray()
                val original = args[8] as Int
                args[8] = (original and 0x00ffffff) or (0x20 shl 24)
                chain.proceed(args)
            }
        module.log(Log.INFO, TAG, "KeyboardView.drawShadow softened")
    }

    private fun blurBubbleBackdrop(view: View, args: Array<Any?>, module: XposedModule) {
        try {
            val bitmap = view.javaClass.getDeclaredField("mBitmapBuffer").apply { isAccessible = true }.get(view) as? Bitmap ?: return
            val canvas = view.javaClass.getDeclaredField("mCurrentCanvas").apply { isAccessible = true }.get(view) as? Canvas ?: return
            val values = args.take(10).map { it as Float }
            val shapeUtils = view.javaClass.classLoader?.loadClass("com.bytedance.android.input.keyboard.utils.ShapeUtils")
            val shapeMethod = shapeUtils?.getDeclaredMethod("g", *Array(10) { Float::class.javaPrimitiveType!! })
            val path = (shapeMethod?.invoke(null, *values.toTypedArray()) as? Path)
                ?: Path().apply { addRect(RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), Path.Direction.CW) }
            val bounds = RectF()
            path.computeBounds(bounds, true)
            val left = (bounds.left.toInt() - 8).coerceIn(0, bitmap.width - 1)
            val top = (bounds.top.toInt() - 8).coerceIn(0, bitmap.height - 1)
            val right = (bounds.right.toInt() + 8).coerceIn(left + 1, bitmap.width)
            val bottom = (bounds.bottom.toInt() + 8).coerceIn(top + 1, bitmap.height)
            val source = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            val systemBlur = blurBitmapSystem(source, 18f, module)
            if (!systemBlur) blurBitmap(source, 5, 3)
            module.log(Log.INFO, TAG, "bubble blur path=${if (systemBlur) "RenderEffect" else "CPU fallback"}")
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(source, left.toFloat(), top.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG))
            canvas.restore()
            source.recycle()
            module.log(Log.INFO, TAG, "blurred long-press backdrop ${right - left}x${bottom - top}")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "long-press backdrop blur skipped: $t")
        }
    }

    private fun blurBitmapSystem(bitmap: Bitmap, radius: Float, module: XposedModule): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return try {
            val node = RenderNode("doubao-bubble-blur")
            node.setPosition(0, 0, bitmap.width, bitmap.height)
            node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
            val recording = node.beginRecording(bitmap.width, bitmap.height)
            recording.drawBitmap(bitmap, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
            node.endRecording()
            val method = Class.forName("android.graphics.HardwareRenderer").getDeclaredMethod(
                "createHardwareBitmap", RenderNode::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
            val blurred = method.invoke(null, node, bitmap.width, bitmap.height) as? Bitmap ?: return false
            Canvas(bitmap).drawBitmap(blurred, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
            blurred.recycle()
            true
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "RenderEffect bubble blur unavailable: ${t.javaClass.name}: ${t.message}")
            false
        }
    }

    private fun blurBitmap(bitmap: Bitmap, radius: Int, passes: Int) {
        val width = bitmap.width
        val height = bitmap.height
        var input = IntArray(width * height)
        var horizontal = IntArray(input.size)
        var output = IntArray(input.size)
        bitmap.getPixels(input, 0, width, 0, 0, width, height)
        repeat(passes) {
            for (y in 0 until height) for (x in 0 until width) {
                var ar = 0; var ag = 0; var ab = 0; var aa = 0; var count = 0
                for (dx in -radius..radius) {
                    val color = input[y * width + (x + dx).coerceIn(0, width - 1)]
                    aa += Color.alpha(color); ar += Color.red(color); ag += Color.green(color); ab += Color.blue(color); count++
                }
                horizontal[y * width + x] = Color.argb(aa / count, ar / count, ag / count, ab / count)
            }
            for (y in 0 until height) for (x in 0 until width) {
                var ar = 0; var ag = 0; var ab = 0; var aa = 0; var count = 0
                for (dy in -radius..radius) {
                    val color = horizontal[(y + dy).coerceIn(0, height - 1) * width + x]
                    aa += Color.alpha(color); ar += Color.red(color); ag += Color.green(color); ab += Color.blue(color); count++
                }
                output[y * width + x] = Color.argb(aa / count, ar / count, ag / count, ab / count)
            }
            val swap = input
            input = output
            output = swap
        }
        bitmap.setPixels(input, 0, width, 0, 0, width, height)
    }

    private data class BubbleState(
        var overlay: BubbleOverlayView? = null,
        var popup: PopupWindow? = null,
        var bounds: RectF = RectF(),
        var text: String = "",
        var color: Int = Color.WHITE
    )

    private val bubbleStates = WeakHashMap<View, BubbleState>()

    private fun hookKeyboardViewBubbleText(module: XposedModule, loader: ClassLoader) {
        val keyboardView = loader.loadClass("com.bytedance.android.input.keyboard.KeyboardView")
        val method = keyboardView.getDeclaredMethod(
            "drawText", String::class.java, Typeface::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Rect::class.java, Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val view = chain.thisObject as View
                val state = bubbleStates[view]
                val rect = chain.getArg(8) as? Rect
                val textSize = chain.getArg(2) as? Int ?: 0
                if (state != null && textSize >= 48 && rect != null && !state.bounds.isEmpty && RectF.intersects(state.bounds, RectF(rect))) {
                    state.text = chain.getArg(0) as String
                    state.overlay?.invalidate()
                    Unit
                } else {
                    chain.proceed()
                }
            }
        module.log(Log.INFO, TAG, "KeyboardView independent bubble text hook installed")
    }

    private fun showIndependentBubble(view: View, args: Array<Any?>, color: Int, module: XposedModule) {
        val values = args.take(10).map { it as Float }
        val shapeUtils = view.javaClass.classLoader?.loadClass("com.bytedance.android.input.keyboard.utils.ShapeUtils")
        val method = shapeUtils?.getDeclaredMethod("g", *Array(10) { Float::class.javaPrimitiveType!! })
        val path = method?.invoke(null, *values.toTypedArray()) as? Path ?: return
        val bounds = RectF().also { path.computeBounds(it, true) }
        val state = bubbleStates.getOrPut(view) { BubbleState() }
        state.bounds = bounds
        state.color = color
        val overlay = state.overlay ?: BubbleOverlayView(view.context, view, state, module).also { state.overlay = it }
        overlay.update(path, bounds, color)
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = location[0] + bounds.left.toInt()
        val y = location[1] + bounds.top.toInt()
        val width = bounds.width().toInt().coerceAtLeast(1)
        val height = bounds.height().toInt().coerceAtLeast(1)
        val popup = state.popup ?: PopupWindow(overlay, width, height, false).apply {
            isClippingEnabled = false
            isTouchable = false
            elevation = 0f
            state.popup = this
            showAtLocation(view, Gravity.TOP or Gravity.START, x, y)
        }
        if (popup.isShowing) popup.update(x, y, width, height)
        overlay.post { overlay.refreshBlur() }
        overlay.postDelayed({
            if (bubbleStates[view] === state && state.popup === popup) {
                popup.dismiss()
                state.popup = null
                state.overlay = null
            }
        }, 180)
    }

    private class BubbleOverlayView(
        context: android.content.Context,
        private val source: View,
        private val state: BubbleState,
        private val module: XposedModule
    ) : View(context) {
        private var path = Path()
        private var offsetX = 0f
        private var offsetY = 0f
        private var bubbleColor = Color.WHITE

        fun update(newPath: Path, bounds: RectF, color: Int) {
            path = Path(newPath)
            offsetX = bounds.left
            offsetY = bounds.top
            bubbleColor = color
            val params = layoutParams as? FrameLayout.LayoutParams
            if (params != null) {
                params.width = bounds.width().toInt().coerceAtLeast(1)
                params.height = bounds.height().toInt().coerceAtLeast(1)
                params.leftMargin = bounds.left.toInt()
                params.topMargin = bounds.top.toInt()
                layoutParams = params
            }
            refreshBlur()
            invalidate()
        }

        fun refreshBlur() {
            try {
                val getRoot = View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }
                val root = getRoot.invoke(rootView) ?: return
                val cls = Class.forName("android.view.ViewRootImpl")
                val drawable = cls.getDeclaredMethod("createBackgroundBlurDrawable").apply { isAccessible = true }.invoke(root) as Drawable
                drawable.javaClass.getDeclaredMethod("setBlurRadius", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(drawable, 24)
                drawable.javaClass.getDeclaredMethod("setCornerRadius", Float::class.javaPrimitiveType).apply { isAccessible = true }.invoke(drawable, 18f)
                val fill = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 18f; setColor(bubbleColor) }
                background = LayerDrawable(arrayOf(drawable, fill))
            } catch (t: Throwable) {
                module.log(Log.WARN, TAG, "independent bubble blur unavailable: $t")
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val local = Path(path).apply { offset(-offsetX, -offsetY) }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = height * 0.42f }
            val text = state.text
            if (text.isNotEmpty()) canvas.drawText(text, width / 2f, height * 0.62f, paint)
        }
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
                blurBubbleBackdrop(chain.thisObject as View, args, module)
                args[10] = monetPopupColor(args[10] as Int, (chain.thisObject as View).resources)
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

    private fun hookToolbarPageBlur(module: XposedModule, loader: ClassLoader) {
        val adapter = loader.loadClass("com.bytedance.android.input.keyboard.CustomToolbar.ToolbarPagerAdapter")
        val holder = loader.loadClass("androidx.recyclerview.widget.RecyclerView\$ViewHolder")
        val method = adapter.getDeclaredMethod("onCreateViewHolder", ViewGroup::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val itemView = holder.getDeclaredField("itemView").apply { isAccessible = true }.get(result) as? View
                if (itemView != null) {
                    applyToolbarPageSurface(itemView, module)
                }
                result
            }
        val bind = adapter.getDeclaredMethod("onBindViewHolder", holder, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        module.hook(bind)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val itemView = holder.getDeclaredField("itemView").apply { isAccessible = true }.get(chain.getArg(0)) as? View
                if (itemView != null) {
                    applyToolbarPageSurface(itemView, module)
                }
                result
            }
        module.log(Log.INFO, TAG, "ToolbarPagerAdapter bind blur hook installed")
    }

    private fun hookToolboxKeyboardView(module: XposedModule, loader: ClassLoader) {
        val toolbox = loader.loadClass("com.bytedance.common_biz.tool_box.ToolboxKeyboardView")
        val method = toolbox.getDeclaredMethod("q").apply { isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                applyToolboxSurface(chain.thisObject as View, module)
                result
            }
        val attached = toolbox.getDeclaredMethod("onAttachedToWindow").apply { isAccessible = true }
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
        val item = loader.loadClass("com.bytedance.common_biz.tool_box.ToolboxItemView")
        val method = item.getDeclaredMethod("d").apply { isAccessible = true }
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
        return (base and 0x00ffffff) or (0x88 shl 24)
    }

    private fun translucentGboardKeyColor(original: Int, resources: Resources): Int {
        if (Color.alpha(original) < 0x80) return original
        val r = Color.red(original)
        val g = Color.green(original)
        val b = Color.blue(original)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val average = (r + g + b) / 3
        val role = when {
            b > r + 20 && b > g + 10 -> if (dark) "system_accent1_700" else "system_accent1_600"
            average < 225 -> if (dark) "system_neutral2_700" else "system_neutral2_200"
            else -> "neutral_key"
        }
        if (role == "neutral_key") {
            val base = if (dark) 0xff444746.toInt() else 0xffe2e2e2.toInt()
            return (base and 0x00ffffff) or (0x99 shl 24)
        }
        val id = Resources.getSystem().getIdentifier(role, "color", "android")
        val base = if (id == 0) original else Resources.getSystem().getColor(id)
        return (base and 0x00ffffff) or (0x99 shl 24)
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

    private fun resolveKeyColor(resources: Resources, id: Int): Int? {
        val name = try { resources.getResourceEntryName(id) } catch (_: Throwable) { return null }
        val role = when (name) {
            "ime_key_normal_bg_color" -> Role.KEY
            "ime_key_normal_press_bg_color" -> Role.KEY_PRESSED
            "ime_key_gray_clickable_bg_color", "ime_key_gray_un_clickable_bg_color" -> Role.FUNCTION
            "ime_key_gray_clickable_press_bg_color", "ime_key_gray_un_clickable_press_bg_color" -> Role.FUNCTION_PRESSED
            "ime_key_blue_clickable_bg_color" -> Role.ACCENT
            "ime_key_blue_clickable_press_bg_color" -> Role.ACCENT_PRESSED
            "ime_key_text_clickable_color" -> Role.FOREGROUND
            "ime_key_text_un_clickable_color", "ime_key_hint_text_color", "ime_space_button_text_color" -> Role.FOREGROUND_MUTED
            "ime_key_shadow_color" -> Role.SHADOW
            else -> return null
        }
        return role.color(resources)
    }

    private enum class Role {
        KEY, KEY_PRESSED, FUNCTION, FUNCTION_PRESSED, ACCENT, ACCENT_PRESSED,
        FOREGROUND, FOREGROUND_MUTED, SHADOW;

        fun color(resources: Resources): Int {
            val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val name = when (this) {
                KEY -> if (dark) "system_neutral1_800" else "system_neutral1_100"
                KEY_PRESSED -> if (dark) "system_accent1_700" else "system_accent1_200"
                FUNCTION -> if (dark) "system_neutral2_800" else "system_neutral2_100"
                FUNCTION_PRESSED -> if (dark) "system_accent2_700" else "system_accent2_200"
                ACCENT -> if (dark) "system_accent1_700" else "system_accent1_600"
                ACCENT_PRESSED -> if (dark) "system_accent1_600" else "system_accent1_700"
                FOREGROUND -> if (dark) "system_neutral1_10" else "system_neutral1_900"
                FOREGROUND_MUTED -> if (dark) "system_neutral2_100" else "system_neutral2_700"
                SHADOW -> if (dark) "system_neutral1_900" else "system_neutral1_100"
            }
            val id = resources.getIdentifier(name, "color", "android")
            return if (id == 0) 0 else resources.getColor(id)
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

    private fun monetColor(decor: View, name: String): Int {
        val id = decor.resources.getIdentifier(name, "color", "android")
        if (id == 0) return 0
        return try { decor.context.getColor(id) } catch (_: Throwable) { 0 }
    }

    private fun applyToKeyboardWhole(service: Any, module: XposedModule) {
        try {
            val getWindow = service.javaClass.getMethod("getWindow")
            val dialog = getWindow.invoke(service) as? Dialog ?: return
            val window: Window = dialog.window ?: return
            val decor = window.decorView
            val id = decor.resources.getIdentifier("keyboard_whole", "id", "com.bytedance.android.doubaoime")
            val keyboardWhole = decor.findViewById<View>(id) ?: run {
                module.log(Log.WARN, TAG, "keyboard_whole not attached yet, id=$id")
                return
            }
            val getViewRootImpl = View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }
            val viewRootImpl = getViewRootImpl.invoke(keyboardWhole) ?: return
            val viewRootClass = Class.forName("android.view.ViewRootImpl")
            val create = viewRootClass.getDeclaredMethod("createBackgroundBlurDrawable").apply { isAccessible = true }
            val blur = create.invoke(viewRootImpl) as? Drawable ?: return
            val blurClass = blur.javaClass
            blurClass.getDeclaredMethod("setBlurRadius", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(blur, BLUR_RADIUS)
            blurClass.getDeclaredMethod("setCornerRadius", Float::class.javaPrimitiveType).apply { isAccessible = true }.invoke(blur, CORNER_RADIUS)
            clearOverlayBackground(decor, "native_candidate_bar")
            clearOverlayBackground(decor, "keyboard_main_container_area")
            clearOverlayBackground(decor, "keyboard_whole_llm")
            clearOverlayBackground(decor, "input_view_image_bg")
            val monet = createMonetColor(decor)
            keyboardWhole.background = if (monet == null) blur else LayerDrawable(arrayOf(blur, monet))
            module.log(Log.INFO, TAG, "installed exact keyboard_whole blur bounds=" +
                "${keyboardWhole.left},${keyboardWhole.top},${keyboardWhole.right},${keyboardWhole.bottom}")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "keyboard_whole blur skipped: $t")
        }
    }
}
