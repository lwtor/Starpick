package com.starpick.app.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.starpick.app.AppContainer
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.WatchProgress
import com.starpick.app.core.parser.PlaylistParser
import com.starpick.app.ui.component.EmptyHint
import com.starpick.app.ui.component.FlowPosterGrid
import com.starpick.app.ui.component.SectionTitle
import com.starpick.app.ui.component.VodCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DetailScreen(
    container: AppContainer,
    group: SearchGroup,
    onBack: () -> Unit,
    onPlay: (VodItem, Int, Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sources by container.store.sources.collectAsState(initial = emptyList())

    // 防御：SearchGroup.items 理论非空，但空值时直接给空态而非崩溃。
    if (group.items.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("该条目没有可用的来源", style = MaterialTheme.typography.titleMedium)
            }
            EmptyHint("暂无播放来源", "换一个源或重新搜索")
        }
        return
    }

    var selected by remember(group) { mutableStateOf(group.items.first()) }
    var resolved by remember(group) { mutableStateOf(group.items.first()) }
    var favorite by remember(group) { mutableStateOf(false) }
    var resumeRecord by remember(group) { mutableStateOf<WatchProgress?>(null) }
    var lineIndex by remember(group) { mutableStateOf(0) }

    LaunchedEffect(group) {
        container.store.rememberItem(group.items.first())
    }

    // 搜索结果里有些源不给播放串，换源或取详情时补一次。
    // 关键：依赖 sources 一起作为 key，等 sources 收集到之后才补详情，避免拿空列表直接放弃；
    // 网络请求切 IO 线程，整体 try-catch 兜底，任何异常都不能把详情页带崩。
    LaunchedEffect(selected, sources) {
        runCatching {
            var base = selected
            if (base.playUrl.isBlank()) {
                val site = sources.firstOrNull { it.id == base.sourceId }
                if (site != null) {
                    base = withContext(Dispatchers.IO) {
                        container.api.detail(site, base.vodId)
                    } ?: base
                }
            }
            resolved = base
            lineIndex = 0
            favorite = container.store.isFavorite(base.key)
            resumeRecord = container.store.progressFor(base.key)
        }
    }

    val playGroups = remember(resolved) {
        PlaylistParser.parse(resolved.playFrom, resolved.playUrl)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部返回栏（自带状态栏 inset 由外层 Scaffold 处理，这里不含 topBar 的详情页需自行处理）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = resolved.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 海报 + 信息头
            item {
                Row {
                    VodCover(
                        title = resolved.title,
                        cover = resolved.cover,
                        modifier = Modifier.size(width = 110.dp, height = 154.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = resolved.title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                        )
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
                        resolved.remark.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }

            // 操作按钮
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                container.store.toggleFavorite(resolved)
                                favorite = container.store.isFavorite(resolved.key)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (favorite) "已收进片匣" else "加入片匣")
                    }
                    resumeRecord?.let { record ->
                        OutlinedButton(
                            onClick = { onPlay(resolved, record.groupIndex, record.episodeIndex) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("继续看 ${record.episodeName}")
                        }
                    }
                }
            }

            // 简介
            if (resolved.description.isNotBlank()) {
                item {
                    Column {
                        SectionTitle("简介")
                        Text(
                            text = resolved.description.take(300),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 来源选择
            if (group.items.size > 1) {
                item {
                    Column {
                        SectionTitle("来源")
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            group.items.forEach { item ->
                                FilterChip(
                                    selected = item == selected,
                                    onClick = { selected = item },
                                    label = { Text(item.sourceName) },
                                )
                            }
                        }
                    }
                }
            }

            // 线路选择
            if (playGroups.size > 1) {
                item {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        playGroups.forEachIndexed { index, playGroup ->
                            FilterChip(
                                selected = index == lineIndex,
                                onClick = { lineIndex = index },
                                label = { Text(playGroup.from) },
                            )
                        }
                    }
                }
            }

            // 选集
            item {
                Column {
                    SectionTitle("选集")
                    if (playGroups.isEmpty()) {
                        Text(
                            "该源没有返回播放地址，可换一个源或检查采集站配置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val episodes = playGroups.getOrNull(lineIndex)?.episodes.orEmpty()
                        Text(
                            text = "共 ${episodes.size} 集",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        // 选集按钮：手写分行（每行 5 个），不能嵌套 LazyVerticalGrid（与 LazyColumn 冲突崩溃）。
                        FlowPosterGrid(
                            columns = 5,
                            itemCount = episodes.size,
                            horizontalSpacing = 8.dp,
                            verticalSpacing = 8.dp,
                        ) { index, itemModifier ->
                            OutlinedButton(
                                onClick = { onPlay(resolved, lineIndex, index) },
                                contentPadding = PaddingValues(4.dp),
                                modifier = itemModifier,
                            ) {
                                Text("${index + 1}")
                            }
                        }
                    }
                }
            }
        }
    }
}
