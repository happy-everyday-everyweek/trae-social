package com.trae.social.llm.prompt

/**
 * 内容合规过滤器。
 *
 * 内置常见敏感词集合，提供检测与打码两种能力，作为 LLM 输出合规的最后兜底
 * （RISK-12）。Prompt 中已要求模型自检，本类在应用层再校验一次。
 *
 * 注意：敏感词集合为示例性质，生产环境应替换为可热更新的词库。
 */
class ContentFilter {

    /**
     * 内置敏感词集合（按字符匹配，大小写不敏感）。
     * 涵盖暴力、仇恨、色情、政治敏感等几类常见违规词。
     */
    private val sensitiveWords: Set<String> = setOf(
        // 暴力类
        "杀人", "杀戮", "屠杀", "炸弹", "爆炸物", "枪击", "持枪", "恐怖袭击",
        "恐怖主义", "极端组织", "斩首", "灭族", "血洗",
        // 仇恨言论类
        "种族歧视", "仇恨言论", "纳粹", "法西斯", "排华", "辱华", "白人至上",
        "黑鬼", "黄祸", "畜生不如",
        // 色情类
        "色情", "淫秽", "裸体", "性交易", "卖淫", "嫖娼", "强奸", "猥亵儿童",
        "恋童癖", "成人视频", "一夜情",
        // 涉政敏感类
        "颠覆国家", "分裂国家", "反动", "政变", "刺杀领袖", "藏独", "疆独",
        "台独", "港独",
        // 毒品类
        "毒品交易", "贩毒", "吸毒", "海洛因", "冰毒", "大麻", "可卡因",
        "摇头丸", "迷药",
        // 诈骗违法类
        "诈骗", "洗钱", "贿赂", "行贿", "受贿", "偷渡", "走私", "伪造货币",
        "倒卖器官",
        // 自残自杀类
        "自杀方法", "自残", "割腕", "上吊", "跳楼", "服毒自杀", "安乐死指南",
        // 网络违规类
        "钓鱼网站", "木马程序", "黑客攻击", "盗号", "撞库", "勒索病毒",
        "传播病毒", "生物武器", "化学武器",
        // 其他
        "邪教", "传销", "非法集资", "绑架", "勒索", "敲诈",
    )

    /**
     * 否定/防御前缀表。
     *
     * IMPL-29：当敏感词前紧邻这些前缀时，视为合法讨论（如"反诈骗""防勒索""打击走私"），
     * 不应误判为敏感内容。
     *
     * P2 修复：支持多字符前缀（如"打击"），原实现仅检查单字符前缀导致
     * "打击走私"被误判（"击"不在单字符前缀表中，而"打"虽在表中但
     * "打击走私"中"走私"前一个字符是"击"）。
     */
    private val negationPrefixes: List<String> = listOf(
        // 单字符前缀
        "反", "防", "拒", "不", "非", "抗", "治", "阻", "避",
        // 双字符前缀
        "打击", "拒绝", "反对", "防止", "抵制",
        // 补充常见否定/防御词组（#125）
        "不要", "没有", "预防", "消除", "远离", "杜绝", "严惩",
        "惩治", "治理", "抵抗", "抗击", "避免", "不畏",
    )

    /**
     * 判断指定位置的匹配是否处于否定前缀上下文。
     *
     * P2 修复：遍历 [negationPrefixes]，检查匹配位置前是否紧邻任一前缀。
     */
    private fun isNegationContext(text: String, start: Int): Boolean {
        for (prefix in negationPrefixes) {
            val prefixLen = prefix.length
            if (start < prefixLen) continue
            val actualPrefix = text.substring(start - prefixLen, start)
            if (actualPrefix == prefix) return true
        }
        return false
    }

    /**
     * 判断文本是否包含敏感词。
     *
     * 大小写不敏感；命中任一敏感词即返回 true。
     * IMPL-29：排除"反/防/打击"等否定前缀上下文，避免"反诈骗"等合法讨论误伤。
     */
    fun containsSensitiveContent(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        for (word in sensitiveWords) {
            val w = word.lowercase()
            var idx = lower.indexOf(w)
            while (idx >= 0) {
                if (!isNegationContext(lower, idx)) return true
                idx = lower.indexOf(w, idx + w.length)
            }
        }
        return false
    }

    /**
     * 将文本中的敏感词打码为等长星号。
     *
     * 不会修改不含敏感词的文本；大小写不敏感匹配但保留原文本大小写。
     * 若多个敏感词重叠或相邻，按首次出现位置合并区间后统一替换。
     * IMPL-29：排除"反/防/打击"等否定前缀上下文。
     */
    fun maskSensitive(text: String): String {
        if (text.isBlank()) return text
        val lower = text.lowercase()
        // 收集所有命中区间（IntRange 为闭区间 [start, end]）。
        val ranges = mutableListOf<IntRange>()
        for (word in sensitiveWords) {
            val w = word.lowercase()
            var idx = lower.indexOf(w)
            while (idx >= 0) {
                if (!isNegationContext(lower, idx)) {
                    ranges.add(idx..(idx + w.length - 1))
                }
                idx = lower.indexOf(w, idx + w.length)
            }
        }
        if (ranges.isEmpty()) return text
        // 合并重叠或相邻区间。
        ranges.sortBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (r in ranges) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, r.last)
            } else {
                merged.add(r)
            }
        }
        // 按合并后区间替换为星号。
        val sb = StringBuilder(text.length)
        var cursor = 0
        for (r in merged) {
            if (r.first > cursor) sb.append(text, cursor, r.first)
            val len = r.last - r.first + 1
            repeat(len) { sb.append('*') }
            cursor = r.last + 1
        }
        if (cursor < text.length) sb.append(text, cursor, text.length)
        return sb.toString()
    }
}
