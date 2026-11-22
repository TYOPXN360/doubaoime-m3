# 豆包输入法 1.5.6 hook 锚点复核

- 样本：`source/豆包输入法_1.5.6.apk`
- 包名：`com.bytedance.android.doubaoime`
- versionName：`1.5.6`
- versionCode：`100506012`
- SHA-256：`e2dd286f3aeeb8a329b57622df3ca76ac2ee8b7f9a80544ea6404a26b92fa41c`
- 大小：`180699587` bytes
- 工具：DexSQL `v0.1.1`（`tools/hook-anchors.sql` + `tools/check-hook-anchors.sh`）

## 结论

15 个仍在使用的 hook 锚点在 1.5.6 全部命中，模块里的结构化 matcher 不需要改签名。

1.5.3 里写死混淆名的三条 ASR/状态栏 hook（`InputViewRoot.r2`、
`ImeAnimatedStatusView.c`、`AsrWaveView.g`）在 1.5.6 已不存在；它们本来也没有
出现在 `install()` 的安装列表里，本次直接删除，避免下次误接。

旧版 `HookPoints.toolboxRefresh()` 只按「单参 + void + 不调用 setBackground」过滤，
在 1.5.6 的 `ToolboxKeyboardView` 里会命中 **24 个**方法（包括
`setOnBackClick`、`setOnItemClick` 等 setter）。`HookFinder.method()` 取
`firstOrNull`，实际可能把工具箱整页背景铺到错误的方法上。该 hook 与
`hookOverlayVisibility` / `hookToolboxToggle` 的 `applyToolboxSurface()` 重复，
本次移除，不再参与运行期解析。

## dexsql 结果

```text
+-----------------------------------------------+------------------------------+----------------------------------------------------------------------------------------------------------------------+
| anchor                                        | method                       | descriptor                                                                                                           |
+-----------------------------------------------+------------------------------+----------------------------------------------------------------------------------------------------------------------+
| 1. ImeService.onCreateInputView               | onCreateInputView            | Lcom/bytedance/android/doubaoime/ImeService;->onCreateInputView()Landroid/view/View;                                 |
| 2. ImeService.onStartInputView                | onStartInputView             | Lcom/bytedance/android/doubaoime/ImeService;->onStartInputView(Landroid/view/inputmethod/EditorInfo;Z)V              |
| 3. KeyboardView.drawShadow                    | drawShadow                   | Lcom/bytedance/android/input/keyboard/KeyboardView;->drawShadow(IIIIFFFFIF)V                                         |
| 4. KeyboardView.drawTouchTipBubble            | drawTouchTipBubble           | Lcom/bytedance/android/input/keyboard/KeyboardView;->drawTouchTipBubble(FFFFFFFFFFI)V                                |
| 5. KeyboardView.drawTouchTipBubbleShadow      | drawTouchTipBubbleShadow     | Lcom/bytedance/android/input/keyboard/KeyboardView;->drawTouchTipBubbleShadow(FFFFFFFFFFIF)V                         |
| 6. CandidateItemView.selected                 | b                            | Lcom/bytedance/common_biz/tool_bar/view/widgets/CandidateItemView;->b(I)V                                            |
| 7. InputViewRoot.setNativeCandidateBarVisible | setNativeCandidateBarVisible | Lcom/bytedance/android/input/keyboard/areacontrol/InputViewRoot;->setNativeCandidateBarVisible(ZLjava/lang/String;)V |
| 8. InputViewRoot.toolboxToggle                | K0                           | Lcom/bytedance/android/input/keyboard/areacontrol/InputViewRoot;->K0(Ljava/lang/String;Z)V                           |
| 9. InputViewRoot.emojiToggle                  | A0                           | Lcom/bytedance/android/input/keyboard/areacontrol/InputViewRoot;->A0(ZLjava/lang/String;Ljava/lang/String;)V         |
| 10. OptionItemView.setAdded                   | setAdded                     | Lcom/bytedance/android/input/OptionItemView;->setAdded(Z)V                                                           |
| 11. OptionItemView.setIcon                    | setIcon                      | Lcom/bytedance/android/input/OptionItemView;->setIcon(I)V                                                            |
| 12. OptionItemView.setLabel                   | setLabel                     | Lcom/bytedance/android/input/OptionItemView;->setLabel(Ljava/lang/String;)V                                          |
| 13. ToolboxItemView.refresh                   | d                            | Lcom/bytedance/common_biz/tool_box/ToolboxItemView;->d(Ljava/lang/String;IZFFLnu0/l;)V                               |
| 14. ToolbarClipboardLayoutImpl.dismiss        | dismiss                      | Lcom/bytedance/android/input/keyboard/toolbar/clipboard/ToolbarClipboardLayoutImpl;->dismiss()V                      |
| 15. SegmentedBar.setItems                     | setItems                     | Lcom/bytedance/android/input/keyboard/component/SegmentedBar;->setItems(Ljava/util/List;)V                           |
+-----------------------------------------------+------------------------------+----------------------------------------------------------------------------------------------------------------------+
```

## 资源 ID 复核

1.5.6 里以下模块依赖的 id 仍存在：

- `keyboard_whole` / `keyboard_whole_llm` / `keyboard_whole_board_area`
- `native_candidate_bar`
- `toolbox_content_container` / `toolbox_header_container` / `toolbox_item_content`
- `more_candidate_root` / `more_candidate_tab_area` / `more_candidate_tab_container`

## 第一次弹键盘卡顿

模块里有两类开销会集中在首次 inflate：

1. `View.setBackground` hook 对每个带 id 的 `ColorDrawable` 都打一条
   `setBackground view=... id=... color=...`。键盘首次 inflate 会刷几百行。
2. 未提交的调试代码在每次 `CandidateIdleView` 可见性变化时 `postDelayed(350ms)`
   递归 dump 候选区（最深 6 层、每层最多 20 个子 View），主线程上再刷几十到上百行。

本次处理：

- 删除 `CandidateIdleView` dump 和 `dumpCand()`；
- 新增 `DEBUG = false` 和 `dlog {}`，热路径日志默认不执行（`const` 条件会被内联掉）；
- `Resources.getColor` / `View.setBackground` / `setBackgroundColor` / `setBackgroundResource`
  的逐次日志改为 `dlog`；
- `getResourceEntryName` 按 id 缓存，`getIdentifier` 按名字缓存；
- 首次应用模糊的延迟从 `100/300/700/1200ms` 四次减为 `120/400/900ms` 三次；
- `HookFinder` 增加 DexKit 初始化 / 单次查询耗时日志，便于确认剩余卡顿是否来自 DexKit 扫描。

如果关闭日志后首次弹键盘仍慢，下一步应看 `DexKit ready in ...ms`：180MB APK 的
DexKit 首次建索引仍可能是主要成本。届时的优化方向是把解析结果按 versionCode 缓存，
或用 dexsql 在构建期生成签名表，运行期只做反射校验。
