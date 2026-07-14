# PR 145 UI 显示问题修复计划

## 摘要

基于对 PR 145 分支（`fix/claimed-issues-84-91-107-121-122-127-129-130`）的代码审查，当前 Feed 页面存在 Material3 `PullToRefreshBox` 误用导致的布局/交互异常，是用户反馈"UI 不能正确显示"的最可能根因。本计划通过重构 `FeedScreen.kt` 的下拉刷新结构，将 `PullToRefreshBox` 仅应用于列表状态，避免其包裹 `Crossfade` 带来的内容无法滚动、空态无法刷新、列表尺寸计算异常等问题。

## 当前状态分析

### 1. FeedScreen 下拉刷新结构不当

[feature-feed/src/main/java/com/trae/social/feed/FeedScreen.kt](file:///workspace/feature-feed/src/main/java/com/trae/social/feed/FeedScreen.kt) 当前结构：

```kotlin
Column(modifier = modifier.fillMaxSize()) {
    if (isOnboardingSkipped) { OnboardingSkippedBanner(...) }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { pagingItems.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        Crossfade(targetState = contentKey) { key ->
            when (key) {
                "loading" -> LoadingPlaceholderList()
                "error" -> ErrorPlaceholder(...)
                "empty" -> EmptyPlaceholder()
                else -> FeedList(...)
            }
        }
    }
}
```

问题：
- `PullToRefreshBox` 通过 `NestedScrollConnection` 监听子组件滚动以触发刷新手势，其直接子组件应为可滚动容器（`LazyColumn`）。
- 当前直接子组件是 `Crossfade`，在 `loading`/`error`/`empty` 状态下渲染的是不可滚动占位（`Column`/`Box`），导致下拉刷新手势无法识别，且可能因内容尺寸变化引起 `PullToRefreshBox` 测量异常。
- `Crossfade` 自身未指定 `fillMaxSize()`，过渡期间各状态尺寸可能不一致，进一步加剧列表显示异常或空白。

### 2. 不感兴趣过滤的双重过滤与响应性

[feature-feed/src/main/java/com/trae/social/feed/FeedViewModel.kt](file:///workspace/feature-feed/src/main/java/com/trae/social/feed/FeedViewModel.kt) 中 `feedFlow` 已使用 `PagingData.filter` 过滤不感兴趣推文：

```kotlin
val feedFlow: Flow<PagingData<TweetWithAuthor>> = tweetRepository.getFeedFlow()
    .map { pagingData ->
        val hidden = _notInterestedTweetIds.value
        pagingData
            .filter { it.id !in hidden }
            .map { tweet -> resolveAuthor(tweet) }
    }
```

同时在 [feature-feed/src/main/java/com/trae/social/feed/FeedScreen.kt](file:///workspace/feature-feed/src/main/java/com/trae/social/feed/FeedScreen.kt) 的 `FeedList` 中又做了一次 UI 层过滤：

```kotlin
if (item.tweet.id !in notInterestedIds) {
    TweetCard(...)
}
```

问题：
- UI 层过滤会导致 `LazyColumn` 保留被隐藏项的占位（itemCount 不变但部分 index 不渲染），表现为列表中出现空白间隙或滚动跳跃。
- `feedFlow` 仅在 `PagingSource` 重新加载时重新过滤；`_notInterestedTweetIds` 变化不会自动触发新的 `PagingData` 发射，需要依赖 `pagingItems.refresh()` 才能持久生效。

### 3. 已确认无问题的部分

- `SettingsScreen` 已接受 `onNavigateToProfile` 参数（带默认值），与 `MainActivity` 调用点匹配。
- `TweetCard` 已新增 `onNotInterestedClick` 回调并正确调用。
- `ConfigRepository` 已新增 `bookmarked/notInterested` 推文 ID 集合的 DataStore 存取方法。
- `SocialBottomBar` 的玻璃态背景和 `navigationBarsPadding` 布局已正确。

## 拟议修改

### 修改 1：重构 FeedScreen 下拉刷新结构（主要修复）

目标文件：[feature-feed/src/main/java/com/trae/social/feed/FeedScreen.kt](file:///workspace/feature-feed/src/main/java/com/trae/social/feed/FeedScreen.kt)

将 `Crossfade` 移到 `PullToRefreshBox` 外部，`PullToRefreshBox` 仅在 `"list"` 状态时包裹 `FeedList`。`loading`/`error`/`empty` 状态直接渲染全屏占位，不启用下拉刷新（错误/空状态通过各自的重试按钮刷新）。

修改后结构：

```kotlin
Column(modifier = modifier.fillMaxSize()) {
    if (isOnboardingSkipped) {
        OnboardingSkippedBanner(onNavigateToSettings = onNavigateToSettings)
    }
    val contentKey = when {
        isInitialLoading -> "loading"
        isError -> "error"
        isEmpty -> "empty"
        else -> "list"
    }
    val errorMessage = (refreshState as? LoadState.Error)?.error?.message ?: "加载失败"
    Crossfade(
        targetState = contentKey,
        animationSpec = tween(300),
        label = "feed-state",
        modifier = Modifier.fillMaxSize(),
    ) { key ->
        when (key) {
            "loading" -> LoadingPlaceholderList()
            "error" -> ErrorPlaceholder(
                message = errorMessage,
                onRetry = { pagingItems.retry() },
            )
            "empty" -> EmptyPlaceholder()
            else -> PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { pagingItems.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                FeedList(
                    pagingItems = pagingItems,
                    likedIds = likedIds,
                    bookmarkedIds = bookmarkedIds,
                    notInterestedIds = notInterestedIds,
                    imageLoader = viewModel.imageLoader,
                    onImageClick = { uris, index -> fullScreenTarget = FullScreenImageTarget(uris, index) },
                    onLikeClick = { viewModel.likeTweet(it.tweet.id) },
                    onCommentClick = { commentTarget = it },
                    onRetweetClick = { retweetTarget = it },
                    onBookmarkClick = { viewModel.bookmarkTweet(it.tweet.id) },
                    onNotInterestedClick = { viewModel.markNotInterested(it.tweet.id) },
                    onScrollingChange = onScrollingChange,
                )
            }
        }
    }
}
```

### 修改 2：移除 FeedList 中的冗余 UI 层过滤

目标文件：[feature-feed/src/main/java/com/trae/social/feed/FeedScreen.kt](file:///workspace/feature-feed/src/main/java/com/trae/social/feed/FeedScreen.kt)

删除 `FeedList` items 中的 `if (item.tweet.id !in notInterestedIds)` 条件渲染，让 `TweetCard` 始终渲染。`ViewModel` 层的 `PagingData.filter` 已负责持久过滤，避免列表出现空白占位。

修改后：

```kotlin
items(
    count = pagingItems.itemCount,
    key = pagingItems.itemKey { it.tweet.id },
) { index ->
    pagingItems[index]?.let { item ->
        TweetCard(
            data = item,
            isLiked = item.tweet.id in likedIds,
            isBookmarked = item.tweet.id in bookmarkedIds,
            imageLoader = imageLoader,
            onImageClick = onImageClick,
            onLikeClick = { onLikeClick(item) },
            onCommentClick = { onCommentClick(item) },
            onRetweetClick = { onRetweetClick(item) },
            onBookmarkClick = { onBookmarkClick(item) },
            onNotInterestedClick = { onNotInterestedClick(item) },
            modifier = Modifier.animateItem(),
        )
    }
}
```

### 修改 3：让 feedFlow 自动响应不感兴趣集合变化（可选但推荐）

目标文件：[feature-feed/src/main/java/com/trae/social/feed/FeedViewModel.kt](file:///workspace/feature-feed/src/main/java/com/trae/social/feed/FeedViewModel.kt)

将 `feedFlow` 与 `_notInterestedTweetIds` 组合，使得用户点击"不感兴趣"后无需手动下拉刷新即可自动重新过滤当前分页数据。

实现方式：

```kotlin
val feedFlow: Flow<PagingData<TweetWithAuthor>> =
    _notInterestedTweetIds
        .flatMapLatest { hidden ->
            tweetRepository.getFeedFlow().map { pagingData ->
                pagingData
                    .filter { it.id !in hidden }
                    .map { tweet -> resolveAuthor(tweet) }
            }
        }
        .cachedIn(viewModelScope)
```

注意：引入 `cachedIn(viewModelScope)` 以保持 Paging 在配置变更时的状态。

### 修改 4：空状态支持下拉刷新（可选）

如果产品要求空状态也可下拉刷新，则需为 `EmptyPlaceholder` 包装一个可滚动的容器（如 `verticalScroll`），并将其放入 `PullToRefreshBox`。但基于当前设计，空状态已有"去发布"引导，且错误状态有重试按钮，因此优先采用修改 1 的方案。

## 假设与决策

1. 用户反馈的"UI 不能正确显示"主要指向 Feed 首页的内容区域空白/下拉刷新异常/列表布局异常。若实际现象不同，可在计划批准后调整。
2. 采用 `flatMapLatest` 组合 `_notInterestedTweetIds` 会触发新的 `PagingData` 发射，可能伴随列表轻微闪烁；这是为了让过滤即时生效而接受的 trade-off。
3. 不处理 PR 145 review 中提到的其他 Minor 问题（如 `FollowListScreen.onAccountClick` 空实现、`PersonaUpdateWorker` N+1 等），以保持本次修复聚焦于 UI 显示问题。
4. 由于环境无 Android SDK，无法运行 Gradle 构建验证；修复将基于代码静态正确性和 Compose 布局约定。

## 验证步骤

1. 代码静态检查：
   - 确认 `FeedScreen` 中 `PullToRefreshBox` 仅包裹 `FeedList`。
   - 确认 `Crossfade` 有 `Modifier.fillMaxSize()`。
   - 确认 `FeedList` 不再包含 `notInterestedIds` 相关的 UI 层过滤。
   - 确认 `FeedViewModel.feedFlow` 正确引用 `_notInterestedTweetIds` 并缓存于 `viewModelScope`。

2. 引用一致性检查：
   - 确认 `FeedList` 调用点参数数量与定义一致。
   - 确认 `TweetCard` 的 `onNotInterestedClick` 调用链完整。

3. 构建验证（在有 Android SDK 的环境）：
   - 运行 `./gradlew :feature-feed:compileDebugKotlin` 确认模块编译通过。
   - 安装 APK 到设备/模拟器，验证：
     - Feed 页首次加载显示 shimmer 占位。
     - 有数据时正常显示推文列表。
     - 列表可下拉刷新，刷新指示器正常出现。
     - 点击"不感兴趣"后该推文立即消失，且无空白占位。
     - 数据为空时显示空状态占位。
     - 数据加载失败时显示错误状态并可重试。
