package com.starpick.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starpick.app.AppContainer
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.WatchProgress
import com.starpick.app.ui.component.FlowPosterGrid
import com.starpick.app.ui.component.SectionTitle
import com.starpick.app.ui.component.UpdateCard
import com.starpick.app.ui.component.VodCover
import com.starpick.app.ui.component.formatPosition

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpen: (SearchGroup) -> Unit,
    onResume: (VodItem, WatchProgress) -> Unit,
) {
    val favorites by container.store.favorites.collectAsState(initial = emptyList())
    val progress by container.store.progress.collectAsState(initial = emptyList())
    val recent by container.store.recent.collectAsState(initial = emptyList())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 自升级入口：仅在发现新版本或已下载时显示。
        item { UpdateCard(container) }

        // 继续观看
        item { SectionTitle("继续观看") }
        if (progress.isEmpty()) {
            item {
                Text(
                    "还没有观看记录，去搜索一部剧开始吧",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        } else {
            items(progress.take(8)) { record ->
                val item = recent.firstOrNull { it.key == record.key }
                ContinueWatchingRow(
                    record = record,
                    hasItem = item != null,
                    onClick = { if (item != null) onResume(item, record) },
                )
            }
        }

        // 我的片匣（海报网格）
        item { SectionTitle("我的片匣") }
        if (favorites.isEmpty()) {
            item {
                Text(
                    "在详情里点「加入片匣」，剧会收在这里",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        } else {
            item {
                PosterGrid(
                    items = favorites,
                    onClick = { onOpen(SearchGroup(it.title, listOf(it))) },
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingRow(
    record: WatchProgress,
    hasItem: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = hasItem, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VodCover(
            title = record.title,
            cover = record.cover,
            modifier = Modifier.size(width = 72.dp, height = 100.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = record.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${record.episodeName} · 看到 ${formatPosition(record.positionMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!hasItem) {
                Text(
                    text = "条目缓存已清理，请从片匣重新打开",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 影视海报网格：每行 3 个，封面按 2:3 比例。手写分行，避免嵌套 LazyVerticalGrid 崩溃。 */
@Composable
private fun PosterGrid(
    items: List<VodItem>,
    onClick: (VodItem) -> Unit,
) {
    FlowPosterGrid(
        columns = 3,
        itemCount = items.size,
    ) { index, itemModifier ->
        Column(
            modifier = itemModifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onClick(items[index]) },
        ) {
            VodCover(
                title = items[index].title,
                cover = items[index].cover,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = items[index].title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
