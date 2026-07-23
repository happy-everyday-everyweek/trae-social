package com.trae.social.llm.prompt

/**
 * LLM prompt 测试共享人设工厂（#292c）。
 *
 * 统一 [CommentPromptBuilderTest] 与 [TweetPromptBuilderTest] 的 samplePersona 定义，
 * 消除两处默认值不一致（不同职业、不同语言风格）的重复。
 */

/**
 * 构造测试用 [TweetPromptBuilder.PersonaInput]。
 *
 * @param name 显示名，默认 "测试账号"。
 * @param languageStyle 语言风格，默认 "口语"。
 */
fun samplePersona(
    name: String = "测试账号",
    languageStyle: String = "口语",
) = TweetPromptBuilder.PersonaInput(
    displayName = name,
    profession = "程序员",
    ageRange = "25-34",
    culturalBackground = "华东",
    worldview = "代码即诗，简洁是终极复杂",
    values = "实用主义，效率优先",
    languageStyle = languageStyle,
    catchphrase = "破防了",
    emojiPreference = listOf("A", "B"),
    typoRate = 0.03,
    recentMood = "略焦虑",
)
