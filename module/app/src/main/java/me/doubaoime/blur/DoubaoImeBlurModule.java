package me.doubaoime.blur;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/** API 102 entry point for the original Doubao IME process. */
public final class DoubaoImeBlurModule extends XposedModule {
    private static final String TAG = "DoubaoImeBlur";
    private static final String TARGET_PACKAGE = "com.bytedance.android.doubaoime";

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        try {
            ImeBlurHook.install(param.getDefaultClassLoader(), this);
            log(Log.INFO, TAG, "blur hook installed for " + param.getPackageName());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "blur hook installation failed: " + t);
        }
    }
}
