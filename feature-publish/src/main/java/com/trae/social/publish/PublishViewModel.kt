package com.trae.social.publish

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.trae.social.core.data.AccountIds
import com.trae.social.core.data.TweetLimits
import com.trae.social.core.data.entity.InteractionEntity
import com.trae.social.core.data.entity.InteractionType
import com.trae.social.core.data.entity.TweetEntity
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.InteractionRepository
import com.trae.social.core.data.repository.TweetRepository
import com.trae.social.core.data.model.UserActionType
import com.trae.social.core.data.util.runCatchingCancellable
import com.trae.social.core.profiling.capture.SessionManager
import com.trae.social.core.profiling.capture.UserActionTracker
import com.trae.social.core.scheduler.work.WorkerPolicies
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * 拍照比例。1:1 在 CameraX 中无原生常量，回退到 RATIO_4_3 由 UI 层裁剪为正方形（RISK-7）。
 */
enum class CaptureRatio(val label: String) {
    SQUARE("1:1"),
    RATIO_4_3("4:3"),
    RATIO_16_9("16:9");

    fun toCameraXAspectRatio(): Int = when (this) {
        SQUARE, RATIO_4_3 -> androidx.camera.core.AspectRatio.RATIO_4_3
        RATIO_16_9 -> androidx.camera.core.AspectRatio.RATIO_16_9
    }
}

/**
 * 闪光灯模式。
 */
enum class FlashMode(val label: String) {
    OFF("关闭"),
    ON("常亮"),
    AUTO("自动")
}

/**
 * 发布界面 UI 状态。
 */
data class PublishUiState(
    val captures: List<String> = emptyList(),
    val caption: String = "",
    val selectedRatio: CaptureRatio = CaptureRatio.RATIO_4_3,
    val flashMode: FlashMode = FlashMode.OFF,
    val isPublishing: Boolean = false,
)

/**
 * 发布完成一次性事件。
 */
sealed interface PublishEvent {
    /**
     * 发布失败（IMPL-15），UI 应显示错误并保留输入。
     */
    data object PublishFailed : PublishEvent
}

/**
 * #207 review 修复：发布阶段状态，由 ViewModel 持有以在配置变更后存活。
 *
 * - [IDLE]：空闲
 * - [ANIMATING]：发布成功，正在播放飞入动画
 * - [DONE]：动画完成，UI 应已导航回首页
 */
enum class PublishPhase {
    IDLE,
    ANIMATING,
    DONE,
}

/**
 * 发布页 ViewModel（SubTask 14.7）。
 *
 * 持有 captures / caption / ratio / isPublishing 状态，封装发布流程：
 * 1. 构建 [TweetEntity]（authorId="user-self"，text=caption，mediaPath=captures.firstOrNull()）；
 * 2. [TweetRepository.insertTweet] 落库；
 * 3. 通过 WorkManager 入队 [com.trae.social.core.scheduler.work.InteractionWorker]，
 *    触发 3-8 个虚拟账号的点赞/评论/转发/关注排程；
 * 4. 置 isPublishing=true 触发飞入动画，置 [publishPhase]=ANIMATING 通知 UI 返回首页。
 *
 * 返回首页后信息流由 [TweetRepository.getFeedFlow] 的 Flow 自动更新出新推文。
 */
@HiltViewModel
class PublishViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val tweetRepository: TweetRepository,
    private val interactionRepository: InteractionRepository,
    private val accountRepository: AccountRepository,
    private val userActionTracker: UserActionTracker,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PublishUiState())
    val uiState: StateFlow<PublishUiState> = _uiState.asStateFlow()

    // #207 review 修复：发布阶段状态由 ViewModel 持有，配置变更（旋转屏等）后存活。
    // 替代原 remember { showPublishAnimation }——后者在配置变更时丢失，且 Published
    // Channel 事件已被消费无法重发，导致旋转后动画与导航回调均丢失。
    private val _publishPhase = MutableStateFlow(PublishPhase.IDLE)
    val publishPhase: StateFlow<PublishPhase> = _publishPhase.asStateFlow()

    private val _events = Channel<PublishEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun addCapture(path: String) {
        _uiState.update { state ->
            if (state.captures.size >= MAX_CAPTURES) {
                // review 第 5 轮修复：达上限静默丢弃，但调用方（CameraModeContent.onShutter）
                // 已把 JPEG 落盘到 cacheDir/capture/。丢弃时同步删除文件，避免孤儿文件泄漏。
                runCatching { File(path).delete() }
                    .onFailure { Timber.w(it, "丢弃超额截图文件失败 %s", path) }
                state
            } else {
                state.copy(captures = state.captures + path)
            }
        }
    }

    fun removeCapture(index: Int) {
        // #193：删除条目的同时清理落盘文件，避免 cacheDir 残留泄漏
        val removedPath = _uiState.value.captures.getOrNull(index)
        _uiState.update { state ->
            if (index !in state.captures.indices) state
            else state.copy(captures = state.captures.toMutableList().apply { removeAt(index) })
        }
        if (removedPath != null) {
            runCatching { File(removedPath).delete() }
                .onFailure { Timber.w(it, "删除截图文件失败 %s", removedPath) }
        }
    }

    fun updateCaption(text: String) {
        // #174：使用 codePointCount + offsetByCodePoints 在码点边界截断，
        // 避免 String.take(n) 按 UTF-16 code unit 切开代理对产生非法 Unicode
        _uiState.update { it.copy(caption = text.truncateByCodePoints(TweetLimits.MAX_CAPTION_LENGTH)) }
    }

    fun setRatio(ratio: CaptureRatio) {
        _uiState.update { it.copy(selectedRatio = ratio) }
    }

    fun setFlashMode(mode: FlashMode) {
        _uiState.update { it.copy(flashMode = mode) }
    }

    /**
     * #207 review 修复：标记飞入动画已完成，UI 应已导航回首页。
     */
    fun markPublishAnimationDone() {
        _publishPhase.value = PublishPhase.DONE
    }

    /**
     * #207 review 修复：重置发布阶段为 IDLE，供下次发布使用。
     */
    fun resetPublishPhase() {
        _publishPhase.value = PublishPhase.IDLE
    }

    /**
     * 发布：落库 + 触发 AI 互动 + 发送 Published 事件。
     *
     * 主 review 第 4 轮修复：原入口仅以 `isPublishing` 防止重入，但发布成功后 `finally`
     * 会把 `isPublishing` 置 false，而 `publishPhase` 仍为 ANIMATING。若 UI 未及时导航
     * 离开（动画期间旋转屏、导航失败等），可再次触发 `publish()` 导致重复发推。
     * 入口同时判断 `publishPhase != IDLE`，确保动画阶段无法重复发布；
     * 动画完成后由 [markPublishAnimationDone] 置 DONE，UI 应已 pop 出 Publish back stack entry，
     * ViewModel 被 clear，下一轮进入时为新实例、publishPhase = IDLE。
     */
    fun publish() {
        val current = _uiState.value
        if (current.isPublishing) return
        if (_publishPhase.value != PublishPhase.IDLE) return

        viewModelScope.launch {
            _uiState.update { it.copy(isPublishing = true) }
            try {
                val tweetId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val tweet = TweetEntity(
                    id = tweetId,
                    authorId = AccountIds.USER_SELF_ID,
                    text = current.caption,
                    // IMPL-39：多图以逗号分隔存储，不再静默丢弃
                    mediaPath = if (current.captures.isEmpty()) null
                        else current.captures.joinToString(","),
                    mediaTheme = null,
                    createdAt = now,
                    likeCount = 0,
                    commentCount = 0,
                    retweetCount = 0,
                    isAiGenerated = false,
                    deduplicationKey = "user-self-$now-${UUID.randomUUID()}",
                )
                tweetRepository.insertTweet(tweet)
                // #146 B：发布成功埋点（用 track 挂起直写，保证不可丢失）
                userActionTracker.track(
                    com.trae.social.core.data.model.UserActionEvent(
                        id = UUID.randomUUID().toString(),
                        type = UserActionType.PUBLISH_TWEET,
                        screen = "publish",
                        targetId = tweetId,
                        targetKind = "tweet",
                        extra = mapOf(
                            "captionLen" to kotlinx.serialization.json.JsonPrimitive(current.caption.length),
                            "imageCount" to kotlinx.serialization.json.JsonPrimitive(current.captures.size),
                        ),
                        occurredAt = now,
                        session = sessionManager.currentSessionId() ?: "unknown",
                    )
                )
                triggerAiInteraction(tweetId)
                // #207 review 修复：发布成功后置 publishPhase=ANIMATING（由 ViewModel 持有，
                // 配置变更后存活），替代原 Channel Published 事件（一次性消费，旋转后丢失）。
                _publishPhase.value = PublishPhase.ANIMATING
            } catch (t: kotlinx.coroutines.CancellationException) {
                // #185：协程取消（ViewModel 销毁等）必须向上传播，
                // 不能误判为发布失败并尝试 _events.send（suspend，取消后再次抛 CancellationException）
                throw t
            } catch (t: Throwable) {
                Timber.e(t, "发布失败")
                _events.send(PublishEvent.PublishFailed)
            } finally {
                _uiState.update { it.copy(isPublishing = false) }
            }
        }
    }

    /**
     * 触发 AI 互动排程：入队 InteractionWorker（会排程 3-8 个虚拟账号的点赞/评论/转发）。
     *
     * 降级路径：若 WorkManager 入队异常，回退为直接落库一条即时 LIKE 互动，保证流程不中断。
     * P1 修复（IMPL-3）：降级路径的 accountId 从虚拟账号池随机选一个真实 ID，
     * 不再使用 "ai-fallback" 硬编码，避免 UI 层 resolveAuthor 找不到账号显示异常。
     */
    private suspend fun triggerAiInteraction(tweetId: String) {
        // #185：原 runCatching 会吞 CancellationException，导致协程取消后静默返回，
        // publish() 继续发送 Published 事件让用户看到「发布成功」但 AI 互动排程完全失败。
        // 改用 runCatchingCancellable 重抛 CancellationException。
        runCatchingCancellable {
            val workManager = WorkManager.getInstance(appContext)
            workManager.enqueue(WorkerPolicies.interactionRequest(tweetId))
            Timber.i("已入队 InteractionWorker tweetId=%s", tweetId)
        }.onFailure { t ->
            Timber.w(t, "InteractionWorker 入队失败，回退直接落库单条互动")
            runCatchingCancellable {
                // #208：改用 getVirtualAccountsList 直接获取全部虚拟账号池，
                // 不再依赖 getAccounts(1) 的分页大小与默认排序，语义明确
                val virtualAccounts = accountRepository.getVirtualAccountsList()
                if (virtualAccounts.isEmpty()) {
                    Timber.w("无可用虚拟账号，跳过回退互动落库")
                    return@runCatchingCancellable
                }
                val fallbackAccountId = virtualAccounts.random().id
                val now = System.currentTimeMillis()
                interactionRepository.scheduleInteraction(
                    InteractionEntity(
                        id = UUID.randomUUID().toString(),
                        tweetId = tweetId,
                        accountId = fallbackAccountId,
                        type = InteractionType.LIKE,
                        content = null,
                        createdAt = now,
                        scheduledAt = now + 30_000L,
                        executedAt = null,
                    )
                )
            }.onFailure { Timber.w(it, "回退互动落库失败") }
        }
    }

    companion object {
        const val MAX_CAPTURES = 4
    }
}

/**
 * #174：按 Unicode 码点数安全截断字符串。
 *
 * [String.take] 按 UTF-16 code unit 计数，当 n 落在代理对中间时会产生 dangling surrogate。
 * 本函数用 [String.codePointCount] 计算码点数，用 [String.offsetByCodePoints] 定位码点边界，
 * 保证不在代理对中间切开。超出 [maxCodePoints] 时截断至边界位置。
 */
private fun String.truncateByCodePoints(maxCodePoints: Int): String {
    if (maxCodePoints <= 0) return ""
    val total = codePointCount(0, length)
    if (total <= maxCodePoints) return this
    val endIndex = offsetByCodePoints(0, maxCodePoints)
    return substring(0, endIndex)
}
