package com.trae.social.core.data.di

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.trae.social.core.data.dao.AccountDao
import com.trae.social.core.data.dao.CommentDao
import com.trae.social.core.data.dao.FollowRelationDao
import com.trae.social.core.data.dao.ImageUsageDao
import com.trae.social.core.data.dao.InteractionDao
import com.trae.social.core.data.dao.LlmEndpointDao
import com.trae.social.core.data.dao.SchedulerLogDao
import com.trae.social.core.data.dao.TweetDao
import com.trae.social.core.data.dao.UserActionDao
import com.trae.social.core.data.dao.UserConfigDao
import com.trae.social.core.data.dao.UserProfileDao
import com.trae.social.core.data.dao.UserProfileFeedbackDao
import com.trae.social.core.data.dao.UserProfileOverrideDao
import com.trae.social.core.data.dao.UserProfileRollbackDao
import com.trae.social.core.data.db.ALL_MIGRATIONS
import com.trae.social.core.data.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import javax.inject.Singleton

/**
 * 数据层 Hilt 模块：提供 Database、各 DAO、EncryptedSharedPreferences 的 @Singleton 绑定。
 *
 * Repository 通过 @Inject 构造函数自动注入，无需在此显式 @Provides。
 *
 * #320：Room schema 迁移定义已拆分到 [com.trae.social.core.data.db.DatabaseMigrations]，
 * 本模块仅通过 [ALL_MIGRATIONS] 引用，不再内联 SQL 与 schema 演进说明。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        // IMPL-23：仅降级时破坏性重建；升级走显式 Migration，避免用户数据丢失
        // #320：迁移定义拆分到 DatabaseMigrations.kt，新增 schema 版本时去那里追加
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigrationOnDowngrade()
            .addMigrations(*ALL_MIGRATIONS)
            .build()
    }

    @Provides
    @Singleton
    fun provideAccountDao(db: AppDatabase): AccountDao = db.accountDao()

    @Provides
    @Singleton
    fun provideTweetDao(db: AppDatabase): TweetDao = db.tweetDao()

    @Provides
    @Singleton
    fun provideInteractionDao(db: AppDatabase): InteractionDao = db.interactionDao()

    @Provides
    @Singleton
    fun provideFollowRelationDao(db: AppDatabase): FollowRelationDao = db.followRelationDao()

    @Provides
    @Singleton
    fun provideUserConfigDao(db: AppDatabase): UserConfigDao = db.userConfigDao()

    @Provides
    @Singleton
    fun provideSchedulerLogDao(db: AppDatabase): SchedulerLogDao = db.schedulerLogDao()

    @Provides
    @Singleton
    fun provideImageUsageDao(db: AppDatabase): ImageUsageDao = db.imageUsageDao()

    @Provides
    @Singleton
    fun provideCommentDao(db: AppDatabase): CommentDao = db.commentDao()

    // #146：用户行为建模相关 DAO
    @Provides
    @Singleton
    fun provideUserActionDao(db: AppDatabase): UserActionDao = db.userActionDao()

    @Provides
    @Singleton
    fun provideUserProfileDao(db: AppDatabase): UserProfileDao = db.userProfileDao()

    @Provides
    @Singleton
    fun provideUserProfileOverrideDao(db: AppDatabase): UserProfileOverrideDao = db.userProfileOverrideDao()

    @Provides
    @Singleton
    fun provideUserProfileFeedbackDao(db: AppDatabase): UserProfileFeedbackDao = db.userProfileFeedbackDao()

    @Provides
    @Singleton
    fun provideUserProfileRollbackDao(db: AppDatabase): UserProfileRollbackDao = db.userProfileRollbackDao()

    // #151：LLM 多端点配置 DAO
    @Provides
    @Singleton
    fun provideLlmEndpointDao(db: AppDatabase): LlmEndpointDao = db.llmEndpointDao()

    /**
     * 提供 EncryptedSharedPreferences（RISK-11：API Key 加密存储）。
     * MasterKey 通过 MasterKey.Builder + AES256_GCM，基于 Android Keystore。
     *
     * IMPL-10：Keystore 损坏时 catch 异常，删除损坏的 prefs 文件后重建空实例，
     * 避免 app 启动崩溃。用户需重新配置 API Key（已记录的崩溃风险可接受）。
     *
     * #301 修复：重建仍失败时不再回退到明文 SharedPreferences（会导致 API Key 明文落盘），
     * 改为回退到 [InMemorySharedPreferences]（纯内存，进程结束即消失）。
     * 同时通过 [SecurePrefsAvailability] 暴露降级状态，供 UI 提示用户密钥无法安全持久化。
     *
     * 构建逻辑封装在 [SecurePrefsHolder] 中，保证 [SharedPreferences] 与
     * [SecurePrefsAvailability] 来自同一次构建，状态判定一致。
     */
    @Provides
    @Singleton
    fun provideSecurePrefsHolder(
        @ApplicationContext context: Context
    ): SecurePrefsHolder {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            return SecurePrefsHolder(prefs, degraded = false)
        }.getOrElse { e ->
            Timber.e(e, "EncryptedSharedPreferences 创建失败，尝试重建")
            runCatching {
                context.deleteSharedPreferences(SECURE_PREFS_FILE_NAME)
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val prefs = EncryptedSharedPreferences.create(
                    context,
                    SECURE_PREFS_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                return SecurePrefsHolder(prefs, degraded = false)
            }.getOrElse { e2 ->
                // #301：不再回退明文 SharedPreferences，改用纯内存实现避免密钥落盘
                Timber.e(e2, "EncryptedSharedPreferences 重建仍失败，回退到纯内存 SharedPreferences（密钥不落盘）")
                SecurePrefsHolder(InMemorySharedPreferences(), degraded = true)
            }
        }
        // 理论不可达（runCatching 两条路径均已 return），保留以满足编译器
        return SecurePrefsHolder(InMemorySharedPreferences(), degraded = true)
    }

    @Provides
    @Singleton
    @SecurePreferences
    fun provideEncryptedSharedPreferences(holder: SecurePrefsHolder): SharedPreferences =
        holder.prefs

    /**
     * #301：暴露加密存储可用性。EncryptedSharedPreferences 不可用时为 true，
     * UI（SettingsScreen）据此展示"密钥无法安全存储"警告，避免用户误以为密钥已加密落盘。
     */
    @Provides
    @Singleton
    fun provideSecurePrefsAvailability(holder: SecurePrefsHolder): SecurePrefsAvailability =
        SecurePrefsAvailability(holder.degraded)

    private const val SECURE_PREFS_FILE_NAME = "social_secure_prefs"
}
