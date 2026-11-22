-- 豆包输入法 hook 锚点核对（单条 SQL，供 tools/check-hook-anchors.sh 调用）
--
-- 设计原则：只依赖跨版本稳定的结构特征（类名、参数/返回类型、调用的系统 API），
-- 不写死 b/o/m2/r2 这类混淆方法名。每个块固定返回一行：
--   method = '<MISSING>' 表示当前 APK 已失配，需要更新 matcher。
--
-- 用法：
--   dexsql query --input 豆包输入法_1.5.6.apk --sql "$(cat tools/hook-anchors.sql)"
--
-- 参考：https://github.com/LSPosed/dexsql-release

SELECT '1. ImeService.onCreateInputView' AS anchor,
       IFNULL(MAX(name), '<MISSING>') AS method,
       IFNULL(MAX(descriptor), '') AS descriptor
FROM methods
WHERE class_descriptor='Lcom/bytedance/android/doubaoime/ImeService;'
  AND name='onCreateInputView' AND param_count=0 AND return_descriptor='Landroid/view/View;'

UNION ALL
SELECT '2. ImeService.onStartInputView',
       IFNULL(MAX(name), '<MISSING>'),
       IFNULL(MAX(descriptor), '')
FROM methods
WHERE class_descriptor='Lcom/bytedance/android/doubaoime/ImeService;'
  AND name='onStartInputView'
  AND param_descriptors='Landroid/view/inputmethod/EditorInfo;,Z'
  AND return_descriptor='V'

UNION ALL
SELECT '3. KeyboardView.drawShadow',
       IFNULL(MAX(m.name), '<MISSING>'),
       IFNULL(MAX(m.descriptor), '')
FROM methods m
WHERE m.class_descriptor='Lcom/bytedance/android/input/keyboard/KeyboardView;'
  AND m.param_descriptors='I,I,I,I,F,F,F,F,I,F'
  AND m.return_descriptor='V'
  AND EXISTS (SELECT 1 FROM method_calls c
              WHERE c.method_class=m.class_descriptor AND c.method_name=m.name
                AND c.callee='Landroid/graphics/Canvas;->drawPath(Landroid/graphics/Path;Landroid/graphics/Paint;)V')

UNION ALL
SELECT '4. KeyboardView.drawTouchTipBubble',
       IFNULL(MAX(name), '<MISSING>'),
       IFNULL(MAX(descriptor), '')
FROM methods
WHERE class_descriptor='Lcom/bytedance/android/input/keyboard/KeyboardView;'
  AND param_descriptors='F,F,F,F,F,F,F,F,F,F,I'
  AND return_descriptor='V'

UNION ALL
SELECT '5. KeyboardView.drawTouchTipBubbleShadow',
       IFNULL(MAX(name), '<MISSING>'),
       IFNULL(MAX(descriptor), '')
FROM methods
WHERE class_descriptor='Lcom/bytedance/android/input/keyboard/KeyboardView;'
  AND param_descriptors='F,F,F,F,F,F,F,F,F,F,I,F'
  AND return_descriptor='V'

UNION ALL
SELECT '6. CandidateItemView.selected',
       IFNULL(MAX(m.name), '<MISSING>'),
       IFNULL(MAX(m.descriptor), '')
FROM methods m
WHERE m.class_descriptor='Lcom/bytedance/common_biz/tool_bar/view/widgets/CandidateItemView;'
  AND m.param_descriptors='I'
  AND m.return_descriptor='V'
  AND EXISTS (SELECT 1 FROM method_calls c
              WHERE c.method_class=m.class_descriptor AND c.method_name=m.name
                AND c.callee='Landroid/widget/TextView;->setTextColor(I)V')
  AND EXISTS (SELECT 1 FROM method_calls c
              WHERE c.method_class=m.class_descriptor AND c.method_name=m.name
                AND c.callee='Landroidx/core/content/ContextCompat;->getColor(Landroid/content/Context;I)I')

UNION ALL
SELECT '7. InputViewRoot.setNativeCandidateBarVisible',
       IFNULL(MAX(name), '<MISSING>'),
       IFNULL(MAX(descriptor), '')
FROM methods
WHERE class_descriptor='Lcom/bytedance/android/input/keyboard/areacontrol/InputViewRoot;'
  AND param_descriptors='Z,Ljava/lang/String;'
  AND return_descriptor='V'

UNION ALL
SELECT '8. InputViewRoot.toolboxToggle',
       IFNULL(MAX(m.name), '<MISSING>'),
       IFNULL(MAX(m.descriptor), '')
FROM methods m
WHERE m.class_descriptor='Lcom/bytedance/android/input/keyboard/areacontrol/InputViewRoot;'
  AND m.param_descriptors='Ljava/lang/String;,Z'
  AND m.return_descriptor='V'
  AND EXISTS (SELECT 1 FROM method_calls c
              WHERE c.method_class=m.class_descriptor AND c.method_name=m.name
                AND c.callee='Landroid/view/ViewGroup;->addView(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V')
  AND EXISTS (SELECT 1 FROM method_calls c
              WHERE c.method_class=m.class_descriptor AND c.method_name=m.name
                AND c.callee='Lcom/bytedance/common_biz/tool_box/ToolboxKeyboardView;->setStatusGuide(Lcom/bytedance/common_biz/tool_box/h;)V')

UNION ALL
SELECT '9. InputViewRoot.emojiToggle',
       IFNULL(MAX(name), '<MISSING>'),
       IFNULL(MAX(descriptor), '')
FROM methods
WHERE class_descriptor='Lcom/bytedance/android/input/keyboard/areacontrol/InputViewRoot;'
  AND param_descriptors='Z,Ljava/lang/String;,Ljava/lang/String;'
  AND return_descriptor='V'

UNION ALL
SELECT '10. OptionItemView.setAdded',
       IFNULL(MAX(name), '<MISSING>'),
       IFNULL(MAX(descriptor), '')
FROM methods
WHERE class_descriptor='Lcom/bytedance/android/input/OptionItemView;'
  AND name='setAdded' AND param_descriptors='Z'

UNION ALL
SELECT '11. OptionItemView.setIcon',
       IFNULL(MAX(name), '<MISSING>'),
       IFNULL(MAX(descriptor), '')
FROM methods
WHERE class_descriptor='Lcom/bytedance/android/input/OptionItemView;'
  AND name='setIcon' AND param_descriptors='I'

UNION ALL
SELECT '12. OptionItemView.setLabel',
       IFNULL(MAX(name), '<MISSING>'),
       IFNULL(MAX(descriptor), '')
FROM methods
WHERE class_descriptor='Lcom/bytedance/android/input/OptionItemView;'
  AND name='setLabel' AND param_descriptors='Ljava/lang/String;'

UNION ALL
SELECT '13. ToolboxItemView.refresh',
       IFNULL(MAX(m.name), '<MISSING>'),
       IFNULL(MAX(m.descriptor), '')
FROM methods m
WHERE m.class_descriptor='Lcom/bytedance/common_biz/tool_box/ToolboxItemView;'
  AND m.param_count=6
  AND m.return_descriptor='V'
  AND EXISTS (SELECT 1 FROM method_calls c
              WHERE c.method_class=m.class_descriptor AND c.method_name=m.name
                AND c.callee='Lcom/airbnb/lottie/LottieAnimationView;->setProgress(F)V')
  AND EXISTS (SELECT 1 FROM method_calls c
              WHERE c.method_class=m.class_descriptor AND c.method_name=m.name
                AND c.callee='Landroid/view/View;->setScaleX(F)V')

UNION ALL
SELECT '14. ToolbarClipboardLayoutImpl.dismiss',
       IFNULL(MAX(name), '<MISSING>'),
       IFNULL(MAX(descriptor), '')
FROM methods
WHERE class_descriptor='Lcom/bytedance/android/input/keyboard/toolbar/clipboard/ToolbarClipboardLayoutImpl;'
  AND name='dismiss' AND param_count=0

UNION ALL
SELECT '15. SegmentedBar.setItems',
       IFNULL(MAX(name), '<MISSING>'),
       IFNULL(MAX(descriptor), '')
FROM methods
WHERE class_descriptor='Lcom/bytedance/android/input/keyboard/component/SegmentedBar;'
  AND name='setItems' AND param_descriptors='Ljava/util/List;'

ORDER BY anchor;
