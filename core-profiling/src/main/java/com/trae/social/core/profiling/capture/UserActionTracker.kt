package com.trae.social.core.profiling.capture

import com.trae.social.core.data.model.UserActionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户行为捕获层写侧接口（#146 第一层）。
 *
 * - [trackNow]：fire-and-forget，投递到内部队列由单协程批写，不阻塞调用方。
 * - [track]：挂起直写，绕过队列单事务落库，用于发布完成等不可丢失场景。
 *
 * 实现保证：批写 500ms/50 条单事务原子性；写前去重；采集开关关闭丢弃；
 * 调试旁路；失败仅 Timber.w 不重试。
 */
interface UserActionTracker {

    /** 同步投递事件到内部队列，fire-and-forget，不阻塞调用方。 */
    fun trackNow(event: UserActionEvent)

    /** 挂起写：直接落库并返回，用于需要确认持久化的场景（如发布完成）。 */
    suspend fun track(event: UserActionEvent)
}

/**
 * 事件构建辅助：简化各业务点埋点。
 */
class UserActionEventBuilder(
    private val tracker: UserActionTracker,
    private val sessionProvider: () -> String,
) {
    fun emit(
        type: com.trae.social.core.data.model.UserActionType,
        screen: String,
        targetId: String? = null,
        targetKind: String? = null,
        durationMs: Long? = null,
        extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
        occurredAt: Long = System.currentTimeMillis(),
    ) {
        tracker.trackNow(
            UserActionEvent(
                id = UUID.randomUUID().toString(),
                type = type,
                screen = screen,
                targetId = targetId,
                targetKind = targetKind,
                extra = extra,
                durationMs = durationMs,
                occurredAt = occurredAt,
                session = sessionProvider(),
            )
        )
    }
}

/**
 * [UserActionTracker] 默认实现。
 *
 * 依赖 [ProfilingGate] 读取采集开关；依赖 [UserActionSink] 落库（由 DAO 实现注入，
 * 避免核心逻辑直接耦合 Room，便于单测）。
 */
@Singleton
class UserActionTrackerImpl @Inject constructor(
    private val sink: UserActionSink,
    private val gate: ProfilingGate,
) : UserActionTracker {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<UserActionEvent>(capacity = Channel.UNLIMITED)
    private val dedupLock = Any()
    private val recentKeys = LinkedHashMap<String, Long>()

    @Volatile private var consumerStarted = false

    private fun ensureConsumer() {
        if (consumerStarted) return
        synchronized(this) {
            if (consumerStarted) return
            consumerStarted = true
            scope.launch { consumeLoop() }
        }
    }

    override fun trackNow(event: UserActionEvent) {
        if (!gate.isEnabled()) return
        if (!dedup(event)) return
        ensureConsumer()
        channel.trySend(event)
    }

    override suspend fun track(event: UserActionEvent) {
        if (!gate.isEnabled()) return
        if (!dedup(event)) return
        runCatching { sink.insertAll(listOf(event.toEntity())) }
            .onFailure { Timber.w(it, "track 直写失败") }
        if (gate.isDebug()) {
            Timber.i("[Profile] track %s @ %s target=%s", event.type, event.screen, event.targetId)
        }
    }

    /** 写前去重：同 (type,targetId,session,occurredAt) 在 1s 内去重，防 Compose 重组重复触发。 */
    private fun dedup(event: UserActionEvent): Boolean {
        val key = "${event.type}|${event.targetId}|${event.session}|${event.occurredAt}"
        val now = System.currentTimeMillis()
        synchronized(dedupLock) {
            val last = recentKeys[key]
            if (last != null && now - last < DEDUP_WINDOW_MS) {
                return false
            }
            recentKeys[key] = now
            if (recentKeys.size > DEDUP_MAX) {
                recentKeys.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
            }
            return true
        }
    }

    private suspend fun consumeLoop() {
        val batch = ArrayList<UserActionEvent>(BATCH_MAX)
        while (true) {
            // 等待首条事件
            val first = channel.receive()
            batch.add(first)
            // 在窗口内尽可能聚合
            val deadline = System.currentTimeMillis() + BATCH_WINDOW_MS
            while (batch.size < BATCH_MAX) {
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
                if (remaining == 0L) break
                val next = try {
                    kotlinx.coroutines.withTimeoutOrNull(remaining) { channel.receive() }
                } catch (_: Throwable) {
                    null
                }
                if (next == null) break
                batch.add(next)
            }
            flush(batch)
            batch.clear()
            // 第二轮 review Nit 修复:原 `if (batch.isEmpty()) delay(10)` 在 clear 之后执行,
            // batch.isEmpty() 恒为 true,delay 每次 flush 后都执行,注释"避免空转"与实际不符
            // (非空转时也在 delay)。channel.receive() 已是 suspending 调用,无事件时挂起,
            // 不会 busy loop,无需额外 delay 防空转,直接删除避免拖慢批写。
        }
    }

    private suspend fun flush(batch: List<UserActionEvent>) {
        if (batch.isEmpty()) return
        val entities = batch.map { it.toEntity() }
        runCatching { sink.insertAll(entities) }
            .onFailure { Timber.w(it, "批写 %d 条事件失败", batch.size) }
        if (gate.isDebug()) {
            Timber.i("[Profile] 批写 %d 条事件", batch.size)
        }
    }

    private fun UserActionEvent.toEntity() =
        com.trae.social.core.profiling.mapping.ProfileMappers.run { this@toEntity.toEntity() }

    private companion object {
        const val BATCH_WINDOW_MS = 500L
        const val BATCH_MAX = 50
        const val DEDUP_WINDOW_MS = 1000L
        const val DEDUP_MAX = 500
    }
}

/** 落库抽象（DAO 实现），解耦 Tracker 与 Room。 */
interface UserActionSink {
    suspend fun insertAll(events: List<com.trae.social.core.data.entity.UserActionEventEntity>)
}

/** 采集开关与调试旁路读取。 */
interface ProfilingGate {
    fun isEnabled(): Boolean
    fun isDebug(): Boolean
}
