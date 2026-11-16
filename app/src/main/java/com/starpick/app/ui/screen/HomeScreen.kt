package com.starpick.app.ui.screen

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.starpick.app.AppContainer
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.WatchProgress
import com.starpick.app.ui.component.EmptyState
import com.starpick.app.ui.component.FlowPosterGrid
import com.starpick.app.ui.component.MediaRow
import com.starpick.app.ui.component.PosterCard
import com.starpick.app.ui.component.SectionHeader
import com.starpick.app.ui.component.SegmentedTabs
import com.starpick.app.ui.component.UpdateCard
import com.starpick.app.ui.component.formatPosition
import com.starpick.app.ui.theme.Ink

/**
 * 片匣：继续观看（列表）+ 收藏（海报网格）。
 *
 * 「继续观看」放第一位且用列表而非网格：一行里能同时给出进度条、剩余时长和上次观看时间，
 * 这是回访决策需要的全部信息。
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpen: (SearchGroup) -> Unit,
    onResume: (VodItem, WatchProgress) -> Unit,
) {
    val favorites by container.store.favorites.collectAsState(initial = emptyList())
    val progress by container.store.progress.collectAsState(initial = emptyList())
    val recent by container.store.recent.collectAsState(initial = emptyList())

    var tabIndex by remember { mutableIntStateOf(0) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        // 自升级入口：仅在发现新版本或已下载时显示（UpdateCard 内部自行判断）。
        item { UpdateCard(container) }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "片匣",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Ink.Text1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${favorites.size} 部收藏",
                    style = MaterialTheme.typography.labelMedium,
                    color = Ink.Text3,
                )
            }
        }

        item {
            Row(modifier = Modifier.padding(horizontal = 20.dp)) {
                SegmentedTabs(
                    options = listOf("继续观看", "收藏"),
                    selectedIndex = tabIndex,
                    onSelect = { tabIndex = it },
                )
            }
        }

        if (tabIndex == 0) {
            if (progress.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有观看记录",
                        description = "去首页挑一部，看过的会自动出现在这里",
                    )
                }
            } else {
                items(progress, key = { it.key }) { record ->
                    val item = recent.firstOrNull { it.key == record.key }
                    val fraction = if (record.durationMs > 0) {
                        (record.positionMs.toFloat() / record.durationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val remain = (record.durationMs - record.positionMs).coerceAtLeast(0L) / 60_000
                    MediaRow(
                        title = record.title,
                        subtitle = buildString {
                            append(record.episodeName.ifBlank { "上次看到" })
                            append(" · 看到 ")
                            append(formatPosition(record.positionMs))
                            if (remain > 0) {
                                append(" · 剩 ")
                                append(remain)
                                append(" 分钟")
                            }
                        },
                        cover = record.cover,
                        progress = fraction,
                        enabled = item != null,
                        onClick = { if (item != null) onResume(item, record) },
                    )
                }
            }
        } else {
            if (favorites.isEmpty()) {
                item {
                    EmptyState(
                        title = "片匣是空的",
                        description = "在详情页点收藏，剧就会收在这里",
                    )
                }
            } else {
                item {
                    Box(modifier = Modifier.padding(top = 16.dp)) {
                        FlowPosterGrid(
                            columns = 3,
                            itemCount = favorites.size,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        ) { index, itemModifier ->
                            val fav = favorites[index]
                            PosterCard(
                                title = fav.title,
                                cover = fav.cover,
                                subtitle = fav.remark.takeIf { it.isNotBlank() },
                                modifier = itemModifier,
                                onClick = { onOpen(SearchGroup(fav.title, listOf(fav))) },
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}
