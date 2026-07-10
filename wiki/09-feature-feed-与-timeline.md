# feature-feed 与 feature-timeline

首页信息流与朋友圈式图片时间线两个功能模块。

## feature-feed

namespace `com.trae.social.feature.feed`。依赖 `paging-runtime` / `paging-compose` / `coil-compose` / `coil-svg` / `hilt` / `timber`。

### FeedScreen

参数 `onScrollingChange` / `onNavigateToSettings`。容器 `PullToRefreshBox` + `LazyColumn`。UI 状态派生 `isInitialLoading` / `isError` / `isEmpty` / `isRefreshing` / `isInListMode`。

占位组件：

- `LoadingPlaceholderList`（3 `ShimmerCard`）
- `EmptyPlaceholder`（灰色方块":-)"）
- `ErrorPlaceholder`（WifiOff 图标 + `friendlyErrorMessage()`）

`RetweetConfirmDialog`（`SocialCard` + `Dialog`）。`OnboardingSkippedBanner`（systemBlue 12% 背景）。滚动检测 `snapshotFlow{listState.isScrollInProgress}.collect{onScrollingChange(it)}` 触发 `GlassBlurContainer` 降级。

### FeedViewModel

`@HiltViewModel`，注入 `TweetRepository` / `AccountRepository` / `InteractionRepository` / `CommentRepository` / `ConfigRepository` / `@FeedImageLoader ImageLoader`。

- `authorCache = ConcurrentHashMap<String, AuthorInfo>` 线程安全作者缓存。
- 状态字段 `_likedTweetIds` / `_bookmarkedTweetIds`（乐观更新）/ `_isOnboardingSkipped`。
- `feedFlow = tweetRepository.getFeedFlow().map{pagingData.map{resolveAuthor(it)}}` Paging 3。
- `likeTweet` 乐观更新 + `scheduleInteraction(LIKE)` + 失败回滚。
- `commentTweet` `updateCommentCount` + `scheduleInteraction(COMMENT)` + `addComment`。
- `retweetTweet` `insertTweet("转发:"+...)` + `updateRetweetCount` + `scheduleInteraction(RETWEET)`。

常量 `USER_SELF_ID = "user-self"`。

### TweetCard

头像 + 名称 + @username + 相对时间 + `DropdownMenu`（复制/分享/不感兴趣）。`TweetText` 280 字折叠 + "展开全文"。媒体 `AsyncImage` 12dp 圆角 maxHeight 400dp。`InteractionButton` 评论/转发/点赞/收藏 44dp 触控热区。`FeedAvatar` AI 推文头像右下角 6dp systemBlue 蓝点标识。`formatCount >= 10000` 显示"x.x万"。

### TweetWithAuthor / CommentItem

`data class`。

### CommentSheet

`SocialSheet` 包装，`TweetSummary`（头像 32dp + 文本前 50 字 + 缩略图 80x60），`LazyColumn` 评论列表 + `OutlinedTextField` + Send `IconButton`，发送乐观追加 `CommentItem(authorName = "我", authorAvatarSeed = "user-self")`。

### FullScreenImage

`Dialog(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)`。`detectTapGestures(onTap dismiss / onDoubleTap 切换 1x/3x)`。`detectTransformGestures` scale 1x-5x `clampOffset` 钳制平移。常量 `MIN_SCALE = 1f` / `MAX_SCALE = 5f` / `DOUBLE_TAP_SCALE = 3f`。IMPL-34 修复：原 `transformable` 无边界约束 -> `detectTransformGestures` + `clampOffset`。

### FeedUtils

- `formatRelativeTime`（刚刚 / N分钟前 / N小时前 / N天前 / MM-dd）
- `toImageUri`（逗号分隔多图取第一张，asset 路径 -> `file:///android_asset/`）
- `avatarUriFromSeed`（hash 映射 gallery 8 类别每类 25 张）

### di/FeedImageLoaderModule

`@FeedImageLoader` 限定符，`ImageLoader.Builder` + `SvgDecoder.Factory` + `crossfade(true)`。

## feature-timeline

namespace `com.trae.social.feature.timeline`。依赖 `paging-runtime` / `paging-compose` / `coil-compose` / `coil-svg`。

### TimelineScreen

参数 `onPublishClick` / `onScrollingChange`。`TimelineHeader`（`MonogramAvatar` 48dp systemBlue 圆 + "我"）。`GroupBlock`（dateLabel + `MediaGrid`）。

`MediaGrid` 差异化布局：

- 1张 `SingleImageLayout`（320dp）
- 2张 `TwoImageLayout`（160dp）
- 3张 `ThreeImageLayout`（200dp + 2x100dp）
- 4+张 `GridImageLayout`（3列 max 9 "+N"角标）

`TimelineImageCell`（`SubcomposeAsyncImage` + shimmer）。`EmptyIllustration`（`Canvas` 几何图）。常量 `GRID_COLUMNS = 3` / `GRID_MAX_DISPLAY = 9`。

### TimelineViewModel

`@HiltViewModel` 注入 `TweetRepository`。

```kotlin
val timelineFlow: StateFlow<TimelineUiState> = observeMediaTweets()
    .map(groupTweets)
    .map{ empty -> Empty else Success }
    .catch(Error)
    .stateIn(WhileSubscribed(5000), Loading)
```

`groupTweets` `groupBy LocalDate` `sortedByDescending`。`formatDateLabel`（今天 / 昨天 / N天前 / M月d日 / yyyy年M月d日）。

数据结构：

- `TimelineGroup(date, dateLabel, items)`
- `TimelineItem(tweetId, mediaPath, text, timeLabel, fullText)`

常量 `STOP_TIMEOUT_MILLIS = 5000` / `TIME_PATTERN = "HH:mm"`。

### FullScreenImage（timeline 版）

`FullScreenImageViewer(items, initialIndex, dateLabel)`。`HorizontalPager` + `rememberPagerState` 支持左右翻页。`ZoomableImage` `detectTapGestures onDoubleTap` + `detectTransformGestures`（1x-5x + `clampOffset`）。顶部 dateLabel + close，底部 fullText caption。IMPL-34 修复：单指拖拽透传给 Pager 完成翻页不被 zoom 手势吞掉。
