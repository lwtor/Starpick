package com.starpick.app.ui.screen

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starpick.app.AppContainer
import com.starpick.app.core.model.DoubanSubject
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

/**
 * 搜索页 UI 状态，由导航层持有。
 *
 * 为什么不 remember 在页面里：进详情再返回、切 tab 再切回都会让本页 Composable 重建，
 * 页面内的 remember 全丢，用户就得重新搜一遍。放在导航层后：
 * 进来默认显示上一次的结果，只有下拉才重新请求。
 */
class SearchUiState {
    /** 输入框里的原始文本。 */
    var query by mutableStateOf("")

    /** 已提交的关键词；空串表示还没搜过。 */
    var keyword by mutableStateOf("")
    var outcome by mutableStateOf<SearchOutcome?>(null)
    var elapsed by mutableLongStateOf(0L)
    var loading by mutableStateOf(false)
    var refreshing by mutableStateOf(false)

    /** 空态兜底的豆瓣热搜，只在首次进入拉一次。 */
    var hot by mutableStateOf<List<DoubanSubject>>(emptyList())
    var hotLoaded by mutableStateOf(false)
    var hotLoading by mutableStateOf(false)

    val listState = LazyListState()

    /** 有输入或结果时，返回键先清搜索而不是直接退出页面。 */
    val hasSearch: Boolean get() = query.isNotEmpty() || keyword.isNotEmpty() || outcome != null

    fun clearSearch() {
        query = ""
        keyword = ""
        outcome = null
        elapsed = 0L
        loading = false
        refreshing = false
        listState.requestScrollToItem(0)
    }
}

/**
 * 搜索：输入 → 所有已启用源并发聚合 → **按片名去重**后一行一部。
 *
 * 不再按源分组罗列：同一个片名在 5 个源里各出一条，列表会瞬间膨胀成几十行，
 * 用户实际只关心「有没有这部片」。来源信息收进行内一行小字，换源交给详情页。
 */
@Composable
fun SearchScreen(
    container: AppContainer,
    state: SearchUiState,
    onOpen: (SearchGroup) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val sources by container.store.sources.collectAsState()
    val history by container.store.searchHistory.collectAsState()
    val enabledSources = remember(sources) { sources.filter { it.enabled } }
    val scope = rememberCoroutineScope()
    val focusManager: FocusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // 有搜索内容时，返回键只清空搜索，不退出页面；再按一次才轮到导航层处理退出。
    BackHandler(enabled = state.hasSearch) { state.clearSearch() }

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

    // 空态用豆瓣热门兜底：用户总能一键开始，而不是面对「请输入关键词」。
    // 依赖「有没有源」而不是 Unit：用户中途去设置里加完源回来才会真正去拉。
    LaunchedEffect(enabledSources.isNotEmpty()) {
        if (enabledSources.isNotEmpty()) loadHot(false)
    }

    val runSearch: (String, Boolean) -> Unit = { raw, isRefresh ->
        focusManager.clearFocus()
        keyboard?.hide()
        val keyword = raw.trim()
        if (keyword.isNotEmpty() && enabledSources.isNotEmpty() && !state.loading) {
            state.loading = true
            state.refreshing = isRefresh
            state.query = keyword
            state.keyword = keyword
            scope.launch {
                try {
                    container.store.addSearchHistory(keyword)
                    val started = System.currentTimeMillis()
                    val result = withContext(Dispatchers.IO) {
                        container.aggregator.grouped(enabledSources, keyword)
                    }
                    state.elapsed = System.currentTimeMillis() - started
                    state.outcome = result
                    // 新鲜的一次搜索回到顶部；下拉刷新保持在原位。
                    if (!isRefresh) runCatching { state.listState.scrollToItem(0) }
                } finally {
                    state.loading = false
                    state.refreshing = false
                }
            }
        }
    }

    // 下拉：有搜索结果就重搜上一次的关键词，否则重拉豆瓣热搜。
    val refresh: () -> Unit = {
        if (state.keyword.isNotEmpty()) runSearch(state.keyword, true) else loadHot(true)
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        SearchBar(
            query = state.query,
            placeholder = "搜索片名 / 演员",
            onQueryChange = { state.query = it },
            onSearch = { runSearch(state.query, false) },
            onClear = { state.clearSearch() },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        if (enabledSources.isEmpty()) {
            EmptyState(
                title = "还没有可用的采集源",
                description = "添加一个苹果 CMS V10 站点后才能搜索",
                actionLabel = "去添加采集源",
                onAction = onOpenSettings,
            )
            return@Column
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = refresh,
            modifier = Modifier.weight(1f),
        ) {
            val result = state.outcome
            when {
                // 下拉刷新时保留旧结果，只让顶部指示器转，别把列表抽走。
                state.loading && !state.refreshing -> {
                    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            color = Accent.Brand,
                            trackColor = Ink.Line,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "正在聚合 ${enabledSources.size} 个源…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink.Text2,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }

                result != null && result.groups.isEmpty() -> {
                    LazyColumn(state = state.listState) {
                        item {
                            EmptyState(
                                title = "没找到这部片",
                                description = buildString {
                                    append("换一个更短的关键词试试")
                                    if (result.failedSources.isNotEmpty()) {
                                        append(" · ${result.failedSources.joinToString("、")} 超时")
                                    }
                                },
                                actionLabel = "换个关键词",
                                onAction = {
                                    state.query = ""
                                    state.keyword = ""
                                    state.outcome = null
                                },
                            )
                        }
                    }
                }

                result != null -> {
                    ResultList(
                        groups = result.groups,
                        sourceCount = enabledSources.size,
                        elapsed = state.elapsed,
                        failedSources = result.failedSources,
                        listState = state.listState,
                        onOpen = onOpen,
                    )
                }

                else -> {
                    // 初始态：搜索历史（有才显示）+ 豆瓣热门前 5，点一下就能搜。
                    LazyColumn(
                        state = state.listState,
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
                                    Text(
                                        text = "搜索历史",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Ink.Text1,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        text = "清空",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Ink.Text3,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                scope.launch { container.store.clearSearchHistory() }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                            item {
                                FlowRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    history.forEach { keyword ->
                                        HistoryChip(text = keyword) {
                                            state.query = keyword
                                            runSearch(keyword, false)
                                        }
                                    }
                                }
                            }
                        }

                        item { SectionHeader(title = "大家都在搜", trailing = "豆瓣热门") }
                        items(state.hot, key = { it.id }) { s ->
                            HotRow(
                                index = state.hot.indexOf(s),
                                title = s.title,
                                rate = s.rate,
                            ) {
                                state.query = s.title
                                runSearch(s.title, false)
                            }
                        }
                        item {
                            Text(
                                text = "搜索会在你已启用的 ${enabledSources.size} 个采集源里同时进行",
                                style = MaterialTheme.typography.labelSmall,
                                color = Ink.Text3,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 结果列表：一部片一行。
 *
 * 行内三行字分别是「片名 / 年份+更新信息 / 有哪些源提供」，把原来按源分组导致的
 * 重复条目压成一条，多源时列表长度直接降到 1/N。
 */
@Composable
private fun ResultList(
    groups: List<SearchGroup>,
    sourceCount: Int,
    elapsed: Long,
    failedSources: List<String>,
    listState: LazyListState,
    onOpen: (SearchGroup) -> Unit,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item {
            Text(
                text = buildString {
                    append("找到 ${groups.size} 部")
                    append(" · $sourceCount 个源")
                    append(" · ${"%.1f".format(elapsed / 1000.0)} 秒")
                    if (failedSources.isNotEmpty()) append(" · ${failedSources.size} 个源超时")
                },
                style = MaterialTheme.typography.labelMedium,
                color = Ink.Text3,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
            )
        }
        items(groups, key = { it.title + it.items.first().key }) { group ->
            GroupRow(group = group, onClick = { onOpen(group) })
        }
    }
}

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

@Composable
private fun HotRow(index: Int, title: String, rate: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = (index + 1).toString(),
            style = MaterialTheme.typography.labelLarge,
            color = if (index < 3) Accent.Gold else Ink.Text3,
            modifier = Modifier.width(22.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = Ink.Text1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (rate.isNotBlank()) "豆瓣 $rate" else "",
            style = MaterialTheme.typography.labelSmall,
            color = Ink.Text3,
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
