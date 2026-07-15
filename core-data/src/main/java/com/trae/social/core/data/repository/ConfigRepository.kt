package com.trae.social.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.core.data.config.LlmProvider
import com.trae.social.core.data.di.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用级 DataStore（非敏感配置）。
 */
private val Context.socialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "social_prefs"
)

/**
 * 配置仓库：统一对外，内部按字段类型路由。
 *
 * - 敏感数据（API Key、Base URL、模型名）：EncryptedSharedPreferences（RISK-11）
 * - 非敏感配置（默认提供商、引导标记、AI 活跃度）：DataStore Preferences
 */
@Singleton
class ConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @SecurePreferences private val secureSharedPreferences: android.content.SharedPreferences
) {

    private val dataStore: DataStore<Preferences> get() = context.socialDataStore

    /**
     * IMPL-48：档位变更事件流。
     *
     * [setAiActivityLevel] 写入 DataStore 后通过此流发出新档位，
     * SchedulerInitializer 收集后以 REPLACE 策略重新入队周期 Worker。
     */
    private val _activityLevelChanges = MutableSharedFlow<AiActivityLevel>(extraBufferCapacity = 1)
    val activityLevelChanges: SharedFlow<AiActivityLevel> = _activityLevelChanges

    // ------------------------------------------------------------------
    // API Key（敏感，EncryptedSharedPreferences）
    // ------------------------------------------------------------------

    suspend fun getApiKey(provider: LlmProvider): String? = withContext(Dispatchers.IO) {
        secureSharedPreferences.getString(apiKeyEntry(provider), null)
    }

    suspend fun setApiKey(provider: LlmProvider, key: String) = withContext(Dispatchers.IO) {
        secureSharedPreferences.edit().putString(apiKeyEntry(provider), key).apply()
    }

    suspend fun getBaseUrl(provider: LlmProvider): String? = withContext(Dispatchers.IO) {
        secureSharedPreferences.getString(baseUrlEntry(provider), null)
    }

    suspend fun setBaseUrl(provider: LlmProvider, baseUrl: String) = withContext(Dispatchers.IO) {
        secureSharedPreferences.edit().putString(baseUrlEntry(provider), baseUrl).apply()
    }

    suspend fun getModelName(provider: LlmProvider): String? = withContext(Dispatchers.IO) {
        secureSharedPreferences.getString(modelEntry(provider), null)
    }

    suspend fun setModelName(provider: LlmProvider, model: String) = withContext(Dispatchers.IO) {
        secureSharedPreferences.edit().putString(modelEntry(provider), model).apply()
    }

    fun apiKeyPreview(provider: LlmProvider): String? {
        val key = secureSharedPreferences.getString(apiKeyEntry(provider), null) ?: return null
        if (key.length <= 8) return "***"
        return key.take(4) + "***" + key.takeLast(4)
    }

    // ------------------------------------------------------------------
    // 默认提供商（DataStore）
    // ------------------------------------------------------------------

    suspend fun getDefaultProvider(): LlmProvider? {
        val id = dataStore.data.map { it[KEY_DEFAULT_PROVIDER] }.first()
        return LlmProvider.fromId(id)
    }

    suspend fun setDefaultProvider(provider: LlmProvider) {
        dataStore.edit { it[KEY_DEFAULT_PROVIDER] = provider.id }
    }

    // ------------------------------------------------------------------
    // 引导标记（DataStore）
    // ------------------------------------------------------------------

    suspend fun isOnboardingCompleted(): Boolean =
        dataStore.data.map { it[KEY_ONBOARDING_COMPLETED] ?: false }.first()

    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    /**
     * IMPL-13：区分"跳过引导"与"完成引导"。
     *
     * 跳过时写入 skipped=true，FeedScreen 顶部展示 banner 提示用户补全 API Key。
     */
    suspend fun isOnboardingSkipped(): Boolean =
        dataStore.data.map { it[KEY_ONBOARDING_SKIPPED] ?: false }.first()

    suspend fun setOnboardingSkipped(skipped: Boolean) {
        dataStore.edit { it[KEY_ONBOARDING_SKIPPED] = skipped }
    }

    // ------------------------------------------------------------------
    // AI 活跃度（DataStore）
    // ------------------------------------------------------------------

    suspend fun getAiActivityLevel(): AiActivityLevel {
        val id = dataStore.data.map { it[KEY_AI_ACTIVITY_LEVEL] }.first()
        return AiActivityLevel.fromId(id) ?: AiActivityLevel.MEDIUM
    }

    suspend fun setAiActivityLevel(level: AiActivityLevel) {
        dataStore.edit { it[KEY_AI_ACTIVITY_LEVEL] = level.id }
        // IMPL-48：通知调度器重新排队
        _activityLevelChanges.tryEmit(level)
    }

    private fun apiKeyEntry(provider: LlmProvider) = "api_key_${provider.id}"
    private fun baseUrlEntry(provider: LlmProvider) = "base_url_${provider.id}"
    private fun modelEntry(provider: LlmProvider) = "model_${provider.id}"

    // ------------------------------------------------------------------
    // 收藏 / 不感兴趣推文 ID 集合（DataStore，#102 / #142）
    // ------------------------------------------------------------------

    /** #102：读取已收藏推文 ID 集合 */
    suspend fun getBookmarkedTweetIds(): Set<String> =
        dataStore.data.map { it[KEY_BOOKMARKED_TWEET_IDS] ?: emptySet() }.first()

    /** #102：写入已收藏推文 ID 集合 */
    suspend fun setBookmarkedTweetIds(ids: Set<String>) {
        dataStore.edit { it[KEY_BOOKMARKED_TWEET_IDS] = ids }
    }

    /** #142：读取不感兴趣推文 ID 集合 */
    suspend fun getNotInterestedTweetIds(): Set<String> =
        dataStore.data.map { it[KEY_NOT_INTERESTED_TWEET_IDS] ?: emptySet() }.first()

    /** #142：写入不感兴趣推文 ID 集合 */
    suspend fun setNotInterestedTweetIds(ids: Set<String>) {
        dataStore.edit { it[KEY_NOT_INTERESTED_TWEET_IDS] = ids }
    }

    // ------------------------------------------------------------------
    // 用户行为建模配置（#146）
    // ------------------------------------------------------------------

    /** 用户画像采集与个性化总开关（默认开启），关闭后 Tracker 停库 + 反哺降级 */
    suspend fun isProfilingEnabled(): Boolean =
        dataStore.data.map { it[KEY_PROFILING_ENABLED] ?: true }.first()

    suspend fun setProfilingEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_PROFILING_ENABLED] = enabled }
    }

    /** 反哺全局灰度比例（默认 1.0，全量生效） */
    suspend fun getFeedbackGrayRatio(): Double =
        dataStore.data.map { it[KEY_FEEDBACK_GRAY_RATIO] ?: DEFAULT_FEEDBACK_GRAY_RATIO }.first()

    suspend fun setFeedbackGrayRatio(ratio: Double) {
        dataStore.edit { it[KEY_FEEDBACK_GRAY_RATIO] = ratio.coerceIn(0.0, 1.0) }
    }

    /** 各反哺场景独立开关（默认全 true，可被用户覆盖强制关闭） */
    suspend fun isScenarioEnabled(scenarioId: Int): Boolean =
        dataStore.data.map { it[scenarioEnabledKey(scenarioId)] ?: true }.first()

    suspend fun setScenarioEnabled(scenarioId: Int, enabled: Boolean) {
        dataStore.edit { it[scenarioEnabledKey(scenarioId)] = enabled }
    }

    /** 原始事件保留天数（默认 14） */
    suspend fun getEventTtlDays(): Int =
        dataStore.data.map { it[KEY_EVENT_TTL_DAYS] ?: DEFAULT_EVENT_TTL_DAYS }.first()

    suspend fun setEventTtlDays(days: Int) {
        dataStore.edit { it[KEY_EVENT_TTL_DAYS] = days.coerceAtLeast(1) }
    }

    /** LLM 画像周期（小时，默认 48，被 AiActivityLevel 覆盖） */
    suspend fun getProfilePeriodHours(): Int =
        dataStore.data.map { it[KEY_PROFILE_PERIOD_HOURS] ?: DEFAULT_PROFILE_PERIOD_HOURS }.first()

    suspend fun setProfilePeriodHours(hours: Int) {
        dataStore.edit { it[KEY_PROFILE_PERIOD_HOURS] = hours.coerceAtLeast(1) }
    }

    /** 用户反馈智能体开关（默认开启） */
    suspend fun isFeedbackAgentEnabled(): Boolean =
        dataStore.data.map { it[KEY_FEEDBACK_AGENT_ENABLED] ?: true }.first()

    suspend fun setFeedbackAgentEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_FEEDBACK_AGENT_ENABLED] = enabled }
    }

    private fun scenarioEnabledKey(scenarioId: Int) =
        booleanPreferencesKey("scenario_enabled_$scenarioId")

    companion object {
        private val KEY_DEFAULT_PROVIDER = stringPreferencesKey("default_provider")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_ONBOARDING_SKIPPED = booleanPreferencesKey("onboarding_skipped")
        private val KEY_AI_ACTIVITY_LEVEL = stringPreferencesKey("ai_activity_level")
        private val KEY_BOOKMARKED_TWEET_IDS = stringSetPreferencesKey("bookmarked_tweet_ids")
        private val KEY_NOT_INTERESTED_TWEET_IDS = stringSetPreferencesKey("not_interested_tweet_ids")

        // #146 用户行为建模配置
        private val KEY_PROFILING_ENABLED = booleanPreferencesKey("profiling_enabled")
        private val KEY_FEEDBACK_GRAY_RATIO = doublePreferencesKey("feedback_gray_ratio")
        private val KEY_EVENT_TTL_DAYS = intPreferencesKey("event_ttl_days")
        private val KEY_PROFILE_PERIOD_HOURS = intPreferencesKey("profile_period_hours")
        private val KEY_FEEDBACK_AGENT_ENABLED = booleanPreferencesKey("feedback_agent_enabled")

        const val DEFAULT_FEEDBACK_GRAY_RATIO = 1.0
        const val DEFAULT_EVENT_TTL_DAYS = 14
        const val DEFAULT_PROFILE_PERIOD_HOURS = 48
        /** 冷启动事件数阈值（常量） */
        const val COLD_START_THRESHOLD = 50
        /** 智能体调用限流（次/小时，常量） */
        const val FEEDBACK_AGENT_RATE_LIMIT_PER_HOUR = 10
        /** 读缓存 TTL（ms，常量） */
        const val PROFILE_CACHE_TTL_MS = 30_000L
        /** 版本库上限（超限删最旧非激活版本，常量） */
        const val MAX_PROFILE_VERSIONS = 100
    }
}
