// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 TYOPXN360

package me.doubaoime.blur

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.dynamiccolor.ColorSpec2021
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeTonalSpot

/**
 * 使用 MaterialKolor（material-color-utilities 官方 Kotlin 移植）的取色引擎。
 * 与 ReSukiSU MD3E 管理器完全一致：种子色 → ColorSpec2021 + TonalSpot → M3 ColorScheme。
 * 同时注册壁纸变更广播，壁纸变化时自动重新取色。
 */
object MonetEngine {
    private const val TAG = "DoubaoImeBlur-Monet"

    /** 最终强调色（primary），各 hook 直接用这个值 */
    var accentColor: Int = 0xFF4F84FF.toInt() // 默认亮蓝
        private set

    /** 强调色带透明度（primary + 0xCC alpha），用于滑动高亮等半透明场景 */
    var accentColorTranslucent: Int = 0xCC4F84FF.toInt()
        private set

    /** 亮色高亮强调色（primary 深色模式 tone 80 = #8FCFF3 风格），候选栏高亮框 + 操作键用 */
    var accentHighlightColor: Int = 0x998FCFF3.toInt()
        private set

    /** 普通键莫奈色（secondaryContainer #364954，与功能键同色） */
    var keyNormalLight: Int = 0xFFFFFFFF.toInt()
        private set
    var keyNormalDark: Int = 0x47FFFFFF.toInt()
        private set

    /** 功能键强调色（Gboard 实测功能键色 #384B56，半透明模糊） */
    var keyFunctionLight: Int = 0xB3D1E5F3.toInt()
        private set
    var keyFunctionDark: Int = 0xB3384B56.toInt()
        private set

    /** 操作/回车/完成键（强调色） */
    var keyActionLight: Int = 0xCCA4CCE4.toInt()
        private set
    var keyActionDark: Int = 0xCC224B5F.toInt()
        private set

    /** 当前系统是否为深色模式（初始化时记录，供绘制 hook 判断，避免误用系统 Resources） */
    var isDarkMode: Boolean = false
        private set

    @Volatile
    private var initialized = false

    /** 是否已完成取色。绘制热路径据此跳过反射找 context。 */
    fun isReady(): Boolean = initialized

    private var appContext: Context? = null

    /** 惰性初始化：首次调用时确保只初始化一次，context 可用 */
    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            appContext = context.applicationContext
            initialize(context)
            registerWallpaperReceiver(appContext!!)
        }
    }

    /** 手动重新取色（壁纸变化时调用） */
    fun refreshFromWallpaperChange() {
        val ctx = appContext ?: return
        Log.i(TAG, "wallpaper changed, re-extracting seed color")
        // 重新初始化，但保留广播注册状态
        val wasInitialized = initialized
        initialized = false
        ensureInitialized(ctx)
        initialized = wasInitialized
    }

    /** 初始化取色引擎 */
    fun initialize(context: Context) {
        try {
            val res = context.resources
            isDarkMode = res.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

            // 1. 种子色：系统 accent1_500（ReSukiSU 同源）
            val seed = getSeedColor(context)

            // 2. MaterialKolor 原生库：SPEC_2021 + TonalSpot 生成 M3 ColorScheme
            //    完全对齐 ReSukiSU MD3E 管理器
            val spec = ColorSpec2021()
            val sourceHct = Hct.fromInt(seed)
            val scheme = SchemeTonalSpot(
                sourceHct, isDarkMode,
                0.0, // contrastLevel
                ColorSpec.SpecVersion.SPEC_2021,
                DynamicScheme.Platform.PHONE
            )

            // 3. 提取 M3 关键色
            val primary = spec.primary().getArgb(scheme)          // primary
            val primaryContainer = spec.primaryContainer().getArgb(scheme) // primaryContainer
            val secondary = spec.secondary().getArgb(scheme)      // secondary
            val secondaryContainer = spec.secondaryContainer().getArgb(scheme) // secondaryContainer
            val surfaceVariant = spec.surfaceVariant().getArgb(scheme) // surfaceVariant

            // 基准强调色
            accentColor = primary
            accentColorTranslucent = (primary and 0x00ffffff) or (0xCC shl 24)

            // 亮色高亮 = primary（深色模式 tone 80，MD3E 开关色 = #8FCFF3）
            accentHighlightColor = (primary and 0x00ffffff) or (0x99 shl 24)

            // 普通字母键 = 透明（豆包原生 #47ffffff，未按压时清澈）
            // 功能键 = secondaryContainer #364954 莫奈色
            keyNormalDark = 0x47FFFFFF.toInt() // 透明
            keyNormalLight = 0xFFFFFFFF.toInt()

            // 功能键 = secondaryContainer（莫奈 #364954）
            keyFunctionDark = withAlpha(secondaryContainer, 0xB3)
            keyFunctionLight = withAlpha(secondaryContainer, 0xB3)

            // 搜索/完成/回车键 = secondaryContainer（与功能键统一）
            keyActionDark = withAlpha(secondaryContainer, 0xCC)
            keyActionLight = withAlpha(secondaryContainer, 0xCC)

            Log.i(TAG, "Monet(MaterialKolor) primary=#${Integer.toHexString(primary)} container=#${Integer.toHexString(primaryContainer)} secondary=#${Integer.toHexString(secondary)} secContainer=#${Integer.toHexString(secondaryContainer)}")
        } catch (t: Throwable) {
            Log.w(TAG, "MonetEngine init failed: $t, using fallback")
            accentColor = 0xFF557C92.toInt()
            accentColorTranslucent = 0xCC557C92.toInt()
            accentHighlightColor = 0x998FCFF3.toInt()
            keyNormalLight = 0xFFFFFFFF.toInt()
            keyNormalDark = 0x47FFFFFF.toInt()
            keyFunctionLight = 0xB3D1E5F3.toInt()
            keyFunctionDark = 0xB3384B56.toInt()
            keyActionLight = 0xCCA4CCE4.toInt()
            keyActionDark = 0xCC224B5F.toInt()
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00ffffff) or (alpha shl 24)

    /** 读取系统 neutral 动态色（脏脏透明底） */
    private fun sysNeutral(res: android.content.res.Resources, name: String, fallback: Int): Int {
        val id = res.getIdentifier(name, "color", "android")
        return if (id != 0) res.getColor(id) else fallback
    }

    private fun blendColor(from: Int, to: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val r = ((from shr 16 and 0xff) * inv + (to shr 16 and 0xff) * ratio).toInt()
        val g = ((from shr 8 and 0xff) * inv + (to shr 8 and 0xff) * ratio).toInt()
        val b = ((from and 0xff) * inv + (to and 0xff) * ratio).toInt()
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 注册壁纸变更广播（参考 resukisu MonetCompat 的壁纸监听） */
    private fun registerWallpaperReceiver(context: Context) {
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_WALLPAPER_CHANGED) {
                        refreshFromWallpaperChange()
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_WALLPAPER_CHANGED)
            }
            context.registerReceiver(receiver, filter)
            Log.i(TAG, "wallpaper change receiver registered")
        } catch (t: Throwable) {
            Log.w(TAG, "register wallpaper receiver failed: $t")
        }
    }

    /** 从系统动态色板获取种子色 */
    private fun getSeedColor(context: Context): Int {
        // 优先用系统 accent1_500（系统官方权威种子色）
        val id = context.resources.getIdentifier("system_accent1_500", "color", "android")
        if (id != 0) return context.getColor(id)

        // fallback 1: WallpaperManager 取壁纸主色（反射，避免编译期 SDK 检查）
        try {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                val wm = context.getSystemService(Context.WALLPAPER_SERVICE)
                val getColors = wm.javaClass.getMethod("getWallpaperColors", Int::class.javaPrimitiveType)
                val colors = getColors.invoke(wm, 1) // FLAG_SYSTEM
                if (colors != null) {
                    val getPrimary = colors.javaClass.getMethod("getPrimaryColor")
                    val primary = getPrimary.invoke(colors)
                    if (primary != null) return primary as Int
                }
            }
        } catch (_: Throwable) {}

        // fallback 2: 系统 colorAccent
        try {
            val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorAccent))
            val accent = ta.getColor(0, 0xFF557C92.toInt())
            ta.recycle()
            return accent
        } catch (_: Throwable) {}

        return 0xFF557C92.toInt()
    }
}