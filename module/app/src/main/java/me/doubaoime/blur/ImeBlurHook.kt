package me.doubaoime.blur

import android.app.Dialog
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
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
import android.view.WindowManager
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
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "KeyboardView key hook skipped: $t")
        }
        hookCreateInputView(module, imeService)
        hookStartInputView(module, imeService)
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
                itemView?.postDelayed({ applyToolbarPageSurface(itemView, module) }, 40)
                result
            }
        val bind = adapter.getDeclaredMethod("onBindViewHolder", holder, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        module.hook(bind)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val itemView = holder.getDeclaredField("itemView").apply { isAccessible = true }.get(chain.getArg(0)) as? View
                itemView?.postDelayed({ applyToolbarPageSurface(itemView, module) }, 40)
                result
            }
        module.log(Log.INFO, TAG, "ToolbarPagerAdapter bind blur hook installed")
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
        return (base and 0x00ffffff) or (0xb8 shl 24)
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
