package com.trae.social.core.profiling.feedback

import com.trae.social.core.data.model.UserProfileSnapshot
import com.trae.social.core.data.model.UserProfileVersion
import com.trae.social.core.profiling.analysis.UserProfileAggregator
import com.trae.social.llm.prompt.UserProfilePromptBuilder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #311：画像版本生成领域服务（core-profiling）。
 *
 * 将原 `UserProfileWorker`（core-scheduler）中的画像领域逻辑下沉到本服务，使调度层 Worker
 * 仅保留"读配置→调服务→返回 Result"的薄编排。该服务聚合如下领域不变量：
 *
 * - 输入指纹 [inputFingerprint]：缓存命中判定，决定是否跳过本次 LLM 调用。
 * - prompt 模板版本哈希 [promptHash]：模板内容变更时递增 [TEMPLATE_VERSION] 使指纹失效。
 * - 新版本实体构造 [buildNewVersion]：聚合 LLM 解析结果 + 指纹 + 主端点 id。
 *
 * 原将这些逻辑放在 `core-scheduler` 的 Worker 内违反 Evans DDD（领域不变量外泄到调度层）与
 * Clean Architecture（SRP）。Worker 因此既冗长又难测（#283 / #281）。
 */
@Singleton
class ProfileGenerationService @Inject constructor() {

    /**
     * 计算输入指纹：hash(snapshot.computedAt + evidence.eventCount + promptHash +
     * activeOverrides + recentMessages + feedbackEffect.scenarioDeltas)。
     *
     * M1 修复（沿用）：剔除 `latestVersion.id`——它是输出而非输入，每次自增会让指纹永远变化，
     * 导致缓存命中短路永远失败，48h 周期 LLM 画像每次重算浪费配额。
     *
     * 命名与 [UserProfileVersion.inputFingerprint] 字段对齐，表达"该值用于与历史版本的
     * `inputFingerprint` 字段比较以判定缓存命中"。
     */
    fun inputFingerprint(
        snapshot: UserProfileSnapshot,
        aggregated: UserProfileAggregator.AggregatedInput,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        val sb = StringBuilder()
        sb.append(snapshot.computedAt).append('|')
        sb.append(snapshot.evidence.eventCount).append('|')
        sb.append(promptHash()).append('|')
        sb.append(aggregated.userFeedback.activeOverrides.joinToString(",") { "${it.type.id}:${it.key}" }).append('|')
        sb.append(aggregated.userFeedback.recentMessages.take(10).joinToString(",") { "${it.role}:${it.createdAt}" }).append('|')
        sb.append(aggregated.feedbackEffect.scenarioDeltas.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" })
        md.update(sb.toString().toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * prompt 模板版本哈希。
     *
     * M4 修复（沿用）：原 `promptHash` 仅哈希 Worker 类名，模板内容变更无法识别；改为
     * [UserProfilePromptBuilder] 类名 + 显式 [TEMPLATE_VERSION]，模板变更时递增该常量即可
     * 让指纹失效，触发重新生成。下沉后类名由 Worker 改为 PromptBuilder，语义更准确——
     * 指纹应反映"prompt 模板版本"而非"调用方类名"。
     */
    fun promptHash(): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("${UserProfilePromptBuilder::class.java.name}#$TEMPLATE_VERSION".toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }.take(8)
    }

    /**
     * 构造新版本实体（`isActive=false`，由 [ProfileVersionStore.insertAndActivate] 在事务内激活）。
     *
     * @param parsed LLM 解析结果
     * @param fingerprint 输入指纹（来自 [inputFingerprint]）
     * @param primaryEndpointId 主端点 id（用于版本溯源；降级链触达次端点时仅作粗粒度标记）
     * @param now 创建时间戳
     */
    fun buildNewVersion(
        parsed: UserProfilePromptBuilder.Result,
        fingerprint: String,
        primaryEndpointId: String,
        now: Long,
    ): UserProfileVersion = UserProfileVersion(
        id = 0,
        identityHypothesis = parsed.identityHypothesis,
        personalityTraits = parsed.personalityTraits,
        contentPreferences = parsed.contentPreferences,
        socialStyle = parsed.socialStyle,
        activityProfile = parsed.activityProfile,
        engagementLevel = parsed.engagementLevel,
        feedbackWeights = parsed.feedbackWeights,
        narrative = parsed.narrative,
        overrideAcknowledgment = parsed.overrideAcknowledgment,
        modelProvider = primaryEndpointId,
        promptHash = promptHash(),
        inputFingerprint = fingerprint,
        snapshotId = null,
        rollbackFrom = null,
        // 由 ProfileVersionStore.insertAndActivate 在事务内统一激活，此处保持 false
        isActive = false,
        createdAt = now,
    )

    private companion object {
        // M4：prompt 模板版本，模板内容变更时递增以使指纹失效
        const val TEMPLATE_VERSION = 1
    }
}
