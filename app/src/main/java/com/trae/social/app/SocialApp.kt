package com.trae.social.app

import android.app.Application
import android.util.Log
import timber.log.Timber

/**
 * 全局 Application 入口。
 *
 * 职责：
 * 1. 初始化 Timber 日志（debug 树 + release 脱敏树）。
 * 2. 注册全局未捕获异常处理器，记录崩溃信息。
 * 3. 预留 WorkManager 初始化钩子（后续 Task 8 实现）。
 */
@dagger.hilt.android.HiltAndroidApp
class SocialApp : Application() {

    override fun onCreate() {
        super.onCreate()

        initTimber()
        installCrashHandler()

        // TODO(Task 8): 在此初始化 WorkManager 自定义 Configuration。
        // 当前使用默认 Configuration（AndroidManifest 已通过 androidx.startup
        // 自动初始化 WorkManager）；后续在 Task 8 中切换为 Configuration.Provider
        // 以注入 HiltWorkerFactory，实现依赖注入的 Worker 构建。
        initWorkManagerHook()
    }

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
     * WorkManager 初始化钩子，当前为占位实现。
     */
    private fun initWorkManagerHook() {
        Timber.i("WorkManager 钩子占位：将在 Task 8 中接入 HiltWorkerFactory")
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
