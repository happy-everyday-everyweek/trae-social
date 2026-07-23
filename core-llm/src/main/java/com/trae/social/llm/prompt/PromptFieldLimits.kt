package com.trae.social.llm.prompt

/**
 * Prompt 插值字段净化长度上限（#285：消除跨 Builder 重复字面量）。
 *
 * 此前各 PromptBuilder 在调用 [PromptUtils.sanitizeForPrompt] 时对同一人设字段
 * 各自硬编码相同数值（如 ageRange 在 TweetPromptBuilder / CommentPromptBuilder
 * 均写 `20`），任一处调整需同步多处且易遗漏。抽到此 object 后统一引用。
 *
 * 命名按"字段语义"而非"数值大小"，便于在字段语义变化时定位需调整的常量。
 */
object PromptFieldLimits {

    /** 短标签类字段：年龄段、单条 emoji（如 "25-34" / "😄"）。 */
    const val SHORT_TAG = 20

    /** 单行描述类字段：显示名、职业、语言风格、情绪、文化背景、时段描述。 */
    const val SINGLE_LINE = 60

    /** 中等长度枚举/主题类字段：用户兴趣主题、覆盖 value 预览。 */
    const val THEME = 40

    /** 口癖字段上限（略长于单行，允许短句）。 */
    const val CATCHPHRASE = 80

    /** 多句描述类字段：价值观、口籍摘要、用户背景 narrative。 */
    const val PARAGRAPH = 120

    /** 长文本字段：世界观、画像 narrative 摘要。 */
    const val LONG_PARAGRAPH = 200
}
