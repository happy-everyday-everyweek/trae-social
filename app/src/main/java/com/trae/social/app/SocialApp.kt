package com.trae.social.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.trae.social.core.data.seed.PersonaSeeder
import com.trae.social.core.data.util.runCatchingCancellable
import com.trae.social.core.profiling.capture.SessionManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 全局 Application 入口。
 *
 * 职责：
 * 1. 初始化 Timber 日志（debug 树 + release 脱敏树）。
 * 2. 注册全局未捕获异常处理器，记录崩溃信息。
 * 3. 实现 [Configuration.Provider] 接入 [HiltWorkerFactory]，使 @HiltWorker 可注入依赖。
 * 4. IMPL-1：触发 [PersonaSeeder.seedIfNeeded] 导入虚拟账号与历史推文。
 * 5. #146：进程退出时调用 [SessionManager.endSession] 发出最后一个 SESSION_END 埋点。
 *
 * 注意：调度器初始化（[com.trae.social.core.scheduler.SchedulerInitializer.initialize]）
 * 已移至 [MainActivity.onCreate] 执行——Application.onCreate 运行于后台上下文，
 * 在 Android 12+（targetSdk 31+）从后台启动前台服务会抛
 * ForegroundServiceStartNotAllowedException 导致应用启动即崩。
 */
@HiltAndroidApp
class SocialApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var personaSeeder: PersonaSeeder

    // #146：进程级生命周期结束时调用 endSession，保证最后一个会话发出 SESSION_END。
    @Inject
    lateinit var sessionManager: SessionManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        initTimber()
        installCrashHandler()

        // IMPL-1：触发种子数据导入（虚拟账号 + 历史推文 + user-self 账号）
        // #185：改用 runCatchingCancellable 重抛 CancellationException，保持协程取消语义
        appScope.launch {
            runCatchingCancellable { personaSeeder.seedIfNeeded().collect { /* 进度可通过 StateFlow 暴露给 UI */ } }
                .onFailure { Timber.e(it, "种子数据导入失败") }
        }

        // #146：监听进程级生命周期 ON_STOP，进程退到后台时触发 endSession 发出 SESSION_END。
        // 用户回到前台时 MainActivity.onResume 会开新会话；30s 内 ON_STOP→ON_RESUME 不会重复
        // 开会话（endSession 已清理 currentSessionId，但 SessionManager.onResume 走的是
        // pausedAt 30s 合并逻辑——此处主动 endSession 确保后台即结束会话，简化语义）。
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                runCatching { sessionManager.endSession() }
                    .onFailure { Timber.w(it, "endSession 失败") }
            }
        })

        // 调度器初始化已移至 MainActivity.onCreate，避免在后台上下文启动前台服务导致崩溃。
    }

    /**
     * 为 WorkManager 提供 Hilt 注入的 WorkerFactory。
     *
     * 注意：AndroidManifest 已通过 androidx.startup 自动初始化 WorkManager，
     * 但要使 HiltWorkerFactory 生效，需移除默认 Initializer 并由本类提供 Configuration。
     * 见 AndroidManifest.xml 中对 androidx.startup WorkManagerInitializer 的移除声明。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    /**
     * 初始化 Timber：debug 构建使用 DebugTree 打印完整日志；
     * release 构建使用 ReleaseTree 仅输出 WARN/ERROR 并对敏感信息脱敏。
     */
    private fun initTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(object : Timber.DebugTree() {
                override fun createStackElementTag(element: StackTraceElement): String =
                    "Social/${element.fileName}:${element.lineNumber}"
            })
        } else {
            Timber.plant(ReleaseTree())
        }
    }

    /**
     * 注册全局未捕获异常处理器：记录崩溃堆栈后委托给默认处理器终止进程。
     */
    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Timber.e(throwable, "未捕获异常 thread=%s", thread.name)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Release 构建的日志树：
     * - 仅记录 WARN 及以上级别，避免敏感数据落盘。
     * - 对日志消息中可能出现的 token / 密钥 / 手机号等做正则脱敏。
     */
    private class ReleaseTree : Timber.Tree() {

        override fun isLoggable(tag: String?, priority: Int): Boolean =
            priority >= Log.WARN

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val sanitized = sanitize(message)
            // 仅输出脱敏后的摘要，避免完整明文
            if (priority >= Log.WARN) {
                Log.println(priority, tag ?: "Social", sanitized)
            }
        }

        /**
         * 脱敏处理：遮蔽疑似 token / 密钥 / 邮箱 / 手机号的敏感片段。
         */
        private fun sanitize(message: String): String {
            var result = message
            // 形如 Bearer xxxxx 的授权头
            result = result.replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._\\-]+"), "$1***")
            // 长十六进制 / base64 串（疑似密钥或 token，长度>=20）
            result = result.replace(Regex("\\b[A-Za-z0-9_\\-]{20,}\\b"), "***")
            // 邮箱
            result = result.replace(Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+"), "***@***")
            // 中国大陆手机号
            result = result.replace(Regex("1[3-9]\\d{9}"), "1**********")
            return result
        }
    }
}
