package com.trae.social.core.data.di

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
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
import javax.inject.Singleton

/**
 * 数据层 Hilt 模块：提供 Database、各 DAO、EncryptedSharedPreferences 的 @Singleton 绑定。
 *
 * Repository 通过 @Inject 构造函数自动注入，无需在此显式 @Provides。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        // 开发期使用 fallbackToDestructiveMigration；发布版须替换为显式 Migration（RISK-9）
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
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
     */
    @Provides
    @Singleton
    @SecurePreferences
    fun provideEncryptedSharedPreferences(
        @ApplicationContext context: Context
    ): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private const val SECURE_PREFS_FILE_NAME = "social_secure_prefs"
}
