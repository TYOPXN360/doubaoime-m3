# 豆包输入法 Monet 模糊模块

LibXposed 模块，目标包 `com.bytedance.android.doubaoime`（适配豆包输入法 **1.5.6**）。
把键盘做成 Gboard 风格：系统 Monet 动态取色 + 背景模糊。

## 功能

- 键盘、候选栏、工具栏统一 Monet 动态取色，键盘背景带实时模糊
- 按键半透明，按压/长按气泡跟随 Monet 色板
- 手写找字页、候选展开页、超级联想输入框接入 Monet
- 剪贴板、常用语卡片与分词胶囊统一为圆角半透明 Monet
- 滑动工具栏、翻译、表情页、定制工具栏页与键盘视觉一致，不叠加、不串层
- 剪贴板提示、浮层页面出现时自动让位，不与键盘内容重叠
- Android 12 以下只保留取色，模糊保护性跳过

## 安装

- 已签名 APK 见 Releases 页
- 最低系统：Android 8.0；模糊效果需 Android 12+
- 需要 LSPosed（LibXposed API 102），作用域只勾选豆包输入法

## 构建

```bash
cd module
./gradlew :app:assembleRelease
```

模块元数据：

- `app/src/main/resources/META-INF/xposed/java_init.list`
- `app/src/main/resources/META-INF/xposed/scope.list`

LibXposed API 102 参考：

- API 文档索引：<https://libxposed.github.io/api/>
- Service 文档索引：<https://libxposed.github.io/service/>
- API 源码：<https://github.com/libxposed/api>
