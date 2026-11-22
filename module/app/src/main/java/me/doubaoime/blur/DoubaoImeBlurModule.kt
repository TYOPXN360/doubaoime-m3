// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 TYOPXN360

package me.doubaoime.blur

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class DoubaoImeBlurModule : XposedModule() {
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE) return
        try {
            ImeBlurHook.install(param.defaultClassLoader, this, param.applicationInfo?.sourceDir ?: return)
            log(Log.INFO, TAG, "blur hook installed for ${param.packageName}")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "blur hook installation failed: $t")
        }
    }

    private companion object {
        const val TAG = "DoubaoImeBlur"
        const val TARGET_PACKAGE = "com.bytedance.android.doubaoime"
    }
}
