package me.doubaoime.blur;

import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Uses the exact keyboard_whole target identified in the modified APK. */
final class ImeBlurHook {
    private static final String TAG = "DoubaoImeBlur";
    private static final int BLUR_RADIUS = 20;
    private static final float CORNER_RADIUS = 0f;

    private ImeBlurHook() {}

    static void install(ClassLoader loader, XposedModule module) throws Exception {
        if (Build.VERSION.SDK_INT < 31) {
            module.log(Log.INFO, TAG, "BackgroundBlurDrawable requires Android 12+");
            return;
        }
        Class<?> imeService = loader.loadClass("com.bytedance.android.doubaoime.ImeService");
        hookCreateInputView(module, imeService);
        hookStartInputView(module, imeService);
    }

    private static void hookCreateInputView(XposedModule module, Class<?> imeService) throws Exception {
        Method target = imeService.getDeclaredMethod("onCreateInputView");
        target.setAccessible(true);
        module.hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    schedule(chain.getThisObject(), module);
                    return result;
                });
    }

    private static void hookStartInputView(XposedModule module, Class<?> imeService) throws Exception {
        Method target = imeService.getDeclaredMethod("onStartInputView", EditorInfo.class, boolean.class);
        target.setAccessible(true);
        module.hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    schedule(chain.getThisObject(), module);
                    return result;
                });
    }

    private static void schedule(Object service, XposedModule module) {
        Handler handler = new Handler(Looper.getMainLooper());
        for (int delay : new int[]{100, 300, 700, 1200}) {
            handler.postDelayed(() -> applyToKeyboardWhole(service, module), delay);
        }
    }

    private static void clearOverlayBackground(View decor, String name) {
        int id = decor.getResources().getIdentifier(name, "id", "com.bytedance.android.doubaoime");
        View view = id == 0 ? null : decor.findViewById(id);
        if (view != null) {
            view.setBackground(new ColorDrawable(0x00000000));
        }
    }

    private static void applyToKeyboardWhole(Object service, XposedModule module) {
        try {
            Method getWindow = service.getClass().getMethod("getWindow");
            Object dialog = getWindow.invoke(service);
            if (!(dialog instanceof Dialog)) return;
            Window window = ((Dialog) dialog).getWindow();
            if (window == null) return;
            View decor = window.getDecorView();
            int id = decor.getResources().getIdentifier(
                    "keyboard_whole", "id", "com.bytedance.android.doubaoime");
            View keyboardWhole = decor.findViewById(id);
            if (keyboardWhole == null) {
                module.log(Log.WARN, TAG, "keyboard_whole not attached yet, id=" + id);
                return;
            }

            Method getViewRootImpl = View.class.getDeclaredMethod("getViewRootImpl");
            getViewRootImpl.setAccessible(true);
            Object viewRootImpl = getViewRootImpl.invoke(keyboardWhole);
            if (viewRootImpl == null) return;
            Class<?> viewRootClass = Class.forName("android.view.ViewRootImpl");
            Method create = viewRootClass.getDeclaredMethod("createBackgroundBlurDrawable");
            create.setAccessible(true);
            Object blur = create.invoke(viewRootImpl);
            if (!(blur instanceof Drawable)) return;
            Class<?> blurClass = blur.getClass();
            Method setRadius = blurClass.getDeclaredMethod("setBlurRadius", int.class);
            Method setCorner = blurClass.getDeclaredMethod("setCornerRadius", float.class);
            setRadius.setAccessible(true);
            setCorner.setAccessible(true);
            setRadius.invoke(blur, BLUR_RADIUS);
            setCorner.invoke(blur, CORNER_RADIUS);
            clearOverlayBackground(decor, "native_candidate_bar");
            clearOverlayBackground(decor, "keyboard_main_container_area");
            clearOverlayBackground(decor, "keyboard_whole_llm");
            clearOverlayBackground(decor, "input_view_image_bg");
            keyboardWhole.setBackground((Drawable) blur);
            module.log(Log.INFO, TAG, "installed exact keyboard_whole blur bounds="
                    + keyboardWhole.getLeft() + "," + keyboardWhole.getTop() + ","
                    + keyboardWhole.getRight() + "," + keyboardWhole.getBottom());
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "keyboard_whole blur skipped: " + t);
        }
    }
}
