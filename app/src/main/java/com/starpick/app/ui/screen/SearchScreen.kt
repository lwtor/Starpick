package com.starpick.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starpick.app.AppContainer
import com.starpick.app.core.model.DoubanSubject
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.search.SearchOutcome
import com.starpick.app.ui.component.EmptyState
import com.starpick.app.ui.component.SectionHeader
import com.starpick.app.ui.component.VodCover
import com.starpick.app.ui.theme.Accent
import com.starpick.app.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索：输入 → 所有已启用源并发聚合 → 按源分组展示。
 *
 * 按源分组而不是混排：同一部片在不同源里的集数不同，混排会让人点错。
 */
@Composable
fun SearchScreen(
    container: AppContainer,
    onOpen: (SearchGroup) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val sources by container.store.sources.collectAsState(initial = emptyList())
    val enabledSources = remember(sources) { sources.filter { it.enabled } }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<SearchOutcome?>(null) }
    var loading by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0L) }
    var hot by remember { mutableStateOf<List<DoubanSubject>>(emptyList()) }

    // 空态用豆瓣热门兜底：用户总能一键开始，而不是面对「请输入关键词」。
    LaunchedEffect(Unit) {
        if (hot.isEmpty()) {
            hot = withContext(Dispatchers.IO) { container.douban.hot("movie") }.take(5)
        }
    }

    val runSearch: (String) -> Unit = { raw ->
        val keyword = raw.trim()
        if (keyword.isNotEmpty() && enabledSources.isNotEmpty() && !loading) {
            loading = true
            outcome = null
            scope.launch {
                val started = System.currentTimeMillis()
                val result = withContext(Dispatchers.IO) {
                    container.aggregator.grouped(enabledSources, keyword)
                }
                elapsed = System.currentTimeMillis() - started
                outcome = result
                loading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        SearchField(
            query = query,
            onQueryChange = { query = it },
            onSearch = { runSearch(query) },
            onClear = {
                query = ""
                outcome = null
            },
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

        val result = outcome
        when {
            loading -> {
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

            result != null && result.items.isEmpty() -> {
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
                        query = ""
                        outcome = null
                    },
                )
            }

            result != null -> {
                val bySource = result.items.groupBy { it.sourceName }
                LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
                    item {
                        Text(
                            text = "${enabledSources.size} 个源 · 找到 ${result.items.size} 条 · 用时 ${"%.1f".format(elapsed / 1000.0)} 秒",
                            style = MaterialTheme.typography.labelMedium,
                            color = Ink.Text3,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
                        )
                    }
                    bySource.forEach { (sourceName, items) ->
                        item(key = "h-$sourceName") {
                            SectionHeader(title = sourceName, trailing = "${items.size} 条")
                        }
                        items(items, key = { it.key }) { item ->
                            ResultRow(
                                item = item,
                                onClick = {
                                    val group = result.groups
                                        .firstOrNull { g -> g.items.any { it.key == item.key } }
                                        ?: SearchGroup(item.title, listOf(item))
                                    onOpen(group)
                                },
                            )
                        }
                    }
                }
            }

            else -> {
                // 初始态：豆瓣热门榜前 5，点一下就能搜。
                LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
                    item { SectionHeader(title = "大家都在搜", trailing = "豆瓣热门") }
                    items(hot, key = { it.id }) { s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    query = s.title
                                    runSearch(s.title)
                                }
                                .padding(horizontal = 20.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = (hot.indexOf(s) + 1).toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (hot.indexOf(s) < 3) Accent.Gold else Ink.Text3,
                                modifier = Modifier.width(22.dp),
                            )
                            Text(
                                text = s.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Ink.Text1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (s.rate.isNotBlank()) "豆瓣 ${s.rate}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = Ink.Text3,
                            )
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

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.Card)
            .border(1.dp, Ink.Line, RoundedCornerShape(14.dp))
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = Ink.Text3,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(9.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "搜索片名 / 演员",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Ink.Text3,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Text1),
                    cursorBrush = SolidColor(Accent.Brand),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "清空",
                    tint = Ink.Text3,
                    modifier = Modifier
                        .size(17.dp)
                        .clickable(onClick = onClear),
                )
            }
        }
    }
}

@Composable
private fun ResultRow(item: VodItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VodCover(
            title = item.title,
            cover = item.cover,
            modifier = Modifier.size(width = 44.dp, height = 62.dp),
            shape = RoundedCornerShape(7.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = Ink.Text1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = listOfNotNull(
                    item.year.takeIf { it.isNotBlank() },
                    item.remark.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { "暂无简介" },
                style = MaterialTheme.typography.labelMedium,
                color = Ink.Text3,
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
                text = "详情",
                style = MaterialTheme.typography.labelSmall,
                color = Accent.Brand,
            )
        }
    }
}
