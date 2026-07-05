package com.trae.social.publish

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.trae.social.core.data.entity.InteractionEntity
import com.trae.social.core.data.entity.InteractionType
import com.trae.social.core.data.entity.TweetEntity
import com.trae.social.core.data.repository.InteractionRepository
import com.trae.social.core.data.repository.TweetRepository
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
     * 发布成功，触发缩小飞入动画并返回首页。
     */
    data object Published : PublishEvent
}

/**
 * 发布页 ViewModel（SubTask 14.7）。
 *
 * 持有 captures / caption / ratio / isPublishing 状态，封装发布流程：
 * 1. 构建 [TweetEntity]（authorId="user-self"，text=caption，mediaPath=captures.firstOrNull()）；
 * 2. [TweetRepository.insertTweet] 落库；
 * 3. 通过 WorkManager 入队 [com.trae.social.core.scheduler.work.InteractionWorker]，
 *    触发 3-8 个虚拟账号的点赞/评论/转发/关注排程；
 * 4. 置 isPublishing=true 触发飞入动画，发送 [PublishEvent.Published] 通知 UI 返回首页。
 *
 * 返回首页后信息流由 [TweetRepository.getFeedFlow] 的 Flow 自动更新出新推文。
 */
@HiltViewModel
class PublishViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val tweetRepository: TweetRepository,
    private val interactionRepository: InteractionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PublishUiState())
    val uiState: StateFlow<PublishUiState> = _uiState.asStateFlow()

    private val _events = Channel<PublishEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun addCapture(path: String) {
        _uiState.update { state ->
            if (state.captures.size >= MAX_CAPTURES) state
            else state.copy(captures = state.captures + path)
        }
    }

    fun removeCapture(index: Int) {
        _uiState.update { state ->
            if (index !in state.captures.indices) state
            else state.copy(captures = state.captures.toMutableList().apply { removeAt(index) })
        }
    }

    fun updateCaption(text: String) {
        _uiState.update { it.copy(caption = text.take(MAX_CAPTION_LENGTH)) }
    }

    fun setRatio(ratio: CaptureRatio) {
        _uiState.update { it.copy(selectedRatio = ratio) }
    }

    fun setFlashMode(mode: FlashMode) {
        _uiState.update { it.copy(flashMode = mode) }
    }

    /**
     * 发布：落库 + 触发 AI 互动 + 发送 Published 事件。
     */
    fun publish() {
        val current = _uiState.value
        if (current.isPublishing) return

        viewModelScope.launch {
            _uiState.update { it.copy(isPublishing = true) }
            try {
                val tweetId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val tweet = TweetEntity(
                    id = tweetId,
                    authorId = AUTHOR_SELF,
                    text = current.caption,
                    mediaPath = current.captures.firstOrNull(),
                    mediaTheme = null,
                    createdAt = now,
                    likeCount = 0,
                    commentCount = 0,
                    retweetCount = 0,
                    isAiGenerated = false,
                    deduplicationKey = "user-self-$now-${UUID.randomUUID()}",
                )
                tweetRepository.insertTweet(tweet)
                triggerAiInteraction(tweetId)
            } catch (t: Throwable) {
                Timber.e(t, "发布失败")
            } finally {
                _events.send(PublishEvent.Published)
            }
        }
    }

    /**
     * 触发 AI 互动排程：入队 InteractionWorker（会排程 3-8 个虚拟账号的点赞/评论/转发）。
     *
     * 降级路径：若 WorkManager 入队异常，回退为直接落库一条即时 LIKE 互动，保证流程不中断。
     */
    private suspend fun triggerAiInteraction(tweetId: String) {
        runCatching {
            val workManager = WorkManager.getInstance(appContext)
            workManager.enqueue(WorkerPolicies.interactionRequest(tweetId))
            Timber.i("已入队 InteractionWorker tweetId=%s", tweetId)
        }.onFailure { t ->
            Timber.w(t, "InteractionWorker 入队失败，回退直接落库单条互动")
            runCatching {
                val now = System.currentTimeMillis()
                interactionRepository.scheduleInteraction(
                    InteractionEntity(
                        id = UUID.randomUUID().toString(),
                        tweetId = tweetId,
                        accountId = "ai-fallback",
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
        const val MAX_CAPTION_LENGTH = 280
        const val AUTHOR_SELF = "user-self"
    }
}
