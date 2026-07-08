package com.trae.social.core.data.di

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.trae.social.core.data.dao.AccountDao
import com.trae.social.core.data.dao.FollowRelationDao
import com.trae.social.core.data.dao.ImageUsageDao
import com.trae.social.core.data.dao.InteractionDao
import com.trae.social.core.data.dao.SchedulerLogDao
import com.trae.social.core.data.dao.TweetDao
import com.trae.social.core.data.dao.UserConfigDao
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
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    /**
     * IMPL-5：v1 → v2，interactions 表新增 (tweetId,accountId,type) 唯一索引。
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_interactions_tweetId_accountId_type` " +
                    "ON `interactions` (`tweetId`, `accountId`, `type`)"
            )
        }
    }

    /**
     * IMPL-16：v2 → v3，accounts 表新增 timezone 列（默认 Asia/Shanghai）。
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE `accounts` ADD COLUMN `timezone` TEXT NOT NULL DEFAULT 'Asia/Shanghai'"
            )
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        // IMPL-23：仅降级时破坏性重建；升级走显式 Migration，避免用户数据丢失
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigrationOnDowngrade()
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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

    /**
     * 提供 EncryptedSharedPreferences（RISK-11：API Key 加密存储）。
     * MasterKey 通过 MasterKey.Builder + AES256_GCM，基于 Android Keystore。
     *
     * IMPL-10：Keystore 损坏时 catch 异常，删除损坏的 prefs 文件后重建空实例，
     * 避免 app 启动崩溃。用户需重新配置 API Key（已记录的崩溃风险可接受）。
     */
    @Provides
    @Singleton
    @SecurePreferences
    fun provideEncryptedSharedPreferences(
        @ApplicationContext context: Context
    ): SharedPreferences {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse { e ->
            Timber.e(e, "EncryptedSharedPreferences 创建失败，尝试重建")
            runCatching {
                context.deleteSharedPreferences(SECURE_PREFS_FILE_NAME)
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    SECURE_PREFS_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.getOrElse { e2 ->
                Timber.e(e2, "EncryptedSharedPreferences 重建仍失败，回退到普通 SharedPreferences")
                context.getSharedPreferences("${SECURE_PREFS_FILE_NAME}_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    private const val SECURE_PREFS_FILE_NAME = "social_secure_prefs"
}
