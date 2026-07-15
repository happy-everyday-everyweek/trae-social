package com.trae.social.app.di

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.scheduler.work.TweetGenerationWorker
import com.trae.social.core.scheduler.work.WorkerKeys
import com.trae.social.core.scheduler.work.WorkerPolicies
import com.trae.social.core.scheduler.work.WorkerTags
import com.trae.social.onboarding.ColdStartFiller
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ColdStartFiller 真实实现（RISK-14）。
 *
 * 引导完成时选取当前小时活跃的虚拟账号（最多 20 个），为每个入队
 * [TweetGenerationWorker] 立即生成 1 条推文，使主界面进入时已有可见社交动态，
 * 避免空白冷启动体验。
 *
 * 使用 `coldstart_<accountId>_<windowStart>` 作为 deduplicationKey，
 * 配合 tweets 表 unique 索引保证幂等（重复触发不产生重复推文）。
 *
 * IMPL-1/IMPL-45：取代 [com.trae.social.onboarding.DefaultColdStartFiller] 空实现。
 */
@Singleton
class AppColdStartFiller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository,
) : ColdStartFiller {

    override suspend fun triggerInitialFill() {
        runCatching {
            val now = System.currentTimeMillis()

            // #100：使用每个账号的时区计算当前小时，而非设备时区，
            // 避免跨时区场景下冷启动活跃账号选择错误
            val allVirtual = accountRepository.getVirtualAccountsList()
            val active = allVirtual.filter { account ->
                runCatching {
                    val zone = java.time.ZoneId.of(account.timezone)
                    val hour = java.time.ZonedDateTime.now(zone).hour
                    account.activeWindows.getOrNull(hour) == true
                }.getOrDefault(false)
            }
            if (active.isEmpty()) {
                Timber.i("ColdStartFiller: 当前无活跃账号，跳过冷启动填充")
                return
            }
            val targets = active.take(MAX_COLD_START_ACCOUNTS)
            Timber.i("ColdStartFiller: 为 %d 个活跃账号入队即时推文生成", targets.size)

            val workManager = WorkManager.getInstance(context)
            val windowStart = now
            for (account in targets) {
                val deduplicationKey = "coldstart_${account.id}_$windowStart"
                val request = OneTimeWorkRequestBuilder<TweetGenerationWorker>()
                    .setInputData(
                        workDataOf(
                            WorkerKeys.KEY_ACCOUNT_ID to account.id,
                            WorkerKeys.KEY_DEDUP_KEY to deduplicationKey,
                            WorkerKeys.KEY_WINDOW_START to windowStart,
                            WorkerKeys.KEY_SEQUENCE_NO to 0,
                        )
                    )
                    .setConstraints(WorkerPolicies.networkConstraints)
                    .setBackoffCriteria(
                        WorkerPolicies.backoffPolicy,
                        WorkerPolicies.BACKOFF_INITIAL_SECONDS,
                        TimeUnit.SECONDS,
                    )
                    .addTag(WorkerTags.TWEET_GENERATION)
                    .addTag(TAG_COLD_START)
                    .build()
                workManager.enqueue(request)
            }
        }.onFailure { Timber.w(it, "ColdStartFiller 触发失败") }
    }

    private companion object {
        const val MAX_COLD_START_ACCOUNTS = 20
        const val TAG_COLD_START = "cold_start_fill"
    }
}
