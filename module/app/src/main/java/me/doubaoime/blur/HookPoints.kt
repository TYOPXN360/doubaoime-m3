package me.doubaoime.blur

import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher

/**
 * 所有 hook 点的结构定义。
 *
 * 这里**不允许出现混淆方法名**（b / o / m2 / r2 / X0…）——那些每个版本都会变。
 * 只写类名 + 结构特征（签名 / 参数 / 返回类型 / 调用的系统 API）。
 *
 * 所有锚点均用 dexsql 对豆包 1.5.3 实测验证过唯一性，
 * 见本文件各条注释里的描述符。
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

    /**
     * 候选词选中态。1.5.3 实测唯一命中：`b(I)V`
     *   param: int / return: void / 调用 TextView.setTextColor + ContextCompat.getColor
     * 特征完全不依赖方法名。
     */
    fun candidateSelected(): MethodMatcher = MethodMatcher.create()
        .paramCount(1)
        .addParamType("int")
        .returnType("void")
        .addInvoke("Landroid/widget/TextView;->setTextColor(I)V")
        .addInvoke("Landroidx/core/content/ContextCompat;->getColor(Landroid/content/Context;I)I")

    /** 键盘阴影。1.5.3 唯一命中：`drawShadow(IIIIFFFFIF)V`，内部走 Path + BlurMaskFilter */
    fun keyboardShadow(): MethodMatcher = MethodMatcher.create()
        .paramTypes("int", "int", "int", "int", "float", "float", "float", "float", "int", "float")
        .returnType("void")
        .addInvoke("Landroid/graphics/Canvas;->drawPath(Landroid/graphics/Path;Landroid/graphics/Paint;)V")

    /** 长按气泡正文。1.5.3 唯一：`drawTouchTipBubble(FFFFFFFFFFI)V`（10F+1I） */
    fun touchBubble(): MethodMatcher = MethodMatcher.create()
        .paramTypes("float", "float", "float", "float", "float", "float", "float", "float", "float", "float", "int")
        .returnType("void")

    /** 长按气泡阴影。1.5.3 唯一：`drawTouchTipBubbleShadow(FFFFFFFFFFIF)V`（10F+1I+1F） */
    fun touchBubbleShadow(): MethodMatcher = MethodMatcher.create()
        .paramTypes("float", "float", "float", "float", "float", "float", "float", "float", "float", "float", "int", "float")
        .returnType("void")

    /**
     * 候选栏显隐。1.5.3：`setNativeCandidateBarVisible(Z,String)V`（旧版叫 m2）
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
     * 工具箱显示/隐藏的唯一入口（InputViewRoot 里 `F0(String, boolean)`）。
     *
     * 结构特征：String + boolean → void，且方法体同时出现
     *   - ViewGroup.addView(View, LayoutParams)
     *   - ToolboxKeyboardView.setStatusGuide(...)
     * 1.5.3 实测唯一命中。
     */
    fun toolboxToggle(): MethodMatcher = MethodMatcher.create()
        .paramTypes("java.lang.String", "boolean")
        .returnType("void")
        .addInvoke("Landroid/view/ViewGroup;->addView(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V")
        .addInvoke("Lcom/bytedance/common_biz/tool_box/ToolboxKeyboardView;->setStatusGuide(Lcom/bytedance/common_biz/tool_box/h;)V")

    /**
     * 工具箱视图刷新。1.5.3：`q(Lcom/bytedance/common_biz/tool_box/i;)V`
     *
     * 收敛条件只用非混淆特征：单参 void，且自身不碰 View.setBackground
     * （同类的 o(I)V 会设背景，是干扰项，用 noneOf 排除）。
     */
    fun toolboxRefresh(): MethodMatcher = MethodMatcher.create()
        .paramCount(1)
        .returnType("void")
        .noneOf(
            MethodMatcher.create().addInvoke("Landroid/view/View;->setBackground(Landroid/graphics/drawable/Drawable;)V")
        )

    /**
     * 工具箱 item 刷新。1.5.3 唯一：`d(String,I,Z,F,F,Lbu0/l;)V`
     *
     * 参数里含混淆类型（Lbu0/l;），按类型名匹配不可靠；
     * 改用方法体特有的调用组合作为指纹：Lottie 动画 + 缩放。
     * 这组调用只有这一个方法同时具备。
     */
    fun toolboxItemRefresh(): MethodMatcher = MethodMatcher.create()
        .paramCount(6)
        .returnType("void")
        .addInvoke("Lcom/airbnb/lottie/LottieAnimationView;->setProgress(F)V")
        .addInvoke("Landroid/view/View;->setScaleX(F)V")
}
