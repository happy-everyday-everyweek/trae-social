package com.trae.social.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 用户反馈智能体可应用的结构化 Action（白名单）。
 *
 * 智能体只能输出 schema 内的 Action，未知动作忽略；值域校验后由 ProfileAdjuster / 
 * ProfileVersionStore 应用。回滚 Action 需预览确认，不直接应用。
 */
@Serializable
sealed class FeedbackAction {

    @Serializable
    @SerialName("boost_theme")
    data class BoostTheme(val theme: String, val weight: Double) : FeedbackAction()

    @Serializable
    @SerialName("suppress_theme")
    data class SuppressTheme(val theme: String) : FeedbackAction()

    @Serializable
    @SerialName("add_preference")
    data class AddPreference(val preference: String) : FeedbackAction()

    @Serializable
    @SerialName("remove_preference")
    data class RemovePreference(val preference: String) : FeedbackAction()

    @Serializable
    @SerialName("disable_scenario")
    data class DisableScenario(val scenarioId: Int) : FeedbackAction()

    @Serializable
    @SerialName("enable_scenario")
    data class EnableScenario(val scenarioId: Int) : FeedbackAction()

    @Serializable
    @SerialName("correct_narrative")
    data class CorrectNarrative(val correction: String) : FeedbackAction()

    @Serializable
    @SerialName("set_active_hours")
    data class SetActiveHours(val hours: List<Int>) : FeedbackAction()

    /** 回滚到历史画像版本。定位方式三选一：版本号 / 时间点 / 关键词（匹配 narrative）。 */
    @Serializable
    @SerialName("rollback_profile_version")
    data class RollbackProfileVersion(
        val versionId: Long? = null,
        val aroundTimestamp: Long? = null,
        val narrativeKeyword: String? = null
    ) : FeedbackAction()
}

/** 校验单个 Action 的值域，非法值返回 null（白名单 + 值域校验）。 */
fun FeedbackAction.sanitize(): FeedbackAction? = when (this) {
    is FeedbackAction.BoostTheme -> {
        if (theme.isBlank()) null
        else copy(weight = weight.coerceIn(0.0, 1.0))
    }
    is FeedbackAction.SuppressTheme -> if (theme.isBlank()) null else this
    is FeedbackAction.AddPreference -> if (preference.isBlank()) null else this
    is FeedbackAction.RemovePreference -> if (preference.isBlank()) null else this
    is FeedbackAction.DisableScenario -> if (scenarioId !in 1..8) null else this
    is FeedbackAction.EnableScenario -> if (scenarioId !in 1..8) null else this
    is FeedbackAction.CorrectNarrative -> if (correction.isBlank()) null else this
    is FeedbackAction.SetActiveHours -> {
        val valid = hours.filter { it in 0..23 }
        if (valid.isEmpty()) null else copy(hours = valid)
    }
    is FeedbackAction.RollbackProfileVersion -> {
        // 三参数最多一个非空
        val nonNullCount = listOf(versionId, aroundTimestamp, narrativeKeyword).count { it != null }
        if (nonNullCount > 1) null else this
    }
}
