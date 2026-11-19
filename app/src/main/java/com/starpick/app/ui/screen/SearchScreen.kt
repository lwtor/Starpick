package com.starpick.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starpick.app.AppContainer
import com.starpick.app.core.model.DoubanSubject
import com.starpick.app.core.model.MatchAnchor
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.search.SearchOutcome
import com.starpick.app.ui.component.EmptyState
import com.starpick.app.ui.component.SearchBar
import com.starpick.app.ui.component.SectionHeader
import com.starpick.app.ui.component.VodCover
import com.starpick.app.ui.theme.Accent
import com.starpick.app.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一个搜索框同时查两类结果，用 tab 分开看。 */
enum class SearchTab(val label: String) {
    All("全部"),
    Douban("豆瓣"),
    Sources("视频源"),
}

/** 搜索页「全部」里每类先给几条，想看全的进对应 tab。 */
private const val PREVIEW_DOUAN = 3
private const val PREVIEW_SOURCES = 5

/**
 * 统一搜索页状态，由导航层持有：进详情再返回、切页回来都保留关键词、结果和滚动位置。
 *
 * 豆瓣（片库，规范）和视频源（用户的采集站）同一次提交并发查询，谁先回来先显示谁。
 * 两者都不再是独立页面，只是结果的两个维度。
 */
class SearchUiState {
    /** 输入框里的原始文本。 */
    var query by mutableStateOf("")

    /** 已提交的关键词；空串表示还没搜过。 */
    var keyword by mutableStateOf("")
    var tab by mutableStateOf(SearchTab.All)

    var doubanResults by mutableStateOf<List<DoubanSubject>>(emptyList())
    var doubanLoading by mutableStateOf(false)

    var sourceOutcome by mutableStateOf<SearchOutcome?>(null)
    var sourceLoading by mutableStateOf(false)
    var sourceLoadingMore by mutableStateOf(false)
    /** 当前已加载到的页码；下一页返回空才停止翻。 */
    var sourcePage by mutableIntStateOf(1)
    var sourceHasMore by mutableStateOf(false)

    var refreshing by mutableStateOf(false)

    /** 空态兜底的豆瓣热搜，只拉一次。 */
    var hot by mutableStateOf<List<DoubanSubject>>(emptyList())
    var hotLoaded by mutableStateOf(false)
    var hotLoading by mutableStateOf(false)

    val listStates = SearchTab.entries.associateWith { LazyListState() }
    fun listState(tab: SearchTab): LazyListState = listStates.getValue(tab)

    val loading: Boolean get() = doubanLoading || sourceLoading
    val hasResult: Boolean get() = doubanResults.isNotEmpty() || sourceOutcome != null

    /** 有输入或结果时，返回键先清搜索而不是直接退出页面。 */
    val hasSearch: Boolean get() = query.isNotEmpty() || keyword.isNotEmpty() || hasResult

    fun clearSearch() {
        query = ""
        keyword = ""
        tab = SearchTab.All
        doubanResults = emptyList()
        doubanLoading = false
        sourceOutcome = null
        sourceLoading = false
        sourceLoadingMore = false
        sourcePage = 1
        sourceHasMore = false
        refreshing = false
        listStates.values.forEach { it.requestScrollToItem(0) }
    }
}

/**
 * 统一搜索：一个关键词并发查豆瓣条目库和所有已启用的采集源，三个 tab 分维度展示。
 *
 * 「全部」像微信搜索一样只给两类各几条预览，点「更多」进对应 tab 看完整结果；
 * 视频源结果按 CMS 的 pg 参数逐页加载，不再一次性把整站拉出来。
 */
@Composable
fun SearchScreen(
    container: AppContainer,
    state: SearchUiState,
    onOpenDouban: (MatchAnchor) -> Unit,
    onOpenSource: (SearchGroup) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val sources by container.store.sources.collectAsState()
    val history by container.store.searchHistory.collectAsState()
    val enabledSources = remember(sources) { sources.filter { it.enabled } }
    val scope = rememberCoroutineScope()
    val focusManager: FocusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    BackHandler(enabled = state.hasSearch) { state.clearSearch() }
    val handleBack: () -> Unit = { if (state.hasSearch) state.clearSearch() else onBack() }

    val loadHot: (Boolean) -> Unit = { force ->
        if (!state.hotLoading && (force || !state.hotLoaded)) {
            state.hotLoading = true
            if (force) state.refreshing = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { container.douban.hot("movie") }
                if (result.isNotEmpty()) {
                    state.hot = result.take(5)
                    state.hotLoaded = true
                }
                state.hotLoading = false
                state.refreshing = false
            }
        }
    }

    // 空态热搜只拉一次；用户中途去设置里加完源回来不需要重拉（热搜是豆瓣的，与源无关）。
    LaunchedEffect(Unit) { loadHot(false) }

    fun runSearch(raw: String, isRefresh: Boolean) {
        focusManager.clearFocus()
        keyboard?.hide()
        val keyword = raw.trim()
        if (keyword.isEmpty() || state.loading) return
        state.query = keyword
        state.keyword = keyword
        state.refreshing = isRefresh
        // 新搜索从第 1 页重来；下拉刷新保留在当前 tab 的滚动位置。
        state.sourcePage = 1
        state.sourceHasMore = false
        // 换关键词才清空两路旧结果：否则「一路已返回、另一路还在搜」时会露出上一轮的旧数据。
        // 下拉刷新（同一关键词）不清空，避免整页闪成空。
        if (!isRefresh) {
            state.doubanResults = emptyList()
            state.sourceOutcome = null
        }
        state.doubanLoading = true
        state.sourceLoading = true
        scope.launch {
            try {
                container.store.addSearchHistory(keyword)
            } catch (_: Exception) {
                // 历史写失败不影响搜索本身。
            }
            launch {
                try {
                    state.doubanResults = withContext(Dispatchers.IO) {
                        container.douban.search(keyword)
                    }
                } finally {
                    state.doubanLoading = false
                }
            }
            launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        container.aggregator.groupedWithVariants(enabledSources, keyword, 1)
                    }
                    state.sourceOutcome = result
                    state.sourceHasMore = result.items.isNotEmpty()
                } finally {
                    state.sourceLoading = false
                    state.refreshing = false
                }
            }
        }
    }

    val loadMore: () -> Unit = {
        val keyword = state.keyword
        if (keyword.isNotBlank() && enabledSources.isNotEmpty() &&
            state.sourceHasMore && !state.sourceLoadingMore && !state.sourceLoading
        ) {
            state.sourceLoadingMore = true
            scope.launch {
                try {
                    val nextPage = state.sourcePage + 1
                    val page = withContext(Dispatchers.IO) {
                        container.aggregator.grouped(enabledSources, keyword, nextPage)
                    }
                    state.sourcePage = nextPage
                    state.sourceHasMore = page.items.isNotEmpty()
                    state.sourceOutcome = state.sourceOutcome?.let { current ->
                        container.aggregator.merge(listOf(current, page))
                    } ?: page
                } finally {
                    state.sourceLoadingMore = false
                }
            }
        }
    }

    val refresh: () -> Unit = {
        if (state.keyword.isNotEmpty()) runSearch(state.keyword, true) else loadHot(true)
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = handleBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Ink.Text1,
                )
            }
            SearchBar(
                query = state.query,
                placeholder = "搜索片名 / 演员",
                onQueryChange = { state.query = it },
                onSearch = { runSearch(state.query, false) },
                onClear = { state.clearSearch() },
                modifier = Modifier.weight(1f),
            )
        }

        // 未搜索时不显示分类 tab：此时页面只有历史和热门，出现「全部/豆瓣/视频源」反而误导。
        if (state.keyword.isNotEmpty()) {
            SecondaryTabRow(
                selectedTabIndex = state.tab.ordinal,
                containerColor = Ink.Base,
                contentColor = Accent.Brand,
                divider = {},
            ) {
                SearchTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { state.tab = tab },
                        text = { Text(tab.label) },
                        unselectedContentColor = Ink.Text3,
                    )
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = refresh,
            modifier = Modifier.weight(1f),
        ) {
            if (state.keyword.isEmpty()) {
                EntryState(
                    state = state,
                    history = history,
                    sourceCount = enabledSources.size,
                    scope = scope,
                    container = container,
                    onHistory = { runSearch(it, false) },
                )
                return@PullToRefreshBox
            }

            when (state.tab) {
                SearchTab.All -> AllResults(
                    state = state,
                    sourceCount = enabledSources.size,
                    onTab = { state.tab = it },
                    onOpenDouban = onOpenDouban,
                    onOpenSource = onOpenSource,
                    onReset = { state.clearSearch() },
                )

                SearchTab.Douban -> DoubanResults(
                    state = state,
                    onOpen = onOpenDouban,
                    onReset = { state.clearSearch() },
                )

                SearchTab.Sources -> SourceResults(
                    state = state,
                    enabledSources = enabledSources,
                    onOpen = onOpenSource,
                    onOpenSettings = onOpenSettings,
                    onLoadMore = loadMore,
                    onReset = { state.clearSearch() },
                )
            }
        }
    }
}

/** 未搜索：历史 + 豆瓣热门，点一下直接发起统一搜索。 */
@Composable
private fun EntryState(
    state: SearchUiState,
    history: List<String>,
    sourceCount: Int,
    scope: kotlinx.coroutines.CoroutineScope,
    container: AppContainer,
    onHistory: (String) -> Unit,
) {
    LazyColumn(
        state = state.listState(SearchTab.All),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        if (history.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("搜索历史", style = MaterialTheme.typography.titleSmall, color = Ink.Text1)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "清空",
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink.Text3,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { scope.launch { container.store.clearSearchHistory() } }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    history.forEach { keyword ->
                        HistoryChip(text = keyword) { onHistory(keyword) }
                    }
                }
            }
        }

        item { SectionHeader(title = "大家都在搜", trailing = "豆瓣热门") }
        items(state.hot, key = { "hot-" + it.id }) { subject ->
            SubjectRow(subject = subject, onClick = { onHistory(subject.title) })
        }
        item {
            Text(
                text = "搜索会同时查豆瓣片库和你的 $sourceCount 个采集源",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.Text3,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            )
        }
    }
}

/** 微信式「全部」：两类各给几条，点更多进对应 tab；每路独立加载、谁先回来先显示。 */
@Composable
private fun AllResults(
    state: SearchUiState,
    sourceCount: Int,
    onTab: (SearchTab) -> Unit,
    onOpenDouban: (MatchAnchor) -> Unit,
    onOpenSource: (SearchGroup) -> Unit,
    onReset: () -> Unit,
) {
    val groups = state.sourceOutcome?.groups.orEmpty()
    LazyColumn(
        state = state.listState(SearchTab.All),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        // 豆瓣分区：结果已到就显示，没到就只在这一块转圈，不挡另一路。
        if (state.doubanResults.isNotEmpty()) {
            item {
                PreviewHeader(title = "豆瓣影视", count = state.doubanResults.size) {
                    onTab(SearchTab.Douban)
                }
            }
            items(state.doubanResults.take(PREVIEW_DOUAN), key = { "all-douban-" + it.id }) { subject ->
                SubjectRow(subject = subject, onClick = { onOpenDouban(MatchAnchor.from(subject)) })
            }
        } else if (state.doubanLoading) {
            item { SearchLoading("正在搜豆瓣片库…") }
        }

        // 视频源分区：与豆瓣完全独立，先出结果的先显示，另一路继续 loading。
        if (groups.isNotEmpty()) {
            item {
                PreviewHeader(title = "视频源", count = groups.size) { onTab(SearchTab.Sources) }
            }
            items(groups.take(PREVIEW_SOURCES), key = { "all-source-" + it.items.first().key }) { group ->
                GroupRow(group = group, onClick = { onOpenSource(group) })
            }
        } else if (state.sourceLoading) {
            item { SearchLoading("正在搜 $sourceCount 个视频源…") }
        }

        // 两路都结束且都没有结果，才给整页空态。
        if (!state.loading && state.doubanResults.isEmpty() && groups.isEmpty()) {
            item {
                EmptyState(
                    title = "没找到相关结果",
                    description = "试试更短的关键词，或换个片名",
                    actionLabel = "换个关键词",
                    onAction = onReset,
                )
            }
        }

        if (state.doubanResults.isNotEmpty() || groups.isNotEmpty()) {
            item {
                Text(
                    text = buildString {
                        append("豆瓣 ${state.doubanResults.size} 条")
                        append(" · 视频源 ${groups.size} 部 / $sourceCount 个源")
                        if (state.doubanLoading || state.sourceLoading) append(" · 仍在搜索…")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.Text3,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                )
            }
        }
    }
}

/** 豆瓣 tab：完整 suggest 结果（该接口一次返回一批，不分页）。 */
@Composable
private fun DoubanResults(
    state: SearchUiState,
    onOpen: (MatchAnchor) -> Unit,
    onReset: () -> Unit,
) {
    when {
        state.doubanLoading && state.doubanResults.isEmpty() -> {
            SearchLoading("正在搜豆瓣片库…", modifier = Modifier.padding(top = 24.dp))
        }

        state.doubanResults.isEmpty() -> {
            LazyColumn(state = state.listState(SearchTab.Douban)) {
                item {
                    EmptyState(
                        title = "豆瓣没搜到",
                        description = "换个片名试试，或去「视频源」tab 直接找资源",
                        actionLabel = "清空",
                        onAction = onReset,
                    )
                }
            }
        }

        else -> LazyColumn(
            state = state.listState(SearchTab.Douban),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            item {
                Text(
                    text = "豆瓣找到 ${state.doubanResults.size} 部 · 点一部去你的源里找资源",
                    style = MaterialTheme.typography.labelMedium,
                    color = Ink.Text3,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
                )
            }
            items(state.doubanResults, key = { it.id + it.title }) { subject ->
                SubjectRow(subject = subject, onClick = { onOpen(MatchAnchor.from(subject)) })
            }
        }
    }
}

/** 视频源 tab：按 CMS 的 pg 逐页加载，空页为止。 */
@Composable
private fun SourceResults(
    state: SearchUiState,
    enabledSources: List<com.starpick.app.core.model.SourceSite>,
    onOpen: (SearchGroup) -> Unit,
    onOpenSettings: () -> Unit,
    onLoadMore: () -> Unit,
    onReset: () -> Unit,
) {
    when {
        enabledSources.isEmpty() -> {
            EmptyState(
                title = "还没有可用的采集源",
                description = "添加一个苹果 CMS V10 站点后才能搜片源，豆瓣搜索不受影响",
                actionLabel = "去添加采集源",
                onAction = onOpenSettings,
            )
        }

        state.sourceLoading && state.sourceOutcome == null -> {
            SearchLoading("正在搜 ${enabledSources.size} 个视频源…", modifier = Modifier.padding(top = 16.dp))
        }

        state.sourceOutcome?.groups.isNullOrEmpty() && !state.sourceLoading -> {
            LazyColumn(state = state.listState(SearchTab.Sources)) {
                item {
                    EmptyState(
                        title = "没找到这部片",
                        description = "换一个更短的关键词试试",
                        actionLabel = "换个关键词",
                        onAction = onReset,
                    )
                }
            }
        }

        else -> {
            val outcome = state.sourceOutcome ?: return
            val groups = outcome.groups
            LazyColumn(
                state = state.listState(SearchTab.Sources),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                item {
                    Text(
                        text = buildString {
                            append("找到 ${groups.size} 部 · ${enabledSources.size} 个源")
                            append(" · 第 ${state.sourcePage} 页")
                            if (outcome.failedSources.isNotEmpty()) {
                                append(" · ${outcome.failedSources.size} 个源超时")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink.Text3,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                    )
                }
                items(groups, key = { it.title + it.items.first().key }) { group ->
                    GroupRow(group = group, onClick = { onOpen(group) })
                }
                item {
                    when {
                        state.sourceLoadingMore -> {
                            SearchLoading("正在加载更多…")
                        }

                        state.sourceHasMore -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 16.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Ink.Card)
                                    .border(1.dp, Ink.Line, RoundedCornerShape(12.dp))
                                    .clickable(onClick = onLoadMore)
                                    .padding(vertical = 13.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("加载更多", style = MaterialTheme.typography.labelLarge, color = Accent.Brand)
                            }
                        }

                        groups.isNotEmpty() -> {
                            Text(
                                text = "已经到底了",
                                style = MaterialTheme.typography.labelSmall,
                                color = Ink.Text3,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewHeader(title: String, count: Int, onMore: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, color = Ink.Text1)
        Spacer(Modifier.width(6.dp))
        Text(text = "$count", style = MaterialTheme.typography.labelSmall, color = Ink.Text3)
        Spacer(Modifier.weight(1f))
        Text(
            text = "更多 ›",
            style = MaterialTheme.typography.labelMedium,
            color = Accent.Brand,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onMore)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** 豆瓣条目行：海报 + 片名 + 年份/原名，右侧提示「去源里找」。 */
@Composable
private fun SubjectRow(subject: DoubanSubject, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VodCover(
            title = subject.title,
            cover = subject.cover,
            modifier = Modifier.size(width = 44.dp, height = 62.dp),
            shape = RoundedCornerShape(7.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = subject.title,
                style = MaterialTheme.typography.titleSmall,
                color = Ink.Text1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subject.episodesInfo.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subject.episodesInfo,
                    style = MaterialTheme.typography.labelMedium,
                    color = Ink.Text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Ink.Line, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text("找资源", style = MaterialTheme.typography.labelSmall, color = Ink.Text2)
        }
    }
}

/** 视频源结果：同片名聚成一行，来源小字放行内。 */
@Composable
private fun GroupRow(group: SearchGroup, onClick: () -> Unit) {
    val head = group.items.first()
    val sourceNames = group.items.map { it.sourceName }.distinct()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VodCover(
            title = head.title,
            cover = group.items.firstOrNull { it.cover.isNotBlank() }?.cover.orEmpty(),
            modifier = Modifier.size(width = 44.dp, height = 62.dp),
            shape = RoundedCornerShape(7.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleSmall,
                color = Ink.Text1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = listOfNotNull(
                    head.year.takeIf { it.isNotBlank() },
                    group.items.firstOrNull { it.remark.isNotBlank() }?.remark,
                ).joinToString(" · ").ifBlank { "暂无简介" },
                style = MaterialTheme.typography.labelMedium,
                color = Ink.Text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = sourceNames.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (sourceNames.size > 1) Accent.Brand else Ink.Text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color(0x594C8DFF), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text = if (sourceNames.size > 1) "${sourceNames.size} 源" else "详情",
                style = MaterialTheme.typography.labelSmall,
                color = Accent.Brand,
            )
        }
    }
}

/**
 * 统一搜索 loading：三个依次呼吸的品牌蓝圆点 + 一行说明。
 * 比裸进度条更轻、更聚焦，且同一时刻整页只出现一两处，视觉不吵。
 */
@Composable
private fun SearchLoading(label: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "search-loading")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 720, delayMillis = index * 160),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot-$index",
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Accent.Brand.copy(alpha = alpha)),
            )
            if (index < 2) Spacer(Modifier.width(6.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Ink.Text2,
        )
    }
}

@Composable
private fun HistoryChip(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = Ink.Text2,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Ink.Card)
            .border(1.dp, Ink.Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
