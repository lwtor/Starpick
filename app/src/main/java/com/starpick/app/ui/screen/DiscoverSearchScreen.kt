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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starpick.app.AppContainer
import com.starpick.app.core.model.DoubanSubject
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
 * 豆瓣搜索页的状态，同样由导航层持有 —— 点进详情再退回来，
 * 关键词和结果都还在，不会重新查一遍。
 */
class DoubanSearchUiState {
    var query by mutableStateOf("")
    var keyword by mutableStateOf("")
    var results by mutableStateOf<List<DoubanSubject>>(emptyList())
    var loading by mutableStateOf(false)
    var refreshing by mutableStateOf(false)

    /** 豆瓣热搜，只用于「还没输入」时的推荐位。 */
    var hot by mutableStateOf<List<DoubanSubject>>(emptyList())
    var hotLoaded by mutableStateOf(false)

    val listState = LazyListState()

    /** 有输入或结果时，返回键先清搜索而不是退出本页。 */
    val hasSearch: Boolean get() = query.isNotEmpty() || keyword.isNotEmpty() || results.isNotEmpty()

    fun clearSearch() {
        query = ""
        keyword = ""
        results = emptyList()
        loading = false
        refreshing = false
        listState.requestScrollToItem(0)
    }
}

/**
 * 独立搜索页：**只查豆瓣**，不碰用户的采集源。
 *
 * 与底部「搜索」tab 的分工：
 *  - 这里是「我想看什么」——豆瓣条目库，片名、海报、年份都规范，用来发现和挑片；
 *  - 底部搜索是「我已知片名，去我的源里找资源」——聚合所有采集源。
 * 两者点进去的终点一致：拿片名去源里解析，再进详情。
 */
@Composable
fun DiscoverSearchScreen(
    container: AppContainer,
    state: DoubanSearchUiState,
    onPick: (title: String) -> Unit,
    onBack: () -> Unit,
) {
    val history by container.store.searchHistory.collectAsState()
    val scope = rememberCoroutineScope()

    // 系统返回键：有搜索内容就先清空，不退出本页。
    BackHandler(enabled = state.hasSearch) { state.clearSearch() }
    // 顶部返回箭头遵循同样规则，避免两种返回方式行为不一致。
    val handleBack: () -> Unit = {
        if (state.hasSearch) state.clearSearch() else onBack()
    }

    val search: (String, Boolean) -> Unit = { raw, isRefresh ->
        val keyword = raw.trim()
        if (keyword.isNotEmpty() && !state.loading) {
            state.loading = true
            state.refreshing = isRefresh
            state.query = keyword
            state.keyword = keyword
            scope.launch {
                try {
                    // 搜过才写历史，避免每敲一个字都留一条。
                    container.store.addSearchHistory(keyword)
                    val result = withContext(Dispatchers.IO) { container.douban.search(keyword) }
                    state.results = result
                    if (!isRefresh) runCatching { state.listState.scrollToItem(0) }
                } finally {
                    state.loading = false
                    state.refreshing = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!state.hotLoaded) {
            val hot = withContext(Dispatchers.IO) { container.douban.hot("movie") }
            if (hot.isNotEmpty()) {
                state.hot = hot.take(10)
                state.hotLoaded = true
            }
        }
    }

    val refresh: () -> Unit = {
        if (state.keyword.isNotEmpty()) {
            search(state.keyword, true)
        } else {
            state.refreshing = true
            scope.launch {
                val hot = withContext(Dispatchers.IO) { container.douban.hot("movie") }
                if (hot.isNotEmpty()) {
                    state.hot = hot.take(10)
                    state.hotLoaded = true
                }
                state.refreshing = false
            }
        }
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
                placeholder = "搜索豆瓣影视",
                onQueryChange = { state.query = it },
                onSearch = { search(state.query, false) },
                onClear = { state.clearSearch() },
                modifier = Modifier.weight(1f),
            )
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = refresh,
            modifier = Modifier.weight(1f),
        ) {
            when {
                // 下拉刷新时保留旧结果，只让顶部指示器转。
                state.loading && !state.refreshing -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        CircularProgressIndicator(
                            color = Accent.Brand,
                            strokeWidth = 2.dp,
                            modifier = Modifier.padding(top = 40.dp).size(24.dp),
                        )
                    }
                }

                state.keyword.isNotEmpty() && state.results.isEmpty() -> {
                    LazyColumn(state = state.listState) {
                        item {
                            EmptyState(
                                title = "豆瓣没搜到",
                                description = "换个片名或关键词试试，也可以直接去底部搜索里找源",
                                actionLabel = "清空",
                                onAction = {
                                    state.query = ""
                                    state.keyword = ""
                                },
                            )
                        }
                    }
                }

                state.results.isNotEmpty() -> {
                    LazyColumn(
                        state = state.listState,
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        item {
                            Text(
                                text = "豆瓣找到 ${state.results.size} 部 · 点一部去你的源里找资源",
                                style = MaterialTheme.typography.labelMedium,
                                color = Ink.Text3,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
                            )
                        }
                        items(state.results, key = { it.id + it.title }) { subject ->
                            SubjectRow(subject = subject, onClick = { onPick(subject.title) })
                        }
                    }
                }

                else -> {
                    // 未输入：历史 + 热搜，给一个「不用打字也能开始」的入口。
                    LazyColumn(
                        state = state.listState,
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        if (history.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp),
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
                                            search(keyword, false)
                                        }
                                    }
                                }
                            }
                        }

                        item { SectionHeader(title = "豆瓣热门", trailing = "编辑推荐") }
                        items(state.hot, key = { "hot-" + it.id }) { subject ->
                            SubjectRow(subject = subject, onClick = { onPick(subject.title) })
                        }
                    }
                }
            }
        }
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
            Text(
                text = "找资源",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.Text2,
            )
        }
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
