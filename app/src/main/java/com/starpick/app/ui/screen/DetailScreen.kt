package com.starpick.app.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.starpick.app.AppContainer
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.WatchProgress
import com.starpick.app.core.parser.PlaylistParser
import com.starpick.app.ui.component.Hint
import com.starpick.app.ui.component.SectionTitle
import com.starpick.app.ui.component.VodCover
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(
    container: AppContainer,
    group: SearchGroup,
    onBack: () -> Unit,
    onPlay: (VodItem, Int, Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sources by container.store.sources.collectAsState(initial = emptyList())

    var selected by remember(group) { mutableStateOf(group.items.first()) }
    var resolved by remember(group) { mutableStateOf(group.items.first()) }
    var favorite by remember(group) { mutableStateOf(false) }
    var resumeRecord by remember(group) { mutableStateOf<WatchProgress?>(null) }
    var lineIndex by remember(group) { mutableStateOf(0) }

    LaunchedEffect(group) {
        container.store.rememberItem(group.items.first())
    }

    // 搜索结果里有些源不给播放串，换源或取详情时补一次。
    LaunchedEffect(selected) {
        var base = selected
        if (base.playUrl.isBlank()) {
            val site = sources.firstOrNull { it.id == base.sourceId }
            if (site != null) base = container.api.detail(site, base.vodId) ?: base
        }
        resolved = base
        lineIndex = 0
        favorite = container.store.isFavorite(base.key)
        resumeRecord = container.store.progressFor(base.key)
    }

    val playGroups = remember(resolved) {
        PlaylistParser.parse(resolved.playFrom, resolved.playUrl)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(group.title, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(8.dp))

        Row {
            VodCover(
                title = resolved.title,
                cover = resolved.cover,
                modifier = Modifier.size(width = 100.dp, height = 140.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(resolved.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = listOfNotNull(
                        resolved.year.takeIf { it.isNotBlank() },
                        resolved.area.takeIf { it.isNotBlank() },
                        resolved.category.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = resolved.remark,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        if (resolved.description.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = resolved.description.take(300),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (group.items.size > 1) {
            Spacer(Modifier.height(8.dp))
            SectionTitle("来源")
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                group.items.forEach { item ->
                    TextButton(onClick = { selected = item }) {
                        Text(
                            text = item.sourceName,
                            color = if (item == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row {
            Button(
                onClick = {
                    scope.launch {
                        container.store.toggleFavorite(resolved)
                        favorite = container.store.isFavorite(resolved.key)
                    }
                },
            ) {
                Text(if (favorite) "已收进片匣 · 点此移出" else "加入片匣")
            }
            resumeRecord?.let { record ->
                TextButton(onClick = { onPlay(resolved, record.groupIndex, record.episodeIndex) }) {
                    Text("继续看 ${record.episodeName}")
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionTitle("选集")
        if (playGroups.isEmpty()) {
            Hint("该源没有返回播放地址，可换一个源或检查采集站配置")
        } else {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                playGroups.forEachIndexed { index, playGroup ->
                    TextButton(onClick = { lineIndex = index }) {
                        Text(
                            text = playGroup.from,
                            color = if (index == lineIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
            val episodes = playGroups.getOrNull(lineIndex)?.episodes.orEmpty()
            Text(
                text = "共 ${episodes.size} 集",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(68.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(episodes.size) { index ->
                    OutlinedButton(
                        onClick = { onPlay(resolved, lineIndex, index) },
                        contentPadding = PaddingValues(4.dp),
                    ) {
                        Text("${index + 1}")
                    }
                }
            }
        }
    }
}
