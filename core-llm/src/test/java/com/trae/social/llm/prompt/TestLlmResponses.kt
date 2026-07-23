package com.trae.social.llm.prompt

/**
 * LLM 响应 JSON 测试夹具（#292b）。
 *
 * 集中存放各 PromptBuilder 测试共用的标准 LLM 响应 JSON 字面量，避免在多个测试文件中
 * 重复内联相同结构的 JSON 字符串。仅提取标准成功样例与降级样例，单测专属的边界 JSON
 * （字段缺失、越界 index、超界值等）保留在各测试内联。
 */

/** 标准推文生成响应 JSON（parseTweetResult 成功路径样例）。 */
const val validTweetJson =
    """{"text": "今天天气真不错", "withImage": true, "imageTheme": "landscape", "interactionTendency": 0.8}"""

/** 标准评论生成响应 JSON 数组（parseCommentResults 成功路径样例）。 */
const val validCommentJson =
    """[{"commenterIndex": 0, "text": "说得好", "type": "COMMENT"}, """ +
        """{"commenterIndex": 1, "text": "", "type": "LIKE"}, """ +
        """{"commenterIndex": 2, "text": "", "type": "RETWEET"}]"""

/** 标准人设更新响应 JSON（parsePersonaUpdate 成功路径样例）。 */
const val validPersonaUpdateJson =
    """{"lifeStory": "新经历", "workInfo": "新工作", "mood": "新情绪"}"""

/** 非 JSON 纯文本响应（解析失败降级路径样例，多个 PromptBuilder 测试共用）。 */
const val malformedJson = "这不是 JSON"
