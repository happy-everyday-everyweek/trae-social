package com.trae.social.core.scheduler

import android.content.Context
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
import com.trae.social.core.scheduler.ratelimit.SchedulerRateLimiter
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
import java.util.concurrent.atomic.AtomicBoolean
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
 * 5. 入队 PersonaUpdateWorker（PeriodicWorkRequest，周期按 AI 活跃度档位缩放）。
 *
 * IMPL-48：观察 [ConfigRepository.activityLevelChanges]，
 * 档位切换后以 REPLACE 策略重新入队 PersonaUpdateWorker，使新周期立即生效。
 *
 * RISK-3（后台调度）：通过前台服务 + 调度恢复 + BootReceiver 保证调度不中断。
 */
object SchedulerInitializer {

    private val schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * P2 修复：幂等守卫，防止 initialize() 被多次调用后重复启动 collector 协程。
     */
    private val initialized = AtomicBoolean(false)

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
        fun schedulerRateLimiter(): SchedulerRateLimiter
    }

    /**
     * 初始化调度器。在主线程外的 IO 上下文中执行更佳，但 SocialApp.onCreate 中调用
     * 也可接受（Hilt 已完成依赖注入）。
     *
     * 接受 [Context] 而非 [android.app.Application]，使 Worker（持有 applicationContext）
     * 也能直接调用。
     */
    fun initialize(app: Context) {
        // P2 修复：幂等守卫，ensure observeActivityLevelChanges 的 collector 仅启动一次
        if (!initialized.compareAndSet(false, true)) {
            Timber.i("SchedulerInitializer 已初始化，跳过重复调用")
            return
        }
        val entryPoint = EntryPointAccessors.fromApplication(
            app,
            SchedulerEntryPoint::class.java,
        )
        val accountRepository = entryPoint.accountRepository()
        val tweetRepository = entryPoint.tweetRepository()
        val configRepository = entryPoint.configRepository()
        val logDao = entryPoint.schedulerLogDao()
        val rateLimiter = entryPoint.schedulerRateLimiter()
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

        // IMPL-48：观察档位变更，切换后重新入队周期 Worker + 重新配置限流器
        observeActivityLevelChanges(configRepository, workManager, rateLimiter)
    }

    /**
     * 启动前台服务（Android O+ 需通过 startForegroundService）。
     *
     * 若调用方处于后台上下文（如开机后的 [com.trae.social.core.scheduler.work.SchedulerInitializerWorker]
     * 或进程被 WorkManager 唤起时），Android 12+（targetSdk 31+）会抛
     * ForegroundServiceStartNotAllowedException（IllegalStateException 子类）。
     * 此处捕获以免崩溃；keep-alive 服务缺位不影响调度——周期任务由 WorkManager 兜底。
     */
    private fun startForegroundService(app: Context) {
        val intent = Intent(app, SchedulerForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        } catch (e: IllegalStateException) {
            Timber.e(e, "启动调度前台服务失败：当前处于后台上下文")
        } catch (e: SecurityException) {
            Timber.e(e, "启动调度前台服务失败：权限被拒绝")
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
        val now = Instant.now()
        val level: AiActivityLevel = runCatching { configRepository.getAiActivityLevel() }
            .getOrDefault(AiActivityLevel.MEDIUM)

        // 确定"上次运行"时刻：查最近一条成功的 tweet_generation 日志
        val lastRun = determineLastRunTime(logDao, now)

        // 加载全部虚拟账号
        val virtualAccounts = loadVirtualAccounts(accountRepository)
        if (virtualAccounts.isEmpty()) {
            Timber.w("无虚拟账号，跳过调度恢复")
            enqueueRoutineWork(workManager, level)
            return
        }

        var backfillCount = 0
        var scheduledCount = 0

        for (account in virtualAccounts) {
            try {
                // IMPL-16：使用账号自身时区，避免跨时区旅行时活跃窗偏移
                val accountZone = runCatching { ZoneId.of(account.timezone) }
                    .getOrElse { ZoneId.systemDefault() }
                // P1 修复：postsPerWindow 为每窗上限（默认 2 条），与 dailyPostsPerAccount（每日上限）区分
                val rule = ScheduleRule(
                    accountId = account.id,
                    activeWindows = account.activeWindows,
                    postsPerWindow = POSTS_PER_WINDOW,
                )

                // #114：使用账号自身的最近日志确定"上次运行"时刻，
                // 避免全局日志导致跨账号补发漏窗
                val accountLastRun = determineAccountLastRunTime(logDao, account.id, now)

                // 2. 调度恢复：错过的活跃窗补发（每窗最多 1 条）
                val missed = ScheduleRuleResolver.missedWindows(rule, accountLastRun, now, accountZone)
                for (missedWindow in missed) {
                    // IMPL-4：使用窗口实际所属日期计算 windowStartMillis，避免跨日 key 冲突
                    val windowStartMillis = windowStartMillis(missedWindow.date, missedWindow.startHour, accountZone)
                    // #113：补发前检查窗口内已有推文数，已达 postsPerWindow 上限时跳过
                    val windowEndMillis = windowEndMillis(
                        missedWindow.date,
                        missedWindow.window.endHour,
                        accountZone,
                    )
                    val existingInWindow = runCatching {
                        tweetRepository.countByAuthorInWindow(account.id, windowStartMillis, windowEndMillis)
                    }.getOrDefault(0)
                    if (existingInWindow >= rule.postsPerWindow) continue

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
                // P1 修复：传入 postsInWindowProvider 检查窗内已发布数，达上限时跳到下一窗
                val nextTrigger = ScheduleRuleResolver.nextTriggerTime(
                    rule = rule,
                    now = now,
                    zone = accountZone,
                    postsInWindowProvider = { accountId, ws, we ->
                        tweetRepository.countByAuthorInWindow(accountId, ws, we)
                    },
                )
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
        enqueueRoutineWork(workManager, level)
    }

    /**
     * 确定上次运行时刻（全局，仅用于无账号上下文时的回退）。
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
     * #114：确定指定账号的上次运行时刻。
     *
     * 查询该账号最近一条 tweet_generation 日志的时间戳，
     * 避免 determineLastRunTime 使用全局日志导致跨账号补发漏窗。
     * 无记录时回退为 24 小时前。
     */
    private suspend fun determineAccountLastRunTime(
        logDao: SchedulerLogDao,
        accountId: String,
        now: Instant,
    ): Instant? {
        return runCatching {
            val recent = logDao.getByAccount(accountId, limit = LAST_RUN_LOOKUP_LIMIT)
            val lastTweetLog = recent.firstOrNull { it.action == "tweet_generation" }
            if (lastTweetLog != null) {
                Instant.ofEpochMilli(lastTweetLog.timestamp)
            } else {
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
        // #106：移除 MAX_PAGES 硬编码上限，循环直到无更多数据
        while (true) {
            val batch = accountRepository.getAccounts(page)
            if (batch.isEmpty()) break
            all.addAll(batch.filter { it.isVirtual })
            page++
        }
        Timber.i("loadVirtualAccounts: 加载了 %d 个虚拟账号", all.size)
        return all
    }

    /**
     * 入队 TweetGenerationWorker（立即执行）。
     *
     * P2 修复：使用 enqueueUniqueWork + KEEP 策略，以 deduplicationKey 作为唯一标识，
     * 防止调度恢复或重复调用导致同一去重键的 Worker 重复入队。
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
        workManager.enqueueUniqueWork(
            deduplicationKey,
            androidx.work.ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * 入队 TweetGenerationWorker（延迟执行，由 WorkManager 在 triggerAt 时刻触发）。
     *
     * P2 修复：使用 enqueueUniqueWork + KEEP 策略，与 enqueueTweetGeneration 保持一致，
     * 防止延迟 Worker 重复入队。
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
        workManager.enqueueUniqueWork(
            deduplicationKey,
            androidx.work.ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * 入队周期任务：PendingInteractionWorker + PersonaUpdateWorker。
     *
     * IMPL-47：PersonaUpdateWorker 周期按 [level] 缩放。
     */
    private fun enqueueRoutineWork(workManager: WorkManager, level: AiActivityLevel) {
        workManager.enqueueUniquePeriodicWork(
            WorkerTags.PENDING_INTERACTION,
            ExistingPeriodicWorkPolicy.KEEP,
            WorkerPolicies.pendingInteractionPeriodicRequest(),
        )
        workManager.enqueueUniquePeriodicWork(
            WorkerTags.PERSONA_UPDATE,
            ExistingPeriodicWorkPolicy.KEEP,
            WorkerPolicies.personaUpdatePeriodicRequest(level),
        )
    }

    /**
     * IMPL-48：观察 AI 活跃度档位变更，重新入队周期 Worker。
     *
     * 使用 [ExistingPeriodicWorkPolicy.REPLACE] 替换现有排程，
     * 使新档位的周期（PersonaUpdateWorker）立即生效。
     *
     * P2 修复：同时调用 [SchedulerRateLimiter.reconfigure] 使限流器容量立即同步，
     * 不必等待下一个 TweetGenerationWorker 触发。
     */
    private fun observeActivityLevelChanges(
        configRepository: ConfigRepository,
        workManager: WorkManager,
        rateLimiter: SchedulerRateLimiter,
    ) {
        schedulerScope.launch {
            configRepository.activityLevelChanges.collect { level ->
                Timber.i("AI 活跃度档位变更: %s，重新入队 PersonaUpdateWorker 并重配限流器", level.id)
                workManager.enqueueUniquePeriodicWork(
                    WorkerTags.PERSONA_UPDATE,
                    ExistingPeriodicWorkPolicy.REPLACE,
                    WorkerPolicies.personaUpdatePeriodicRequest(level),
                )
                // P2 修复：档位切换后立即重新配置限流器，避免限流器容量滞后
                runCatching { rateLimiter.reconfigure(level) }
                    .onFailure { Timber.w(it, "重新配置限流器失败") }
            }
        }
    }

    /**
     * 计算活跃窗起始时刻（基于指定日期与 startHour）。
     *
     * IMPL-4：使用窗口实际所属日期 [date]，而非"今天"，避免跨日补发时 windowStart 错误。
     */
    private fun windowStartMillis(
        date: java.time.LocalDate,
        startHour: Int,
        zone: ZoneId,
    ): Long {
        return java.time.LocalTime.of(startHour.coerceIn(0, 23), 0)
            .atDate(date)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * #113：计算活跃窗结束时刻（毫秒）。
     *
     * endHour=24 时结束时刻为次日 00:00。
     */
    private fun windowEndMillis(
        date: java.time.LocalDate,
        endHour: Int,
        zone: ZoneId,
    ): Long {
        return if (endHour >= 24) {
            date.plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        } else {
            java.time.LocalTime.of(endHour, 0)
                .atDate(date)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        }
    }

    private const val LAST_RUN_LOOKUP_LIMIT: Int = 50

    /** P1 修复：每个活跃窗内允许发布的推文数上限（spec 默认 2 条/窗） */
    private const val POSTS_PER_WINDOW: Int = 2
}
