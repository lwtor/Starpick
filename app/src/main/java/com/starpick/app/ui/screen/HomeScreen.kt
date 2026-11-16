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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.starpick.app.AppContainer
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.WatchProgress
import com.starpick.app.ui.component.EmptyState
import com.starpick.app.ui.component.FlowPosterGrid
import com.starpick.app.ui.component.MediaRow
import com.starpick.app.ui.component.PosterCard
import com.starpick.app.ui.component.PrimaryButton
import com.starpick.app.ui.component.SectionHeader
import com.starpick.app.ui.component.SegmentedTabs
import com.starpick.app.ui.component.UpdateCard
import com.starpick.app.ui.component.formatPosition
import com.starpick.app.ui.theme.Accent
import com.starpick.app.ui.theme.Ink
import kotlinx.coroutines.launch

/**
 * 片匣 UI 状态：分段、滚动位置、编辑模式与选中项都挂在导航层，
 * 进详情回来不重置，切 tab 再切回也保持原样。
 */
class HomeUiState {
    var tabIndex by mutableIntStateOf(0)
    var editing by mutableStateOf(false)
    var selectedKeys by mutableStateOf(emptySet<String>())
    val listState = LazyListState()

    fun exitEdit() {
        editing = false
        selectedKeys = emptySet()
    }

    fun toggleKey(key: String) {
        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
    }
}

/**
 * 片匣：继续观看（列表）+ 收藏（海报网格）。
 *
 * 「继续观看」放第一位且用列表而非网格：一行里能同时给出进度条、剩余时长和上次观看时间，
 * 这是回访决策需要的全部信息。该分段支持编辑态多选删除；收藏分段没有编辑入口。
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    state: HomeUiState,
    onOpen: (SearchGroup) -> Unit,
    onResume: (VodItem, WatchProgress) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val favorites by container.store.favorites.collectAsState()
    val progress by container.store.progress.collectAsState()
    val recent by container.store.recent.collectAsState()

    val tabIndex = state.tabIndex
    val editing = tabIndex == 0 && state.editing
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = state.listState,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(bottom = if (editing) 84.dp else 12.dp),
        ) {
            // 自升级入口：仅在发现新版本或已下载时显示（UpdateCard 内部自行判断）。
            item { UpdateCard(container, onOpenSettings) }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "片匣",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Ink.Text1,
                    )
                    Spacer(Modifier.weight(1f))
                    // 只有「继续观看」有记录时才给编辑入口；收藏数量提示删掉。
                    if (tabIndex == 0 && progress.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (state.editing) state.exitEdit() else state.editing = true
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (state.editing) Icons.Filled.Close else Icons.Filled.Edit,
                                contentDescription = null,
                                tint = if (state.editing) Accent.Gold else Ink.Text2,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (state.editing) "完成" else "编辑",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (state.editing) Accent.Gold else Ink.Text2,
                            )
                        }
                    }
                }
            }

            item {
                Row(modifier = Modifier.padding(horizontal = 20.dp)) {
                    SegmentedTabs(
                        options = listOf("继续观看", "收藏"),
                        selectedIndex = tabIndex,
                        onSelect = {
                            state.tabIndex = it
                            // 编辑态只属于继续观看，切到收藏就退出。
                            if (it != 0) state.exitEdit()
                        },
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
                        val selected = record.key in state.selectedKeys
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
                            leading = if (editing) {
                                { SelectCircle(selected = selected) }
                            } else {
                                null
                            },
                            showChevron = !editing,
                            onClick = {
                                if (editing) {
                                    state.toggleKey(record.key)
                                } else if (item != null) {
                                    onResume(item, record)
                                }
                            },
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

        if (editing) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Ink.Base),
            ) {
                HorizontalDivider(thickness = 1.dp, color = Ink.Line)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = if (state.selectedKeys.isEmpty()) {
                            "勾选要删除的记录"
                        } else {
                            "已选 ${state.selectedKeys.size} 项"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.Text2,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(
                        text = "删除",
                        enabled = state.selectedKeys.isNotEmpty(),
                        onClick = {
                            val keys = state.selectedKeys
                            if (keys.isNotEmpty()) {
                                scope.launch {
                                    container.store.clearProgress(keys)
                                    state.exitEdit()
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 编辑态的勾选圈：选中时金色实心 + 对勾，未选中时描边空心。 */
@Composable
private fun SelectCircle(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (selected) Accent.Gold else Color.Transparent)
            .border(1.5.dp, if (selected) Accent.Gold else Ink.Text3, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Ink.Base,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}
