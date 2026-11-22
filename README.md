# 豆包输入法 Monet 模糊模块

LibXposed 模块，目标包 `com.bytedance.android.doubaoime`（适配豆包输入法 **1.5.6**）。
把键盘做成 Gboard 风格：系统 Monet 动态取色 + 背景模糊。

## 功能

- 键盘、候选栏、工具栏统一 Monet 动态取色，键盘背景带实时模糊
- 按键半透明，按压/长按气泡跟随 Monet 色板
- 手写找字页、候选展开页、超级联想输入框接入 Monet
- 剪贴板、常用语卡片与分词胶囊统一为圆角半透明 Monet
- 滑动工具栏、翻译、表情页、定制工具栏页与键盘视觉一致，不叠加、不串层
- Android 12 以下只保留取色，模糊保护性跳过

## 修复记录（1.5.6）

- 工具栏 / 翻译页偶发**双层 Monet**（颜色明显更深）
- 先点表情再点翻译后，双层 Monet 一直残留
- **定制工具栏**页面与底下键盘重叠，并且有双层 Monet
- 复制文字后，剪贴板提示盖住工具栏
- 剪贴板 / 常用语条目卡片仍是灰色不透明底
- 超级联想输入框是灰色不透明底
- 找字页面、候选展开页的删除键是灰色不透明底
- “请先输入内容再整理”提示胶囊是灰色不透明底
- 第一次弹出键盘卡顿（日志与重复资源查询）
- 删除 1.5.6 已不存在的旧版本 hook

## 安装

- 版本：`1.1.0`（versionCode 2），Release 页提供已签名 APK
- 最低系统：Android 8.0；模糊效果需 Android 12+
- 需要 LSPosed（LibXposed API 102），作用域只勾选豆包输入法

## 构建

```bash
cd module
./gradlew :app:assembleRelease
```

构建前可核对 hook 锚点是否还在（DexSQL，只用于分析期）：

```bash
DEXSQL=/path/to/dexsql tools/check-hook-anchors.sh source/豆包输入法_1.5.6.apk
```

模块元数据：

- `app/src/main/resources/META-INF/xposed/java_init.list`
- `app/src/main/resources/META-INF/xposed/scope.list`

LibXposed API 102 参考：

- API 文档索引：<https://libxposed.github.io/api/>
- Service 文档索引：<https://libxposed.github.io/service/>
- API 源码：<https://github.com/libxposed/api>

## 分析工具

`tools/` 内为 JADX CLI 1.5.6、apktool 3.0.3 与 Temurin JDK 17 的固定版本脚本，校验和见 `tools/SHA256SUMS`。
