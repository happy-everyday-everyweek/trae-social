package com.trae.social.app.di

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.profiling.analysis.BasicProfileTrigger
import com.trae.social.core.scheduler.work.TweetGenerationWorker
import com.trae.social.core.scheduler.work.WorkerKeys
import com.trae.social.core.scheduler.work.WorkerPolicies
import com.trae.social.core.scheduler.work.WorkerTags
import com.trae.social.onboarding.ColdStartFiller
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
 * 第七轮 review M4 修复：同时将活跃账号的职业作为初始兴趣种子写入
 * COLD_START_SEEDING 快照（通过 [BasicProfileTrigger.seedColdStartSnapshot]），
 * 使冷启动期 UserProfileReadAccess.coldStartSeeding() 返回非空兴趣向量，
 * TweetGenerationWorker driven 组能注入"用户近期关注话题"提示。
 *
 * IMPL-1/IMPL-45：取代 [com.trae.social.onboarding.DefaultColdStartFiller] 空实现。
 */
@Singleton
class AppColdStartFiller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository,
    private val basicProfileTrigger: BasicProfileTrigger,
) : ColdStartFiller {

    override suspend fun triggerInitialFill() {
        // 主 review 第 1 轮 M2 修复：原外层 runCatching 会吞 CancellationException，
        // 协程取消（如引导页用户离开 / 进程被杀）被误判为 ColdStartFiller 触发失败
        // 并继续走 Timber.w 日志路径。改为 try/catch 显式重抛 CancellationException。
        try {
            val now = System.currentTimeMillis()

            // #100：使用每个账号的时区计算当前小时，而非设备时区，
            // 避免跨时区场景下冷启动活跃账号选择错误
            val allVirtual = accountRepository.getVirtualAccountsList()
            val active = allVirtual.filter { account ->
                // ZoneId.of 是非 suspend 函数，不会抛 CancellationException，
                // 此处 runCatching 仅捕获 ZoneId 解析异常，可保留。
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

            // 第七轮 review M4 修复：将活跃账号的职业作为初始兴趣种子写入冷启动 seeding 快照。
            // 职业作为兴趣 key 直接注入 TweetGenerationWorker 的"用户近期关注话题"提示，
            // LLM 会据此生成贴近用户潜在兴趣的内容，实现冷启动期即个性化。
            // M2 修复：seedColdStartSnapshot 是 suspend，原 runCatching 会吞 CancellationException。
            try {
                val professionInterests = targets.map { it.profession }
                    .filter { it.isNotBlank() }
                    .groupingBy { it }
                    .eachCount()
                    .mapValues { it.value.toDouble() }
                if (professionInterests.isNotEmpty()) {
                    basicProfileTrigger.seedColdStartSnapshot(professionInterests)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "冷启动 seeding 快照写入失败，已忽略")
            }

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
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.w(t, "ColdStartFiller 触发失败")
        }
    }

    private companion object {
        const val MAX_COLD_START_ACCOUNTS = 20
        const val TAG_COLD_START = "cold_start_fill"
    }
}
