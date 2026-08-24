package me.doubaoime.blur;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Blurs a snapshot of the keyboard background while keeping keys readable. */
final class ImeBlurHook {
    private static final String TAG = "DoubaoImeBlur";
    private static final float PORTRAIT_RADIUS = 20f;
    private static final float LANDSCAPE_RADIUS = 20f;
    private static final Map<View, Boolean> INSTALLED = new WeakHashMap<>();

    private ImeBlurHook() {}

    static void install(ClassLoader loader, XposedModule module) throws Exception {
        if (Build.VERSION.SDK_INT < 31) {
            module.log(Log.INFO, TAG, "RenderEffect requires Android 12+");
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
                    if (result instanceof View) {
                        scheduleBackgroundLayer((View) result, module);
                    }
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
                    Object service = chain.getThisObject();
                    new Handler(Looper.getMainLooper()).post(() -> {
                        try {
                            Method getInputView = service.getClass().getMethod("getInputView");
                            Object view = getInputView.invoke(service);
                            if (view instanceof View) {
                                scheduleBackgroundLayer((View) view, module);
                            }
                        } catch (Throwable t) {
                            module.log(Log.WARN, TAG, "input view lookup skipped: " + t);
                        }
                    });
                    return result;
                });
    }

    private static void scheduleBackgroundLayer(View view, XposedModule module) {
        new Handler(Looper.getMainLooper()).post(() -> installBackgroundLayer(view, module));
    }

    private static void installBackgroundLayer(View view, XposedModule module) {
        if (!(view instanceof ViewGroup)) {
            module.log(Log.WARN, TAG, "input view is not a ViewGroup: " + view.getClass().getName());
            return;
        }
        ViewGroup root = (ViewGroup) view;
        if (INSTALLED.put(root, Boolean.TRUE) != null || root.getWidth() <= 0 || root.getHeight() <= 0) {
            return;
        }
        try {
            Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            int[] visibility = new int[root.getChildCount()];
            for (int i = 0; i < root.getChildCount(); i++) {
                View child = root.getChildAt(i);
                visibility[i] = child.getVisibility();
                child.setVisibility(View.INVISIBLE);
            }
            root.draw(new Canvas(bitmap));
            for (int i = 0; i < root.getChildCount(); i++) {
                root.getChildAt(i).setVisibility(visibility[i]);
            }

            ImageView background = new ImageView(root.getContext());
            background.setScaleType(ImageView.ScaleType.FIT_XY);
            background.setImageBitmap(bitmap);
            int radius = Math.round(root.getWidth() > root.getHeight()
                    ? LANDSCAPE_RADIUS : PORTRAIT_RADIUS);
            background.setRenderEffect(RenderEffect.createBlurEffect(
                    radius, radius, Shader.TileMode.CLAMP));
            root.addView(background, 0, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            module.log(Log.INFO, TAG, "installed background-only blur, radius=" + radius);
        } catch (Throwable t) {
            INSTALLED.remove(root);
            module.log(Log.WARN, TAG, "background blur skipped: " + t);
        }
    }
}
