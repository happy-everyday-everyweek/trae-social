package com.trae.social.core.scheduler

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.trae.social.core.data.config.AiActivityLevel
import com.trae.social.core.data.dao.SchedulerLogDao
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.ConfigRepository
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.core.scheduler.rule.DeduplicationKeys
import com.trae.social.core.scheduler.rule.ScheduleRule
import com.trae.social.core.scheduler.rule.ScheduleRuleResolver
import com.trae.social.core.scheduler.work.WorkerPolicies
import com.trae.social.core.scheduler.work.WorkerTags
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 调度器初始化入口（SubTask 8.5）。
 *
 * 由 [com.trae.social.app.SocialApp.onCreate] 调用，完成：
 * 1. 启动 [SchedulerForegroundService]；
 * 2. 调度恢复：扫描所有虚拟账号的 [ScheduleRuleResolver.missedWindows]（自上次运行起），
 *    为错过的活跃窗补发推文（每窗最多补 1 条，避免轰炸）；
 * 3. 入队下一批 TweetGenerationWorker（基于 [ScheduleRuleResolver.nextTriggerTime]）；
 * 4. 入队 PendingInteractionWorker（PeriodicWorkRequest，15 分钟周期）；
 * 5. 入队 PersonaUpdateWorker（PeriodicWorkRequest，7 天周期）。
 *
 * RISK-3（后台调度）：通过前台服务 + 调度恢复 + BootReceiver 保证调度不中断。
 */
object SchedulerInitializer {

    private val schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Hilt EntryPoint：在非 Hilt 创建的对象中访问依赖。
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SchedulerEntryPoint {
        fun accountRepository(): AccountRepository
        fun tweetRepository(): TweetRepository
        fun configRepository(): ConfigRepository
        fun schedulerLogDao(): SchedulerLogDao
    }

    /**
     * 初始化调度器。在主线程外的 IO 上下文中执行更佳，但 SocialApp.onCreate 中调用
     * 也可接受（Hilt 已完成依赖注入）。
     */
    fun initialize(app: Application) {
        val entryPoint = EntryPointAccessors.fromApplication(
            app,
            SchedulerEntryPoint::class.java,
        )
        val accountRepository = entryPoint.accountRepository()
        val tweetRepository = entryPoint.tweetRepository()
        val configRepository = entryPoint.configRepository()
        val logDao = entryPoint.schedulerLogDao()
        val workManager = WorkManager.getInstance(app)

        // 1. 启动前台服务
        startForegroundService(app)

        // 2-5. 调度恢复与周期任务入队
        // 使用 IO 协程执行，避免阻塞 onCreate
        schedulerScope.launch {
            try {
                scheduleRecoveryAndRoutine(
                    accountRepository = accountRepository,
                    tweetRepository = tweetRepository,
                    configRepository = configRepository,
                    logDao = logDao,
                    workManager = workManager,
                )
            } catch (t: Throwable) {
                Timber.e(t, "调度器初始化失败")
            }
        }
    }

    /**
     * 启动前台服务（Android O+ 需通过 startForegroundService）。
     */
    private fun startForegroundService(app: Application) {
        val intent = Intent(app, SchedulerForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    /**
     * 执行调度恢复与周期任务入队。
     */
    private suspend fun scheduleRecoveryAndRoutine(
        accountRepository: AccountRepository,
        tweetRepository: TweetRepository,
        configRepository: ConfigRepository,
        logDao: SchedulerLogDao,
        workManager: WorkManager,
    ) {
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val level: AiActivityLevel = runCatching { configRepository.getAiActivityLevel() }
            .getOrDefault(AiActivityLevel.MEDIUM)

        // 确定"上次运行"时刻：查最近一条成功的 tweet_generation 日志
        val lastRun = determineLastRunTime(logDao, now)

        // 加载全部虚拟账号
        val virtualAccounts = loadVirtualAccounts(accountRepository)
        if (virtualAccounts.isEmpty()) {
            Timber.w("无虚拟账号，跳过调度恢复")
            enqueueRoutineWork(workManager)
            return
        }

        var backfillCount = 0
        var scheduledCount = 0

        for (account in virtualAccounts) {
            try {
                val rule = ScheduleRule(
                    accountId = account.id,
                    activeWindows = account.activeWindows,
                    postsPerWindow = level.dailyPostsPerAccount,
                )

                // 2. 调度恢复：错过的活跃窗补发（每窗最多 1 条）
                val missed = ScheduleRuleResolver.missedWindows(rule, lastRun, now, zone)
                for (window in missed) {
                    val windowStartMillis = windowStartMillis(now.atZone(zone), window.startHour, zone)
                    val deduplicationKey = DeduplicationKeys.forTweet(
                        accountId = account.id,
                        windowStart = windowStartMillis,
                        sequenceNo = 0,
                    )
                    // 幂等：若已存在该去重键的推文则 Worker 内部会静默跳过
                    enqueueTweetGeneration(
                        workManager,
                        account.id,
                        deduplicationKey,
                        windowStartMillis,
                        sequenceNo = 0,
                    )
                    backfillCount++
                }

                // 3. 入队下一批 TweetGenerationWorker
                val nextTrigger = ScheduleRuleResolver.nextTriggerTime(rule, now, zone)
                if (nextTrigger != null) {
                    val windowStartMillis = nextTrigger.toEpochMilli()
                    val deduplicationKey = DeduplicationKeys.forTweet(
                        accountId = account.id,
                        windowStart = windowStartMillis,
                        sequenceNo = 0,
                    )
                    // 延迟入队：使用 setInitialDelay 由 WorkManager 调度
                    enqueueTweetGenerationDelayed(
                        workManager,
                        account.id,
                        deduplicationKey,
                        windowStartMillis,
                        sequenceNo = 0,
                        triggerAt = nextTrigger,
                    )
                    scheduledCount++
                }
            } catch (t: Throwable) {
                Timber.w(t, "账号 %s 调度恢复失败", account.id)
            }
        }

        Timber.i(
            "调度恢复完成：补发 %d 条，排程 %d 条下一批推文",
            backfillCount,
            scheduledCount,
        )

        // 4-5. 入队周期任务
        enqueueRoutineWork(workManager)
    }

    /**
     * 确定上次运行时刻。
     *
     * 查询最近一条 action='tweet_generation' 的日志时间戳；
     * 无记录时回退为 24 小时前（仅补发最近一天的错过的窗）。
     */
    private suspend fun determineLastRunTime(
        logDao: SchedulerLogDao,
        now: Instant,
    ): Instant? {
        return runCatching {
            val recent = logDao.getRecent(limit = LAST_RUN_LOOKUP_LIMIT)
            val lastTweetLog = recent.firstOrNull { it.action == "tweet_generation" }
            if (lastTweetLog != null) {
                Instant.ofEpochMilli(lastTweetLog.timestamp)
            } else {
                // 无历史记录：回退为 24 小时前，避免补发过多
                now.minusSeconds(24 * 60 * 60)
            }
        }.getOrNull()
    }

    /**
     * 加载全部虚拟账号（翻页加载）。
     */
    private suspend fun loadVirtualAccounts(
        accountRepository: AccountRepository,
    ): List<AccountEntity> {
        val all = mutableListOf<AccountEntity>()
        var page = 1
        while (page <= MAX_PAGES) {
            val batch = accountRepository.getAccounts(page)
            if (batch.isEmpty()) break
            all.addAll(batch.filter { it.isVirtual })
            page++
        }
        return all
    }

    /**
     * 入队 TweetGenerationWorker（立即执行）。
     */
    private fun enqueueTweetGeneration(
        workManager: WorkManager,
        accountId: String,
        deduplicationKey: String,
        windowStart: Long,
        sequenceNo: Int,
    ) {
        val request = WorkerPolicies.tweetGenerationRequest(
            accountId = accountId,
            deduplicationKey = deduplicationKey,
            windowStart = windowStart,
            sequenceNo = sequenceNo,
        )
        workManager.enqueue(request)
    }

    /**
     * 入队 TweetGenerationWorker（延迟执行，由 WorkManager 在 triggerAt 时刻触发）。
     */
    private fun enqueueTweetGenerationDelayed(
        workManager: WorkManager,
        accountId: String,
        deduplicationKey: String,
        windowStart: Long,
        sequenceNo: Int,
        triggerAt: Instant,
    ) {
        val delayMillis = (triggerAt.toEpochMilli() - System.currentTimeMillis())
            .coerceAtLeast(0L)
        val request = androidx.work.OneTimeWorkRequestBuilder<
            com.trae.social.core.scheduler.work.TweetGenerationWorker>()
            .setInputData(
                androidx.work.workDataOf(
                    com.trae.social.core.scheduler.work.WorkerKeys.KEY_ACCOUNT_ID to accountId,
                    com.trae.social.core.scheduler.work.WorkerKeys.KEY_DEDUP_KEY to deduplicationKey,
                    com.trae.social.core.scheduler.work.WorkerKeys.KEY_WINDOW_START to windowStart,
                    com.trae.social.core.scheduler.work.WorkerKeys.KEY_SEQUENCE_NO to sequenceNo,
                )
            )
            .setConstraints(WorkerPolicies.networkConstraints)
            .setBackoffCriteria(
                WorkerPolicies.backoffPolicy,
                WorkerPolicies.BACKOFF_INITIAL_SECONDS,
                java.util.concurrent.TimeUnit.SECONDS,
            )
            .setInitialDelay(delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            .addTag(WorkerTags.TWEET_GENERATION)
            .build()
        workManager.enqueue(request)
    }

    /**
     * 入队周期任务：PendingInteractionWorker + PersonaUpdateWorker。
     */
    private fun enqueueRoutineWork(workManager: WorkManager) {
        workManager.enqueueUniquePeriodicWork(
            WorkerTags.PENDING_INTERACTION,
            ExistingPeriodicWorkPolicy.KEEP,
            WorkerPolicies.pendingInteractionPeriodicRequest(),
        )
        workManager.enqueueUniquePeriodicWork(
            WorkerTags.PERSONA_UPDATE,
            ExistingPeriodicWorkPolicy.KEEP,
            WorkerPolicies.personaUpdatePeriodicRequest(),
        )
    }

    /**
     * 计算活跃窗起始时刻（基于当前日期与 startHour）。
     */
    private fun windowStartMillis(
        nowZoned: java.time.ZonedDateTime,
        startHour: Int,
        zone: ZoneId,
    ): Long {
        val date = nowZoned.toLocalDate()
        return java.time.LocalTime.of(startHour.coerceIn(0, 23), 0)
            .atDate(date)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    private const val LAST_RUN_LOOKUP_LIMIT: Int = 50
    private const val MAX_PAGES: Int = 12
}
