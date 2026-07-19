package com.trae.social.timeline

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trae.social.core.data.entity.AccountEntity
import com.trae.social.core.data.entity.TweetEntity
import com.trae.social.core.data.repository.AccountRepository
import com.trae.social.core.data.repository.TweetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * 时间线页面 ViewModel。
 *
 * 数据流：
 * 1. [TweetRepository.observeMediaTweets] 返回仅含 mediaPath 的推文（createdAt DESC）；
 * 2. 按日期分组为 [TimelineGroup] 列表；
 * 3. 暴露为 [timelineFlow]，UI 据此渲染 Loading / Success / Empty / Error。
 */
@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val tweetRepository: TweetRepository,
    private val accountRepository: AccountRepository,
) : ViewModel() {

    val timelineFlow: StateFlow<TimelineUiState> = tweetRepository
        .observeMediaTweets()
        .map { tweets -> groupTweets(tweets) }
        .map { groups ->
            if (groups.isEmpty()) TimelineUiState.Empty
            else TimelineUiState.Success(groups)
        }
        .catch { cause ->
            emit(TimelineUiState.Error(cause.message ?: "加载失败"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = TimelineUiState.Loading,
        )

    // #13：当前用户资料（avatarSeed / displayName），与个人主页共用 user-self 账号，
    // 替代时间线头部硬编码的“我”占位，保证两处身份呈现一致。
    private val _selfProfile = MutableStateFlow<AccountEntity?>(null)
    val selfProfile: StateFlow<AccountEntity?> = _selfProfile.asStateFlow()

    init {
        viewModelScope.launch {
            _selfProfile.value = runCatching {
                accountRepository.getById(SELF_ID)
            }.getOrNull()
        }
    }

    /**
     * 将推文按日期分组。组内保持 createdAt DESC 顺序；组间按日期 DESC 排序。
     */
    private fun groupTweets(tweets: List<TweetEntity>): List<TimelineGroup> {
        if (tweets.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val timeFormatter = DateTimeFormatter.ofPattern(TIME_PATTERN)

        return tweets
            .groupBy { tweet ->
                Instant.ofEpochMilli(tweet.createdAt).atZone(zone).toLocalDate()
            }
            .map { (date, items) ->
                TimelineGroup(
                    date = date,
                    dateLabel = formatDateLabel(date),
                    items = items.map { tweet ->
                        TimelineItem(
                            tweetId = tweet.id,
                            mediaPath = tweet.mediaPath.orEmpty(),
                            text = tweet.text,
                            timeLabel = Instant.ofEpochMilli(tweet.createdAt)
                                .atZone(zone)
                                .toLocalTime()
                                .format(timeFormatter),
                            fullText = tweet.text,
                        )
                    },
                )
            }
            .sortedByDescending { it.date }
    }

    /**
     * 日期标签格式化：今天 / 昨天 / N天前 / M月d日 / yyyy年M月d日。
     */
    private fun formatDateLabel(date: LocalDate): String {
        val today = LocalDate.now()
        val days = ChronoUnit.DAYS.between(date, today).toInt()
        return when {
            days <= 0 -> "今天"
            days == 1 -> "昨天"
            days in 2..6 -> "${days}天前"
            date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日"
            else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val TIME_PATTERN = "HH:mm"
        // #13：自身账号固定 ID（与 PersonaSeeder.USER_SELF_ID / ProfileViewModel.SELF_ID 一致）
        const val SELF_ID = "user-self"
    }
}

/**
 * 时间线 UI 状态。
 */
sealed interface TimelineUiState {
    data object Loading : TimelineUiState
    data class Success(val groups: List<TimelineGroup>) : TimelineUiState
    data object Empty : TimelineUiState
    data class Error(val message: String) : TimelineUiState
}

/**
 * 单日分组：日期 + 标签 + 该日图片项列表。
 *
 * #231：标注 @Immutable。`items` 为 `List<TimelineItem>`，本身会被 Compose 推断为
 * Unstable 导致 TimelineGroup 不可 skip。该类由 ViewModel 一次性构造后下发，集合
 * 内容不再变，可安全标注 @Immutable。
 */
@Immutable
data class TimelineGroup(
    val date: LocalDate,
    val dateLabel: String,
    val items: List<TimelineItem>,
)

/**
 * 单条时间线项。
 *
 * @param mediaPath 原始 asset 相对路径（如 "gallery/landscape/3.svg"），UI 层负责拼装为 Coil 可加载的地址
 * @param text 单行摘要文本（UI 层省略号截断）
 * @param timeLabel 发布时间（HH:mm）
 * @param fullText 完整推文文本，大图浏览器展示
 *
 * #231：标注 @Immutable，所有字段为不可变 String。
 */
@Immutable
data class TimelineItem(
    val tweetId: String,
    val mediaPath: String,
    val text: String,
    val timeLabel: String,
    val fullText: String,
)
