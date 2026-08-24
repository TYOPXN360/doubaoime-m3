# 豆包输入法差异与 LibXposed 模块

## 当前已确认差异

样本：

- 原版：`source/豆包输入法_1.4.2.apk`
- 魔改版：`source/豆包魔改版.apk`

SHA-256 记录在 `analysis/diff/sample-sha256.txt`。

魔改版相对原版新增了 `classes4.dex` 和 `lib/arm64-v8a/libdbi.so`。通过 JADX CLI、apktool 和文本检索确认，模糊功能主要位于新增的 `swipe` 包：

- `swipe.BlurEffectManager`：Android 12+ 通过反射创建 `BackgroundBlurDrawable`，设置模糊半径和圆角，并可叠加渐变、描边、动态噪点。
- `swipe.SwipeManager`：从 `swipe_preferences` 读取 `blur_enabled`、`blur_portrait_radius`、`blur_landscape_radius`、`blur_corner_radius` 等配置。
- 魔改版在 `ImeService`、候选区、键盘视图等调用 `BlurEffectManager.applyBlurEffect()`；原版不存在这些 `swipe` 入口调用。
- 魔改版新增 `swipe.SwipeSettingsActivity`，Manifest 中新增 `swipe.SwipeSettingsActivity`。

这一区分了功能新增与普通依赖/资源重排差异。`libdbi.so` 已记录为新增 native 文件，但当前模糊入口已由 Java/smali 证据闭合，不把 native 文件误认作模糊实现。

## 模块

`module/` 是独立的 LibXposed API 102 Android 模块，目标包仅为 `com.bytedance.android.doubaoime`。实现位于 `ImeBlurHook.java`：

- hook `ImeService.onStartInputView(EditorInfo, boolean)`，并在 `onCreate()` 作为兜底；
- 对 `ImeService.getInputView()` 返回的键盘根视图设置 Android 12+ `RenderEffect.createBlurEffect()`；
- 清除输入法 DecorView 的背景模糊，避免模糊键盘后面的屏幕；
- 低于 Android 12 或厂商 API 缺失时保护性跳过，不影响原版输入法启动。

当前版本优先完成模糊效果；渐变、Monet 取色、动态噪点、描边和其它魔改功能需在设备上确认原版窗口树与系统实现后继续拆分，避免靠猜测 hook 私有类。

## 构建

```bash
cd module
./gradlew :app:assembleDebug
```

模块元数据：

- `app/src/main/resources/META-INF/xposed/java_init.list`
- `app/src/main/resources/META-INF/xposed/scope.list`

LibXposed API 102 参考：

- API 文档索引：<https://libxposed.github.io/api/>
- Service 文档索引：<https://libxposed.github.io/service/>
- API 源码：<https://github.com/libxposed/api>

## 复现分析

工具固定在 `tools/`，使用 JADX CLI 1.5.6、apktool 3.0.3 和 Temurin JDK 17；工具校验和见 `tools/SHA256SUMS`。解包产物按样本分类位于 `analysis/original/`、`analysis/modded/`，差异摘要位于 `analysis/diff/`。
