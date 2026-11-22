package me.doubaoime.blur

import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method

/**
 * 运行时 hook 点解析。
 *
 * 豆包每个版本都会重命名混淆方法（1.4.2 的 m2/r2/o，到 1.5.3 全没了），
 * 所以这里不用任何混淆名，只用两类跨版本稳定的锚点：
 *
 *  1. 类名 —— 业务类名基本不变，混淆只发生在方法/字段层。
 *  2. 结构特征 —— 方法签名、参数类型、返回类型、调用的系统 API、引用的字符串。
 *
 * DexKit 直接读设备上的 dex，不加载目标类。
 */
internal object HookFinder {
    private const val TAG = "DoubaoImeBlur-Finder"

    @Volatile
    private var bridge: DexKitBridge? = null

    fun init(apkPath: String) {
        if (bridge?.isValid == true) return
        try {
            loadNativeLib()
            val t0 = android.os.SystemClock.elapsedRealtime()
            val b = DexKitBridge.create(apkPath)
            bridge = b
            Log.i(TAG, "DexKit ready in ${android.os.SystemClock.elapsedRealtime() - t0}ms")
        } catch (t: Throwable) {
            Log.w(TAG, "DexKit init failed: $t")
        }
    }

    /**
     * 模块代码跑在目标应用进程里，System.loadLibrary 只查目标应用的 nativeLibraryDir，
     * 找不到模块自带的 libdexkit.so，所以这里显式按模块 APK 路径加载。
     */
    private fun loadNativeLib() {
        try {
            System.loadLibrary("dexkit")
            return
        } catch (_: Throwable) {}
        val candidates = listOf(
            "arm64-v8a", "armeabi-v7a", "x86_64", "x86"
        )
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull { it in candidates }
            ?: candidates.first()
        val moduleApk = moduleApkPath()
        val soPath = "$moduleApk!/lib/$abi/libdexkit.so"
        System.load(soPath)
        Log.i(TAG, "loaded $soPath")
    }

    /** 模块自身的 APK 路径（不是目标应用）。 */
    private fun moduleApkPath(): String {
        val cl = HookFinder::class.java.classLoader ?: error("no classloader")
        // 模块的类来自模块 APK，取其 resources 的 asset/apk 路径
        val res = cl.getResource("META-INF/xposed/java_init.list")
            ?: cl.getResource("META-INF/xposed/scope.list")
            ?: error("cannot locate module apk")
        val path = res.path
        return path.substringBefore("!/").removePrefix("file:")
    }

    fun close() {
        try { bridge?.close() } catch (_: Throwable) {}
        bridge = null
    }

    /**
     * 在 [className] 里找满足 [matcher] 的方法，返回真实 [Method]。
     * [matcher] 只写结构特征，不要写死混淆名。
     */
    fun method(loader: ClassLoader, className: String, matcher: MethodMatcher): Method? {
        val b = bridge ?: return null
        return try {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val matches = b.findMethod(
                FindMethod().searchPackages("com.bytedance")
                    .matcher(matcher.declaredClass(className))
            ).filter { it.methodName != "<init>" && it.methodName != "<clinit>" }
            val cost = android.os.SystemClock.elapsedRealtime() - t0
            if (cost > 50) Log.i(TAG, "findMethod $className took ${cost}ms")
            if (matches.isEmpty()) {
                Log.w(TAG, "no match in $className")
                return null
            }
            if (matches.size > 1) {
                // 结构指纹太宽时，firstOrNull 可能挂到 setter 之类的方法上。
                // 这里把全部候选打出来，方便下一次版本升级收紧 matcher。
                Log.w(
                    TAG,
                    "ambiguous match in $className: " + matches.joinToString {
                        "${it.methodName}(${it.paramTypeNames.joinToString(",")})"
                    }
                )
            }
            val found = matches.first()
            val clazz = Class.forName(className, false, loader)
            clazz.getDeclaredMethod(found.methodName, *paramClasses(loader, found.paramTypeNames))
                .apply { isAccessible = true }
        } catch (t: Throwable) {
            Log.w(TAG, "resolve $className failed: $t")
            null
        }
    }

    private fun paramClasses(loader: ClassLoader, names: List<String>): Array<Class<*>> =
        names.map { name ->
            when (name) {
                "boolean" -> Boolean::class.javaPrimitiveType!!
                "byte" -> Byte::class.javaPrimitiveType!!
                "char" -> Char::class.javaPrimitiveType!!
                "short" -> Short::class.javaPrimitiveType!!
                "int" -> Int::class.javaPrimitiveType!!
                "long" -> Long::class.javaPrimitiveType!!
                "float" -> Float::class.javaPrimitiveType!!
                "double" -> Double::class.javaPrimitiveType!!
                else -> Class.forName(name, false, loader)
            }
        }.toTypedArray()
}
