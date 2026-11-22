// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 TYOPXN360

package me.doubaoime.blur

import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher

/**
 * 所有 hook 点的结构定义。
 *
 * 这里**不允许出现混淆方法名**（b / o / m2 / r2 / X0…）——那些每个版本都会变。
 * 只写类名 + 结构特征（签名 / 参数 / 返回类型 / 调用的系统 API）。
 *
 * 2026-11-22 用 dexsql 对豆包 1.5.6（versionCode 100506012）逐条复核：
 * 除已删除的旧 r2/c/g 系列外，下列锚点在 1.5.6 仍然唯一命中。
 * 复核 SQL 与结果见 `tools/check-hook-anchors.sh`（查询在 `tools/hook-anchors.sql`）
 * 和 `analysis/diff/hook-anchors-1.5.6.md`。
 */
internal object HookPoints {
    const val TAG = "DoubaoImeBlur-Points"

    const val IME_SERVICE = "com.bytedance.android.doubaoime.ImeService"
    const val KEYBOARD_VIEW = "com.bytedance.android.input.keyboard.KeyboardView"
    const val CANDIDATE_ITEM = "com.bytedance.common_biz.tool_bar.view.widgets.CandidateItemView"
    const val INPUT_VIEW_ROOT = "com.bytedance.android.input.keyboard.areacontrol.InputViewRoot"
    const val TOOLBOX = "com.bytedance.common_biz.tool_box.ToolboxKeyboardView"
    const val TOOLBOX_ITEM = "com.bytedance.common_biz.tool_box.ToolboxItemView"
    const val OPTION_ITEM = "com.bytedance.android.input.OptionItemView"
    const val MORE_CANDIDATE = "com.bytedance.common_biz.more_candidate.MoreCandidateView"
    const val HANDWRITING_BOARD = "com.bytedance.android.input.keyboard.handwriting.HandWritingBoardView"
    const val HANDWRITING_LOOKUP = "com.bytedance.android.input.keyboard.handwriting.HandWritingLookupBoardView"
    const val EMOJI_LAYOUT = "com.bytedance.android.input.keyboard.emoji.EmojiLayout"

    /**
     * 候选词选中态。1.5.6 实测唯一命中：`b(I)V`
     *   param: int / return: void / 调用 TextView.setTextColor + ContextCompat.getColor
     * 1.5.6 的实现改成了 ContextCompat.getDrawable + setBackground，但这两个调用仍在，
     * 所以结构指纹不变；特征完全不依赖方法名。
     */
    fun candidateSelected(): MethodMatcher = MethodMatcher.create()
        .paramCount(1)
        .addParamType("int")
        .returnType("void")
        .addInvoke("Landroid/widget/TextView;->setTextColor(I)V")
        .addInvoke("Landroidx/core/content/ContextCompat;->getColor(Landroid/content/Context;I)I")

    /** 键盘阴影。1.5.6 唯一命中：`drawShadow(IIIIFFFFIF)V`，内部走 Path + BlurMaskFilter */
    fun keyboardShadow(): MethodMatcher = MethodMatcher.create()
        .paramTypes("int", "int", "int", "int", "float", "float", "float", "float", "int", "float")
        .returnType("void")
        .addInvoke("Landroid/graphics/Canvas;->drawPath(Landroid/graphics/Path;Landroid/graphics/Paint;)V")

    /** 长按气泡正文。1.5.6 唯一：`drawTouchTipBubble(FFFFFFFFFFI)V`（10F+1I） */
    fun touchBubble(): MethodMatcher = MethodMatcher.create()
        .paramTypes("float", "float", "float", "float", "float", "float", "float", "float", "float", "float", "int")
        .returnType("void")

    /** 长按气泡阴影。1.5.6 唯一：`drawTouchTipBubbleShadow(FFFFFFFFFFIF)V`（10F+1I+1F） */
    fun touchBubbleShadow(): MethodMatcher = MethodMatcher.create()
        .paramTypes("float", "float", "float", "float", "float", "float", "float", "float", "float", "float", "int", "float")
        .returnType("void")

    /**
     * 候选栏显隐。1.5.6：`setNativeCandidateBarVisible(Z,String)V`（旧版叫 m2）
     * 按结构定位：单 boolean + String 参数、返回 void。
     */
    fun nativeCandidateBarVisible(): MethodMatcher = MethodMatcher.create()
        .paramTypes("boolean", "java.lang.String")
        .returnType("void")

    /** 工具栏状态变更。这三个是公开方法名，未被混淆 */
    fun setAdded(): MethodMatcher = MethodMatcher.create()
        .name("setAdded").paramCount(1).addParamType("boolean")

    fun setIcon(): MethodMatcher = MethodMatcher.create()
        .name("setIcon").paramCount(1).addParamType("int")

    fun setLabel(): MethodMatcher = MethodMatcher.create()
        .name("setLabel").paramCount(1).addParamType("java.lang.String")

    /**
     * 工具箱显示/隐藏的唯一入口（1.5.6 里是 InputViewRoot 的 `K0(String, boolean)V`）。
     *
     * 结构特征：String + boolean → void，且方法体同时出现
     *   - ViewGroup.addView(View, LayoutParams)
     *   - ToolboxKeyboardView.setStatusGuide(...)
     * 1.5.6 实测唯一命中；同签名的 E/F/n0/T0 都没有 setStatusGuide。
     */
    fun toolboxToggle(): MethodMatcher = MethodMatcher.create()
        .paramTypes("java.lang.String", "boolean")
        .returnType("void")
        .addInvoke("Landroid/view/ViewGroup;->addView(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V")
        .addInvoke("Lcom/bytedance/common_biz/tool_box/ToolboxKeyboardView;->setStatusGuide(Lcom/bytedance/common_biz/tool_box/h;)V")

    /**
     * 表情面板显示/隐藏入口（InputViewRoot 里 `A0(Z,String,String)V`）。
     *
     * 结构特征：boolean + String + String → void。
     * 该类里同签名的只有这一个（另一个 String+boolean 的是工具箱入口）。
     * 1.5.6 复核：A0 同时调用 EmojiLayout.f/k/h，确认是表情面板入口。
     */
    fun emojiToggle(): MethodMatcher = MethodMatcher.create()
        .paramTypes("boolean", "java.lang.String", "java.lang.String")
        .returnType("void")

    /**
     * 工具箱 item 刷新。1.5.6 唯一：`d(String,I,Z,F,F,Lnu0/l;)V`
     *
     * 参数里含混淆类型（Lnu0/l;），按类型名匹配不可靠；
     * 改用方法体特有的调用组合作为指纹：Lottie 动画 + 缩放。
     * 这组调用只有这一个方法同时具备。
     */
    fun toolboxItemRefresh(): MethodMatcher = MethodMatcher.create()
        .paramCount(6)
        .returnType("void")
        .addInvoke("Lcom/airbnb/lottie/LottieAnimationView;->setProgress(F)V")
        .addInvoke("Landroid/view/View;->setScaleX(F)V")
}
