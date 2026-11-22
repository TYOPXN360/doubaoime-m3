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

`module/` 是独立的 LibXposed API 102 Android 模块，目标包仅为 `com.bytedance.android.doubaoime`。实现位于 `ImeBlurHook.kt`：

- hook `ImeService.onStartInputView(EditorInfo, boolean)`，并在 `onCreateInputView()` 作为兜底；
- 对 `ImeService.getWindow()` 返回的键盘根视图设置 Android 12+ `RenderEffect.createBlurEffect()`；
- 清除输入法 DecorView 的背景模糊，避免模糊键盘后面的屏幕；
- 低于 Android 12 或厂商 API 缺失时保护性跳过，不影响原版输入法启动。

当前版本优先完成模糊效果；渐变、Monet 取色、动态噪点、描边和其它魔改功能需在设备上确认原版窗口树与系统实现后继续拆分，避免靠猜测 hook 私有类。

### 1.5.6 更新（2026-11-22）

样本 `source/豆包输入法_1.5.6.apk`（versionCode `100506012`，SHA-256 见
`analysis/diff/hook-anchors-1.5.6.md`）已用 DexSQL 逐条复核：

- 15 个仍在使用的 hook 锚点全部命中，结构化 matcher 不需要硬编码混淆方法名。
- 删除 1.5.3 遗留、1.5.6 已不存在的 `InputViewRoot.r2` / `ImeAnimatedStatusView.c` /
  `AsrWaveView.g` 三条死 hook。
- 移除过宽的 `toolboxRefresh` matcher：它在 1.5.6 会命中 24 个单参 void 方法，
  `firstOrNull` 可能挂到 setter 上；该 hook 与 `hookOverlayVisibility` /
  `hookToolboxToggle` 的 `applyToolboxSurface()` 重复，已删除。
- 修复首次弹键盘卡顿：删除 `CandidateIdleView` 的递归 dump，热路径日志统一走
  `DEBUG=false` 的 `dlog {}`，并按 id / 名字缓存资源查询；首次模糊的延迟从 4 次减为 3 次。
- 修复工具栏偶发“双层 Monet”：`applyToolbarPageSurface()` 不再给工具栏页铺 0x99，
  改为透明；`keyboard_whole` 内部子容器的 `#222222` 也改为透明，避免在 0x66 底板上再叠一层。
- `HookFinder` 增加 DexKit 初始化/单次查找耗时日志，便于确认剩余卡顿是否来自
  180MB APK 的首次索引。
- 修复剪贴板 / 常用语里条目卡片仍是灰色：这些底色写死在 shape drawable 里
  （`bg_clipboard_history_item` 与 `bg_common_phrase_normal` 夜间都是 `#ff3d3d3d`，
  分词胶囊是 `#0f000000`），inflate 既不经过 `Resources.getColor` 也不经过
  `View.setBackgroundResource`，之前的颜色钩子接不到。现在面板可见 / 列表滚动时按
  视图 id（`clipboard_history_list_item_bg`、`common_phrase_list_item_bg`、
  `clipboard_history_word_segmentation_item_text`）扫一遍，换成同圆角的半透明
  Monet 强调色；饱和度高的选中态背景保持原样，选中反馈不丢。
- 修复双层 Monet 的根因：删掉了全局的 `Resources.getColor(#222222) → 0x66` 重映射。
  那个重映射会把候选栏 `native_candidate_bar` 也染成半透明强调色，铺在
  `keyboard_whole` 的 0x66 底板上就是第二层。现在只在 `setBackground` /
  `setBackgroundColor` 里按 `isOverMonetBoard(view)` 判断：底板内部一律透明，
  其余才用 0x66。另外补了 attach 后 0/120/400ms 的复查，清掉“inflate 时还判断
  不出在底板里”的迟到色块 —— 表情 → 返回 → 翻译那种先后顺序不再残留双层。
- 复制文字后剪贴板提示会盖住工具栏：`toolbar_clipboard_close` 可见期间把
  `native_candidate_bar` 的 alpha 置 0，提示消失后恢复，两者不再叠在一起。
- 超级联想的输入框、找字界面的删除键、候选展开页的删除键原来是不透明灰底，
  现在分别按 `SuperAssociationEditText` / `more_candidate_delete` 换成圆角半透明 Monet。
- “定制工具栏”页（PopupWindow）与键盘重叠 + 双层 Monet：原生浮窗铺的是不透明
  `#222222`，被换成 0x66 后键盘键位透出来、还和底板叠成两层。现在浮窗弹出时把
  面板本身改透明、临时隐藏 `KeyboardView`（应用进入定制态会重置 alpha，所以用
  120ms 看门狗持续压住），只留键盘那一层 0x66 底板；`dismiss()` 时停止并恢复。

DexSQL 目前只有桌面 CLI/MCP 和 WASM，没有可在 Android 进程内加载的运行库，
因此没有把 dexsql 塞进 APK；运行期仍由 DexKit 按结构特征解析。dexsql 用于
构建/分析期的锚点核对：

```bash
DEXSQL=/path/to/dexsql tools/check-hook-anchors.sh source/豆包输入法_1.5.6.apk
```

查询定义在 `tools/hook-anchors.sql`，失配会输出 `<MISSING>` 并以非 0 退出。

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
