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
import com.trae.social.core.data.config.LlmProtocol
import com.trae.social.core.data.config.LlmProvider
import com.trae.social.core.data.config.ModelCapability
import com.trae.social.core.data.dao.LlmEndpointDao
import com.trae.social.core.data.di.SecurePreferences
import com.trae.social.core.data.entity.LlmEndpointEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
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
    @SecurePreferences private val secureSharedPreferences: android.content.SharedPreferences,
    private val llmEndpointDao: LlmEndpointDao,
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

    /**
     * #151：端点变更事件流。任何端点 CRUD / reorder 后发出 Unit，
     * EndpointRegistry 收集后失效缓存重建 SDK client。
     */
    private val _endpointChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val endpointChanges: SharedFlow<Unit> = _endpointChanges

    private val migrationMutex = Mutex()
    private val migrated = MutableStateFlow(false)

    // ------------------------------------------------------------------
    // 旧 provider 槽位 getter（仅用于 #151 端点迁移读取遗留配置）
    // ------------------------------------------------------------------
    // #151 重构后端点 CRUD 不再走这些方法（改用 listEndpoints / addEndpoint / 等），
    // 但 migrateLegacyProviderConfigsLocked 仍需读取旧的 EncryptedSharedPreferences
    // 槽位，故保留 getter。setter 已随 AppLlmConfigProvider 删除而废弃。

    suspend fun getApiKey(provider: LlmProvider): String? = withContext(Dispatchers.IO) {
        secureSharedPreferences.getString(apiKeyEntry(provider), null)
    }

    suspend fun getBaseUrl(provider: LlmProvider): String? = withContext(Dispatchers.IO) {
        secureSharedPreferences.getString(baseUrlEntry(provider), null)
    }

    suspend fun getModelName(provider: LlmProvider): String? = withContext(Dispatchers.IO) {
        secureSharedPreferences.getString(modelEntry(provider), null)
    }

    // ------------------------------------------------------------------
    // 默认提供商（DataStore）
    // ------------------------------------------------------------------
    // #151 后默认 provider 概念被「orderIndex=0 的主端点」取代，
    // 仅迁移逻辑读取此字段决定哪个端点排到 orderIndex=0。

    suspend fun getDefaultProvider(): LlmProvider? {
        val id = dataStore.data.map { it[KEY_DEFAULT_PROVIDER] }.first()
        return LlmProvider.fromId(id)
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

    // ==================================================================
    // #151：多端点配置（新抽象，取代按 LlmProvider 寻址的旧槽位）
    // ==================================================================

    /**
     * 端点 API Key 在 EncryptedSharedPreferences 中的命名空间。
     * 旧 key 形如 `api_key_openai`，新 key 形如 `api_key_<endpointId>`。
     */
    private fun endpointApiKeyEntry(endpointId: String) = "api_key_ep_$endpointId"

    /**
     * 确保旧 provider 槽位配置已迁移到 `llm_endpoints` 表。
     *
     * 幂等：已迁移过则直接返回。多端点改造的入口前置调用。
     * 旧 key 不立即删除（回滚安全），与端点 endpointApiKeyEntry 命名空间隔离。
     */
    suspend fun migrateLegacyProviderConfigsIfNeeded() {
        if (migrated.value) return
        migrationMutex.withLock {
            if (migrated.value) return
            // 旧迁移标记存在则跳过迁移本身（幂等）
            val alreadyMigrated = dataStore.data
                .map { it[KEY_ENDPOINT_MIGRATION_DONE] ?: false }
                .first()
            if (!alreadyMigrated) {
                migrateLegacyProviderConfigsLocked()
                dataStore.edit { it[KEY_ENDPOINT_MIGRATION_DONE] = true }
            }
            migrated.value = true
        }
    }

    /**
     * 把现有 4 个 provider 槽位的 EncryptedSharedPreferences 配置迁移到 `llm_endpoints` 表。
     *
     * - OPENAI → endpoint(protocol=OPENAI_COMPATIBLE, capabilities=[TEXT, JSON_MODE_NATIVE, VISION_INPUT, STREAMING])
     * - ANTHROPIC → endpoint(protocol=ANTHROPIC_COMPATIBLE, capabilities=[TEXT, STREAMING])
     * - GEMINI → endpoint(protocol=OPENAI_COMPATIBLE, baseUrl=Google OpenAI 兼容端点,
     *   capabilities=[TEXT, JSON_MODE_NATIVE, VISION_INPUT])
     * - CUSTOM → 若有配置则迁移为 OPENAI_COMPATIBLE 端点
     *
     * `default_provider` 决定 orderIndex：默认 provider 的端点 orderIndex=0。
     * 旧 API Key 复制到新 `api_key_ep_<endpointId>` 命名空间，旧 key 保留不删（回滚安全）。
     */
    private suspend fun migrateLegacyProviderConfigsLocked() {
        val defaultProvider = runCatching { getDefaultProvider() }.getOrNull()
        val now = System.currentTimeMillis()
        val endpointsToCreate = mutableListOf<LlmEndpointEntity>()

        // OPENAI / CUSTOM / ANTHROPIC 三个槽位（CUSTOM 不重复与 OPENAI 共存时优先 OPENAI）
        val legacySlots = listOf(LlmProvider.OPENAI, LlmProvider.ANTHROPIC, LlmProvider.GEMINI, LlmProvider.CUSTOM)
        val seenKeys = mutableSetOf<String>()
        for (provider in legacySlots) {
            val apiKey = runCatching { getApiKey(provider) }.getOrNull()
            val baseUrl = runCatching { getBaseUrl(provider) }.getOrNull()
            val model = runCatching { getModelName(provider) }.getOrNull()
            // 没有任何配置的槽位跳过
            if (apiKey.isNullOrBlank() && baseUrl.isNullOrBlank() && model.isNullOrBlank()) continue
            // 同 key 去重（避免 CUSTOM 与 OPENAI 都是同一 key 时建两条）
            if (apiKey != null && !seenKeys.add(apiKey)) continue

            val protocol = when (provider) {
                LlmProvider.OPENAI, LlmProvider.CUSTOM -> LlmProtocol.OPENAI_COMPATIBLE
                LlmProvider.ANTHROPIC -> LlmProtocol.ANTHROPIC_COMPATIBLE
                LlmProvider.GEMINI -> LlmProtocol.OPENAI_COMPATIBLE
            }
            val resolvedBaseUrl = when {
                !baseUrl.isNullOrBlank() -> baseUrl
                provider == LlmProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai/"
                else -> protocol.defaultBaseUrl
            }
            // 枚举式 when：编译器可静态判断 exhaustive，避免条件式 when 必须加 else 分支
            val resolvedModel = when (provider) {
                LlmProvider.OPENAI, LlmProvider.CUSTOM ->
                    if (!model.isNullOrBlank()) model else "gpt-4o-mini"
                LlmProvider.ANTHROPIC ->
                    if (!model.isNullOrBlank()) model else "claude-3-5-sonnet-20240620"
                LlmProvider.GEMINI ->
                    if (!model.isNullOrBlank()) model else "gemini-1.5-flash"
            }
            val capabilities = when (provider) {
                LlmProvider.OPENAI, LlmProvider.CUSTOM -> setOf(
                    ModelCapability.TEXT,
                    ModelCapability.JSON_MODE_NATIVE,
                    ModelCapability.VISION_INPUT,
                    ModelCapability.STREAMING,
                )
                LlmProvider.ANTHROPIC -> setOf(
                    ModelCapability.TEXT,
                    ModelCapability.STREAMING,
                )
                LlmProvider.GEMINI -> setOf(
                    ModelCapability.TEXT,
                    ModelCapability.JSON_MODE_NATIVE,
                    ModelCapability.VISION_INPUT,
                )
            }

            val endpointId = UUID.randomUUID().toString()
            // 复制 API Key 到新命名空间
            if (!apiKey.isNullOrBlank()) {
                withContext(Dispatchers.IO) {
                    secureSharedPreferences.edit()
                        .putString(endpointApiKeyEntry(endpointId), apiKey)
                        .apply()
                }
            }
            endpointsToCreate += LlmEndpointEntity(
                id = endpointId,
                displayName = provider.displayName,
                protocol = protocol.id,
                baseUrl = resolvedBaseUrl,
                model = resolvedModel,
                capabilities = ModelCapability.run { capabilities.toStorageString() },
                orderIndex = endpointsToCreate.size,
                createdAt = now,
                updatedAt = now,
            )
        }

        // 把默认 provider 的端点提到 orderIndex=0
        if (defaultProvider != null && endpointsToCreate.isNotEmpty()) {
            val defaultIdx = endpointsToCreate.indexOfFirst { it.displayName == defaultProvider.displayName }
            if (defaultIdx > 0) {
                val defaultEndpoint = endpointsToCreate.removeAt(defaultIdx)
                endpointsToCreate.add(0, defaultEndpoint)
            }
            // 重排 orderIndex
            endpointsToCreate.forEachIndexed { idx, e ->
                endpointsToCreate[idx] = e.copy(orderIndex = idx)
            }
        }

        // 持久化（已有数据则跳过，避免重复迁移覆盖用户改动）
        val existing = llmEndpointDao.count()
        if (existing == 0 && endpointsToCreate.isNotEmpty()) {
            endpointsToCreate.forEach { llmEndpointDao.upsert(it) }
        }
    }

    /** 列出所有端点，按 orderIndex 升序。 */
    suspend fun listEndpoints(): List<LlmEndpointEntity> {
        migrateLegacyProviderConfigsIfNeeded()
        return llmEndpointDao.listAll()
    }

    /** 观察端点列表变化（UI 用）。 */
    suspend fun observeEndpoints() = llmEndpointDao.observeAll()

    /** 按 id 获取端点。 */
    suspend fun getEndpoint(id: String): LlmEndpointEntity? = llmEndpointDao.getById(id)

    /**
     * 新增端点。orderIndex 自动取当前最大值 + 1。
     * @return 新端点 id
     */
    suspend fun addEndpoint(
        displayName: String,
        protocol: LlmProtocol,
        baseUrl: String,
        model: String,
        capabilities: Set<ModelCapability>,
        apiKey: String? = null,
    ): String {
        migrateLegacyProviderConfigsIfNeeded()
        val now = System.currentTimeMillis()
        val endpointId = UUID.randomUUID().toString()
        val nextOrder = (llmEndpointDao.maxOrderIndex() ?: -1) + 1
        llmEndpointDao.upsert(
            LlmEndpointEntity(
                id = endpointId,
                displayName = displayName.ifBlank { protocol.displayName },
                protocol = protocol.id,
                baseUrl = normalizeBaseUrl(baseUrl),
                model = model,
                capabilities = ModelCapability.run { capabilities.toStorageString() },
                orderIndex = nextOrder,
                createdAt = now,
                updatedAt = now,
            )
        )
        if (!apiKey.isNullOrBlank()) {
            setEndpointApiKey(endpointId, apiKey)
        }
        _endpointChanges.tryEmit(Unit)
        return endpointId
    }

    /**
     * 更新端点元数据（不含 API Key）。
     */
    suspend fun updateEndpoint(
        id: String,
        displayName: String,
        protocol: LlmProtocol,
        baseUrl: String,
        model: String,
        capabilities: Set<ModelCapability>,
    ) {
        val current = llmEndpointDao.getById(id) ?: return
        llmEndpointDao.update(
            current.copy(
                displayName = displayName,
                protocol = protocol.id,
                baseUrl = normalizeBaseUrl(baseUrl),
                model = model,
                capabilities = ModelCapability.run { capabilities.toStorageString() },
                updatedAt = System.currentTimeMillis(),
            )
        )
        _endpointChanges.tryEmit(Unit)
    }

    /** 删除端点（同时清理其 API Key）。 */
    suspend fun deleteEndpoint(id: String) {
        val endpoint = llmEndpointDao.getById(id) ?: return
        llmEndpointDao.delete(endpoint)
        withContext(Dispatchers.IO) {
            secureSharedPreferences.edit().remove(endpointApiKeyEntry(id)).apply()
        }
        _endpointChanges.tryEmit(Unit)
    }

    /**
     * 事务重写端点排序。orderedIds 按新顺序给出 id 列表。
     *
     * 实际重排逻辑封装在 [LlmEndpointDao.reorder] 的 `@Transaction` 默认方法内，
     * 保证 shift + 多次 setOrderIndex 要么全部提交要么全部回滚，
     * 不会因中途失败留下 orderIndex 不一致的中间状态。
     */
    suspend fun reorderEndpoints(orderedIds: List<String>) {
        if (orderedIds.isEmpty()) return
        llmEndpointDao.reorder(orderedIds)
        _endpointChanges.tryEmit(Unit)
    }

    /** 获取端点 API Key（EncryptedSharedPreferences）。 */
    suspend fun getEndpointApiKey(endpointId: String): String? = withContext(Dispatchers.IO) {
        secureSharedPreferences.getString(endpointApiKeyEntry(endpointId), null)
    }

    /** 设置端点 API Key。 */
    suspend fun setEndpointApiKey(endpointId: String, key: String) = withContext(Dispatchers.IO) {
        secureSharedPreferences.edit().putString(endpointApiKeyEntry(endpointId), key).apply()
        _endpointChanges.tryEmit(Unit)
    }

    /**
     * API Key 脱敏预览（UI 用）。
     *
     * #167：原为非 suspend 函数，直接同步访问 EncryptedSharedPreferences（文件 I/O + 解密），
     * 首次解密可能耗时数百毫秒，UI 调用方在主线程触发有 ANR 风险。改为 suspend 并用
     * `withContext(Dispatchers.IO)` 包装，与同文件 [getApiKey] / [getEndpointApiKey] 等保持一致。
     */
    suspend fun endpointApiKeyPreview(endpointId: String): String? = withContext(Dispatchers.IO) {
        val key = secureSharedPreferences.getString(endpointApiKeyEntry(endpointId), null) ?: return@withContext null
        if (key.length <= 8) return@withContext "***"
        key.take(4) + "***" + key.takeLast(4)
    }

    /**
     * 规范化 Base URL：确保以 `http(s)://` 开头、以 `/` 结尾。
     *
     * - 裸域名（如 `api.openai.com`）自动补 `https://` 前缀和 `/` 后缀
     * - 已带 scheme 但无结尾 `/`（如 `https://api.openai.com/v1`）补 `/`
     * - 已规范化的 URL（如 `https://api.openai.com/v1/`）原样返回
     *
     * 与 [LlmEndpointEntity.baseUrl] KDoc 声明的"已规范化为带 scheme 与结尾 /"一致，
     * 入库前必须调用以避免 SDK 拼接路径出错。
     */
    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        // 补 scheme：不以 http:// 或 https:// 开头时默认补 https://
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
        // 补结尾 /
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }

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

        // #151：旧 provider 配置到端点表的迁移幂等标记
        private val KEY_ENDPOINT_MIGRATION_DONE = booleanPreferencesKey("endpoint_migration_done")

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
