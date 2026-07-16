package com.trae.social.core.profiling.analysis

import com.trae.social.core.data.model.BrowseDepth
import com.trae.social.core.data.model.HourEvidence
import com.trae.social.core.data.model.InteractionTendency
import com.trae.social.core.data.model.Periodicity
import com.trae.social.core.data.model.PostingCadence
import com.trae.social.core.data.model.ProfileConfidence
import com.trae.social.core.data.model.ProfileEvidence
import com.trae.social.core.data.model.SocialStyle
import com.trae.social.core.data.model.ThemeEvidence
import com.trae.social.core.data.model.UserActionEvent
import com.trae.social.core.data.model.UserActionType
import com.trae.social.core.data.model.UserProfileSnapshot
import com.trae.social.core.profiling.mapping.ProfileMappers
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * 基础分析层（#146 第二层）：纯函数，无副作用。
 *
 * 算法维度：活跃时段、内容偏好向量、互动倾向、浏览深度、发帖节奏、社交风格、周期性特征。
 * 每维度附置信度；异常剔除（IQR / 频率异常）；指数时间衰减（半衰期 7 天）；
 * 增量合并（decay(old) ⊕ aggregate(new)）。
 *
 * 作为业务侧高频读取源与 LLM 深度画像层输入。
 */
object BasicProfileAnalyzer {

    /** 半衰期 7 天（与事件 TTL 14 天协调）。 */
    const val HALF_LIFE_DAYS = 7.0
    const val MS_PER_DAY = 24.0 * 60 * 60 * 1000.0

    /** textTopic 权重提升倍数（用户主动表达的主题比被动浏览的图片主题更能反映真实兴趣）。 */
    private const val TEXT_TOPIC_BOOST = 1.5

    /** textTopics 次要主题权重系数（辅助信号，降权）。 */
    private const val TEXT_TOPICS_WEIGHT = 0.5

    /**
     * 互动事件权重（view=1, like=3, comment=5, retweet=4, bookmark=6, dwell>5s +2, publish=6）。
     *
     * 单一权重表：兴趣向量计算（[computeInterestVector]）与时间衰减基数（[baseWeight]）
     * 共用同一份数值，避免两张表手改漏同步导致语义分歧（见 PR #150 review Q2）。
     */
    private val actionWeights = mapOf(
        UserActionType.TWEET_VIEW to 1.0,
        UserActionType.TWEET_LIKE to 3.0,
        UserActionType.TWEET_COMMENT to 5.0,
        UserActionType.TWEET_RETWEET to 4.0,
        UserActionType.TWEET_BOOKMARK to 6.0,
        UserActionType.TWEET_DWELL to 2.0,
        UserActionType.PUBLISH_TWEET to 6.0,
        UserActionType.FOLLOW to 3.0,
        UserActionType.SESSION_START to 1.0,
        UserActionType.SESSION_END to 1.0,
        UserActionType.SCREEN_ENTER to 1.0,
        UserActionType.SCREEN_LEAVE to 1.0,
    )

    /** 回退权重：未在 [actionWeights] 中显式列出的动作类型。 */
    private const val FALLBACK_WEIGHT = 0.5

    /**
     * 全量分析：对 [events]（按 occurredAt 排序）计算画像快照。
     *
     * @param previous 前一份快照，用于增量合并（null 表示首次全量）。
     * @param now 当前时间戳。
     */
    fun analyze(
        events: List<UserActionEvent>,
        previous: UserProfileSnapshot?,
        now: Long,
    ): UserProfileSnapshot {
        val sorted = events.sortedBy { it.occurredAt }
        val windowStart = sorted.firstOrNull()?.occurredAt ?: now
        val windowEnd = sorted.lastOrNull()?.occurredAt ?: now

        // 异常剔除：对停留时长做 IQR 剔除，标记但不删除
        val dwellMs = sorted.mapNotNull { e ->
            if (e.type == UserActionType.TWEET_DWELL) e.durationMs else null
        }
        val (filteredDwell, anomalyCount) = filterIqr(dwellMs)

        // 时间衰减权重
        val weighted = sorted.map { it to weight(it, now) }

        val activeHours = computeActiveHours(weighted)
        val interestVector = computeInterestVector(weighted)
        val interactionTendency = computeInteractionTendency(sorted)
        val browseDepth = computeBrowseDepth(sorted, filteredDwell)
        val postingCadence = computePostingCadence(sorted, now)
        val socialStyle = computeSocialStyle(sorted)
        val periodicity = computePeriodicity(sorted)
        val confidence = computeConfidence(sorted, now)
        val evidence = buildEvidence(sorted, anomalyCount, interestVector, activeHours)

        val aggregated = UserProfileSnapshot(
            activeHours = activeHours.keys.sortedByDescending { activeHours[it] ?: 0.0 }.take(3),
            interestVector = interestVector,
            interactionTendency = interactionTendency,
            browseDepth = browseDepth,
            postingCadence = postingCadence,
            socialStyle = socialStyle,
            periodicity = periodicity,
            confidence = confidence,
            evidence = evidence,
            computedAt = now,
            eventWindowStart = windowStart,
            eventWindowEnd = windowEnd,
        )

        return if (previous != null) mergeIncremental(previous, aggregated, now) else aggregated
    }

    /**
     * 指数衰减：旧快照各累加量按 0.5^(Δt/7) 整体衰减。
     */
    fun decay(snapshot: UserProfileSnapshot, deltaMs: Long): UserProfileSnapshot {
        if (deltaMs <= 0) return snapshot
        val factor = 0.5.pow(deltaMs / (HALF_LIFE_DAYS * MS_PER_DAY))
        return snapshot.copy(
            interestVector = snapshot.interestVector.mapValues { it.value * factor },
            confidence = snapshot.confidence.copy(
                interestVector = (snapshot.confidence.interestVector * factor),
                interactionTendency = (snapshot.confidence.interactionTendency * factor),
            ),
            computedAt = snapshot.computedAt + deltaMs,
        )
    }

    /**
     * 增量合并：decay(oldSnapshot, Δt) ⊕ aggregate(newEvents)，同维度相加后重新归一化。
     */
    private fun mergeIncremental(
        old: UserProfileSnapshot,
        newAgg: UserProfileSnapshot,
        now: Long,
    ): UserProfileSnapshot {
        val deltaMs = (now - old.computedAt).coerceAtLeast(0L)
        val decayed = decay(old, deltaMs)
        val mergedInterest = (decayed.interestVector.asSequence() + newAgg.interestVector.asSequence())
            .groupingBy { it.key }
            .fold(0.0) { acc, v -> acc + v.value }
            .let { normalize(it) }
        val mergedHours = mergeHourWeights(hourWeights(decayed), hourWeights(newAgg))
        return newAgg.copy(
            interestVector = mergedInterest,
            activeHours = mergedHours.entries.sortedByDescending { it.value }.take(3).map { it.key },
            evidence = newAgg.evidence.copy(
                eventCount = old.evidence.eventCount + newAgg.evidence.eventCount,
                anomalyCount = old.evidence.anomalyCount + newAgg.evidence.anomalyCount,
            ),
            confidence = mergeConfidence(decayed.confidence, newAgg.confidence),
            eventWindowStart = minOf(decayed.eventWindowStart, newAgg.eventWindowStart),
            eventWindowEnd = maxOf(decayed.eventWindowEnd, newAgg.eventWindowEnd),
        )
    }

    // ---- 权重 ----

    private fun weight(event: UserActionEvent, now: Long): Double {
        val base = baseWeight(event.type)
        if (base <= 0.0) return 0.0
        val ageDays = (now - event.occurredAt).coerceAtLeast(0L) / MS_PER_DAY
        return base * 0.5.pow(ageDays / HALF_LIFE_DAYS)
    }

    private fun baseWeight(type: UserActionType): Double =
        actionWeights[type] ?: FALLBACK_WEIGHT

    // ---- 活跃时段 ----

    private fun computeActiveHours(weighted: List<Pair<UserActionEvent, Double>>): Map<Int, Double> {
        val buckets = IntArray(24)
        weighted.forEach { (e, w) ->
            val hour = epochToHour(e.occurredAt)
            if (hour in 0..23) buckets[hour] += w.toInt().coerceAtLeast(0)
        }
        return (0..23).associateWith { buckets[it].toDouble() }.filterValues { it > 0 }
    }

    private fun hourWeights(snapshot: UserProfileSnapshot): Map<Int, Double> {
        // 无显式 hour 权重存储，用 activeHours 顺序近似权重（Top1=3, Top2=2, Top3=1）
        return snapshot.activeHours.mapIndexed { idx, h -> h to (3.0 - idx).coerceAtLeast(0.5) }.toMap()
    }

    private fun mergeHourWeights(a: Map<Int, Double>, b: Map<Int, Double>): Map<Int, Double> =
        (a.asSequence() + b.asSequence()).groupingBy { it.key }.fold(0.0) { acc, v -> acc + v.value }

    // ---- 内容偏好向量 ----

    /**
     * 兴趣向量计算：融合 imageTheme（预打标主题，被动消费信号）与
     * textTopic / textTopics（LLM 预解析主题，主动表达信号）。
     *
     * textTopic 权重提升 [TEXT_TOPIC_BOOST] 倍（用户主动写出的主题比被动浏览
     * 的图片主题更能反映真实兴趣），textTopics 次要主题权重降为 [TEXT_TOPICS_WEIGHT]。
     *
     * 第二轮 review Minor 6 修复:Q2 合并两表后,w 已含 `actionWeights[type] * timeDecay`,
     * 再乘 `actionWeights[type]` 会让权重平方化(PUBLISH_TWEET 实际贡献为 6.0² * decay = 36 * decay,
     * FOLLOW 跃升至 9 * decay)。直接用 w 作为基础贡献,避免类型权重被应用两次;
     * TEXT_TOPIC_BOOST / TEXT_TOPICS_WEIGHT 是对文本信号的额外提升/降权,保留。
     */
    private fun computeInterestVector(weighted: List<Pair<UserActionEvent, Double>>): Map<String, Double> {
        val raw = HashMap<String, Double>()
        weighted.forEach { (e, w) ->
            // 1. imageTheme（预打标主题，被动消费信号）
            ProfileMappers.readExtraString(e.extra, "imageTheme")?.let { theme ->
                raw[theme] = (raw[theme] ?: 0.0) + w
            }
            // 2. textTopic（LLM 预解析主主题，主动表达信号，权重提升）
            ProfileMappers.readExtraString(e.extra, "textTopic")?.let { topic ->
                raw[topic] = (raw[topic] ?: 0.0) + w * TEXT_TOPIC_BOOST
            }
            // 3. textTopics（LLM 预解析次要主题，辅助信号）
            ProfileMappers.readExtraStringList(e.extra, "textTopics").forEach { topic ->
                raw[topic] = (raw[topic] ?: 0.0) + w * TEXT_TOPICS_WEIGHT
            }
        }
        return normalize(raw)
    }

    // ---- 互动倾向 ----

    private fun computeInteractionTendency(events: List<UserActionEvent>): InteractionTendency {
        val viewed = events.count { it.type == UserActionType.TWEET_VIEW }.coerceAtLeast(1)
        return InteractionTendency(
            likeRate = events.count { it.type == UserActionType.TWEET_LIKE }.toDouble() / viewed,
            commentRate = events.count { it.type == UserActionType.TWEET_COMMENT }.toDouble() / viewed,
            retweetRate = events.count { it.type == UserActionType.TWEET_RETWEET }.toDouble() / viewed,
            bookmarkRate = events.count { it.type == UserActionType.TWEET_BOOKMARK }.toDouble() / viewed,
        )
    }

    // ---- 浏览深度 ----

    private fun computeBrowseDepth(events: List<UserActionEvent>, filteredDwell: List<Long>): BrowseDepth {
        val avgDwell = filteredDwell.takeIf { it.isNotEmpty() }?.average()?.toLong() ?: 0L
        val sessionIds = events.map { it.session }.toSet()
        val tweetsPerSession = sessionIds.takeIf { it.isNotEmpty() }
            ?.let { events.count { e -> e.type == UserActionType.TWEET_VIEW }.toDouble() / it.size } ?: 0.0
        return BrowseDepth(avgDwellMs = avgDwell, tweetsPerSession = tweetsPerSession, scrollDepthRatio = 0.0)
    }

    // ---- 发帖节奏 ----

    private fun computePostingCadence(events: List<UserActionEvent>, now: Long): PostingCadence {
        val posts = events.filter { it.type == UserActionType.PUBLISH_TWEET }
        val hours = posts.map { epochToHour(it.occurredAt) }.distinct().sorted()
        val avgCaption = posts.mapNotNull { ProfileMappers.readExtraInt(it.extra, "captionLen") }
            .takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 0
        val avgImages = posts.mapNotNull { ProfileMappers.readExtraInt(it.extra, "imageCount") }
            .takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val spanDays = if (posts.isEmpty()) 1.0 else max(1.0, (now - posts.minOf { it.occurredAt }) / MS_PER_DAY)
        return PostingCadence(
            postFrequency = posts.size / spanDays,
            postingHours = hours,
            avgCaptionLength = avgCaption,
            avgImageCount = avgImages,
        )
    }

    // ---- 社交风格 ----

    private fun computeSocialStyle(events: List<UserActionEvent>): SocialStyle {
        val follows = events.count { it.type == UserActionType.FOLLOW }
        val views = events.count { it.type == UserActionType.TWEET_VIEW }.coerceAtLeast(1)
        val interactions = events.count {
            it.type == UserActionType.TWEET_LIKE || it.type == UserActionType.TWEET_COMMENT
        }
        // 互动延迟分布（相邻互动事件时间差均值）
        val interactionTimes = events.filter {
            it.type == UserActionType.TWEET_LIKE || it.type == UserActionType.TWEET_COMMENT
        }.map { it.occurredAt }.sorted()
        val avgDelay = if (interactionTimes.size < 2) 0L else
            interactionTimes.zipWithNext { a, b -> b - a }.average().toLong()
        return SocialStyle(
            activeFollowRatio = follows.toDouble() / views,
            avgInteractionDelayMs = avgDelay,
        )
    }

    // ---- 周期性 ----

    private fun computePeriodicity(events: List<UserActionEvent>): Periodicity {
        val byDayType = events.groupBy { isWeekend(it.occurredAt) }
        val weekdayScore = byDayType[false]?.size?.toDouble() ?: 0.0
        val weekendScore = byDayType[true]?.size?.toDouble() ?: 0.0
        val total = weekdayScore + weekendScore
        return if (total == 0.0) {
            Periodicity(0.0, 0.0, false)
        } else {
            Periodicity(weekdayScore / total, weekendScore / total, weekendScore > weekdayScore)
        }
    }

    // ---- 置信度 ----

    private fun computeConfidence(events: List<UserActionEvent>, now: Long): ProfileConfidence {
        val viewed = events.count { it.type == UserActionType.TWEET_VIEW }
        val dwell = events.count { it.type == UserActionType.TWEET_DWELL }
        val posts = events.count { it.type == UserActionType.PUBLISH_TWEET }
        val interactions = events.count {
            it.type == UserActionType.TWEET_LIKE || it.type == UserActionType.TWEET_COMMENT ||
                it.type == UserActionType.TWEET_RETWEET || it.type == UserActionType.TWEET_BOOKMARK
        }
        val distinctDays = events.map { epochToDay(it.occurredAt) }.toSet().size
        return ProfileConfidence(
            activeHours = (distinctDays / 7.0).coerceIn(0.0, 1.0),
            interestVector = (viewed / 30.0).coerceIn(0.0, 1.0),
            interactionTendency = (viewed / 30.0).coerceIn(0.0, 1.0),
            browseDepth = (dwell / 20.0).coerceIn(0.0, 1.0),
            postingCadence = (posts / 5.0).coerceIn(0.0, 1.0),
            socialStyle = (interactions / 10.0).coerceIn(0.0, 1.0),
            periodicity = (distinctDays / 14.0).coerceIn(0.0, 1.0),
        )
    }

    private fun mergeConfidence(a: ProfileConfidence, b: ProfileConfidence): ProfileConfidence =
        ProfileConfidence(
            activeHours = max(a.activeHours, b.activeHours),
            interestVector = max(a.interestVector, b.interestVector),
            interactionTendency = max(a.interactionTendency, b.interactionTendency),
            browseDepth = max(a.browseDepth, b.browseDepth),
            postingCadence = max(a.postingCadence, b.postingCadence),
            socialStyle = max(a.socialStyle, b.socialStyle),
            periodicity = max(a.periodicity, b.periodicity),
        )

    // ---- 异常剔除（IQR）----

    private fun filterIqr(values: List<Long>): Pair<List<Long>, Int> {
        if (values.size < 4) return values to 0
        val sorted = values.sorted()
        val q1 = sorted[sorted.size / 4]
        val q3 = sorted[sorted.size * 3 / 4]
        val iqr = q3 - q1
        val lower = q1 - 1.5 * iqr
        val upper = q3 + 1.5 * iqr
        // B3 修复：it 为 Long，lower/upper 为 Double，需转 Double 后再比较，否则类型不匹配编译失败
        val filtered = sorted.filter { it.toDouble() in lower..upper }
        return filtered to (sorted.size - filtered.size)
    }

    // ---- 证据链 ----

    private fun buildEvidence(
        events: List<UserActionEvent>,
        anomalyCount: Int,
        interest: Map<String, Double>,
        activeHours: Map<Int, Double>,
    ): ProfileEvidence {
        val topThemes = interest.entries.sortedByDescending { it.value }
            .take(5)
            .map { (theme, w) ->
                val viewed = events.count { e ->
                    matchesTheme(e, theme) && e.type == UserActionType.TWEET_VIEW
                }
                val interactions = events.count { e ->
                    matchesTheme(e, theme) &&
                        e.type in setOf(
                            UserActionType.TWEET_LIKE, UserActionType.TWEET_COMMENT,
                            UserActionType.TWEET_RETWEET, UserActionType.TWEET_BOOKMARK,
                        )
                }
                ThemeEvidence(theme, w, viewed, interactions)
            }
        val topHours = activeHours.entries.sortedByDescending { it.value }
            .take(3)
            .map { (h, w) -> HourEvidence(h, w.toInt(), w) }
        val sampleTweetIds = events.mapNotNull { it.targetId }
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)
        return ProfileEvidence(
            eventCount = events.size,
            anomalyCount = anomalyCount,
            topThemes = topThemes,
            topActiveHours = topHours,
            sampleTweetIds = sampleTweetIds,
        )
    }

    // ---- 工具 ----

    /**
     * 判断事件是否匹配指定主题：检查 imageTheme（预打标）或 textTopic（LLM 预解析）。
     * 用于证据链统计，使文本主题也能出现在 ThemeEvidence 中。
     */
    private fun matchesTheme(e: UserActionEvent, theme: String): Boolean {
        ProfileMappers.readExtraString(e.extra, "imageTheme")?.let { if (it == theme) return true }
        ProfileMappers.readExtraString(e.extra, "textTopic")?.let { if (it == theme) return true }
        return false
    }

    private fun normalize(map: Map<String, Double>): Map<String, Double> {
        val sum = map.values.sum()
        return if (sum <= 0.0) map else map.mapValues { it.value / sum }
    }

    private fun epochToHour(ts: Long): Int {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        return cal.get(java.util.Calendar.HOUR_OF_DAY)
    }

    private fun epochToDay(ts: Long): String {
        val date = java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return date.toString()
    }

    private fun isWeekend(ts: Long): Boolean {
        val day = java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault()).dayOfWeek.value
        return day >= 6 // Saturday=6, Sunday=7
    }
}

/** 信息熵（用于主题多样性最小熵约束）。 */
fun entropy(values: List<Double>): Double {
    val sum = values.sum()
    if (sum <= 0.0) return 0.0
    return values.sumOf { p ->
        val ratio = p / sum
        if (ratio <= 0.0) 0.0 else -ratio * ln(ratio)
    }
}
