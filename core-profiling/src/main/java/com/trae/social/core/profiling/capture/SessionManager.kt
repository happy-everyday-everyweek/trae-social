package com.trae.social.core.profiling.capture

import com.trae.social.core.data.model.UserActionEvent
import com.trae.social.core.data.model.UserActionType
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话管理（#146 第一层）。
 *
 * 解决 Android onPause/onResume 被权限弹窗、系统弹窗频繁触发导致会话被切碎的问题：
 * - onResume：若距上次 onPause < [sessionGapMs]（30s）则复用旧 sessionId，否则生成新 UUID。
 * - onPause：记录时间戳，不立即结束会话。
 * - 会话 ID 存内存，进程重启即新会话。
 * - 会话开始 track(SESSION_START)，超时真正结束 track(SESSION_END, durationMs)。
 */
@Singleton
class SessionManager @Inject constructor(
    private val tracker: UserActionTracker,
) {

    @Volatile private var currentSessionId: String? = null
    @Volatile private var pausedAt: Long = 0L
    @Volatile private var sessionStartedAt: Long = 0L

    /**
     * 由 Activity.onResume 调用。返回当前生效的会话 ID。
     *
     * @param screen 触发 resume 的屏幕名，用于 SESSION_START 埋点。
     */
    fun onResume(screen: String): String {
        val now = System.currentTimeMillis()
        val existing = currentSessionId
        val resumeExisting = existing != null && pausedAt > 0 && (now - pausedAt) < sessionGapMs
        return if (resumeExisting) {
            existing!!
        } else {
            // 超时或首次：若旧会话未结束则先发 SESSION_END
            endPreviousIfNeeded(now, screen)
            val newId = UUID.randomUUID().toString()
            currentSessionId = newId
            sessionStartedAt = now
            pausedAt = 0L
            tracker.trackNow(
                UserActionEvent(
                    id = UUID.randomUUID().toString(),
                    type = UserActionType.SESSION_START,
                    screen = screen,
                    occurredAt = now,
                    session = newId,
                )
            )
            newId
        }
    }

    /**
     * 由 Activity.onPause 调用，仅记录时间戳，不立即结束会话。
     */
    fun onPause() {
        pausedAt = System.currentTimeMillis()
    }

    /**
     * 当前会话 ID（可能为 null，进程刚启动未 onResume）。
     */
    fun currentSessionId(): String? = currentSessionId

    /**
     * 进程结束时由 App 生命周期调用，确保最后一个会话发 SESSION_END。
     */
    fun endSession() {
        endPreviousIfNeeded(System.currentTimeMillis(), "app_exit")
    }

    private fun endPreviousIfNeeded(now: Long, screen: String) {
        val prev = currentSessionId ?: return
        val startedAt = sessionStartedAt
        if (startedAt > 0) {
            val durationMs = now - startedAt
            runCatching {
                tracker.trackNow(
                    UserActionEvent(
                        id = UUID.randomUUID().toString(),
                        type = UserActionType.SESSION_END,
                        screen = screen,
                        durationMs = durationMs,
                        occurredAt = now,
                        session = prev,
                    )
                )
            }.onFailure { Timber.w(it, "SESSION_END 埋点失败") }
        }
        currentSessionId = null
        sessionStartedAt = 0L
    }

    private companion object {
        const val sessionGapMs = 30_000L
    }
}
