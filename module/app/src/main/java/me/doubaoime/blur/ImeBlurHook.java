package me.doubaoime.blur;

import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.EditorInfo;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Recreates the confirmed BackgroundBlurDrawable branch from the modified APK.
 * The hidden framework class is accessed defensively so pre-Android-12 devices
 * and vendor builds simply keep the original rendering path.
 */
final class ImeBlurHook {
    private static final String TAG = "DoubaoImeBlur";
    private static final float PORTRAIT_RADIUS = 20f;
    private static final float LANDSCAPE_RADIUS = 20f;
    private static final float CORNER_RADIUS = 0f;
    private static final Map<Object, Boolean> INSTALLED = new WeakHashMap<>();

    private ImeBlurHook() {}

    static void install(ClassLoader loader, XposedModule module) throws Exception {
        if (Build.VERSION.SDK_INT < 31) {
            module.log(Log.INFO, TAG, "BackgroundBlurDrawable requires Android 12+");
            return;
        }
        Class<?> imeService = loader.loadClass("com.bytedance.android.doubaoime.ImeService");
        hookStartInputView(module);
        hookLifecycle(module, imeService, "onCreate");
    }

    private static void hookStartInputView(XposedModule module) throws Exception {
        Method target = InputMethodService.class.getDeclaredMethod(
                "onStartInputView", EditorInfo.class, boolean.class);
        target.setAccessible(true);
        module.hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    applyToService(chain.getThisObject(), module);
                    return result;
                });
    }

    private static void hookLifecycle(XposedModule module, Class<?> type, String name) throws Exception {
        Method target = null;
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                target = method;
                break;
            }
        }
        if (target == null) {
            module.log(Log.WARN, TAG, "lifecycle method not found: " + name);
            return;
        }
        target.setAccessible(true);
        module.hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    applyToService(chain.getThisObject(), module);
                    return result;
                });
    }

    private static void applyToService(Object service, XposedModule module) {
        try {
            if (INSTALLED.put(service, Boolean.TRUE) != null) {
                return;
            }
            Window window = findWindow(service);
            if (window == null) {
                INSTALLED.remove(service);
                return;
            }
            View root = window.getDecorView();
            if (root == null) {
                INSTALLED.remove(service);
                return;
            }
            Object blur = createBlurDrawable(root);
            if (blur instanceof Drawable) {
                root.setBackground((Drawable) blur);
                module.log(Log.INFO, TAG, "installed blur on IME decor view");
            }
        } catch (Throwable t) {
            INSTALLED.remove(service);
            module.log(Log.WARN, TAG, "apply blur skipped: " + t);
        }
    }

    private static Window findWindow(Object service) {
        try {
            Method getWindow = service.getClass().getMethod("getWindow");
            Object dialog = getWindow.invoke(service);
            if (dialog instanceof android.app.Dialog) {
                return ((android.app.Dialog) dialog).getWindow();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object createBlurDrawable(View root) throws Exception {
        Class<?> viewRoot = Class.forName("android.view.ViewRootImpl");
        Method getViewRoot = View.class.getDeclaredMethod("getViewRootImpl");
        getViewRoot.setAccessible(true);
        Object viewRootImpl = getViewRoot.invoke(root);
        if (viewRootImpl == null) {
            throw new IllegalStateException("ViewRootImpl unavailable");
        }
        Method create = viewRoot.getDeclaredMethod("createBackgroundBlurDrawable");
        create.setAccessible(true);
        Object drawable = create.invoke(viewRootImpl);
        Class<?> drawableType = drawable.getClass();
        Method setBlur = drawableType.getDeclaredMethod("setBlurRadius", int.class);
        Method setCorner = drawableType.getDeclaredMethod("setCornerRadius", float.class);
        setBlur.setAccessible(true);
        setCorner.setAccessible(true);
        boolean landscape = root.getWidth() > root.getHeight();
        setBlur.invoke(drawable, Math.round(landscape ? LANDSCAPE_RADIUS : PORTRAIT_RADIUS));
        setCorner.invoke(drawable, CORNER_RADIUS);
        return drawable;
    }
}
