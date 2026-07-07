package com.trae.social.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.core.data.config.LlmProvider
import com.trae.social.core.data.di.SecurePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
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
    }

    private fun apiKeyEntry(provider: LlmProvider) = "api_key_${provider.id}"
    private fun baseUrlEntry(provider: LlmProvider) = "base_url_${provider.id}"
    private fun modelEntry(provider: LlmProvider) = "model_${provider.id}"

    companion object {
        private val KEY_DEFAULT_PROVIDER = stringPreferencesKey("default_provider")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_ONBOARDING_SKIPPED = booleanPreferencesKey("onboarding_skipped")
        private val KEY_AI_ACTIVITY_LEVEL = stringPreferencesKey("ai_activity_level")
    }
}
