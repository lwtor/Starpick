package com.starpick.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.starpick.app.AppContainer
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.contentKeyOf
import com.starpick.app.core.model.WatchProgress
import com.starpick.app.core.parser.PlaylistParser
import com.starpick.app.ui.component.EmptyState
import com.starpick.app.ui.component.EpisodeCell
import com.starpick.app.ui.component.FlowPosterGrid
import com.starpick.app.ui.component.LineChip
import com.starpick.app.ui.component.PrimaryButton
import com.starpick.app.ui.component.SectionHeader
import com.starpick.app.ui.component.SquareIconButton
import com.starpick.app.ui.component.VodCover
import com.starpick.app.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 详情：海报模糊铺底 + 片名/评分区 + 主 CTA + 来源/线路/选集。
 *
 * 主 CTA 优先写「继续观看 S01E03」而不是「播放」—— 用户要的是接着看，不是从头看。
 * 本页不在 Scaffold 内，状态栏 inset 必须自己加，否则返回键会被状态栏压住。
 */
@Composable
fun DetailScreen(
    container: AppContainer,
    group: SearchGroup,
    initialPlay: WatchProgress? = null,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sources by container.store.sources.collectAsState()

    // 防御：SearchGroup.items 理论非空，空值时给空态而非崩溃。
    if (group.items.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            BackRow(title = "该条目没有可用的来源", onBack = onBack)
            EmptyState(
                title = "暂无播放来源",
                description = "换一个源或重新搜索",
                actionLabel = "返回",
                onAction = onBack,
            )
        }
        return
    }

    var selected by remember(group) { mutableStateOf(group.items.first()) }
    var resolved by remember(group) { mutableStateOf(group.items.first()) }
    var favorite by remember(group) { mutableStateOf(false) }
    var resumeRecord by remember(group) { mutableStateOf<WatchProgress?>(null) }
    var lineIndex by remember(group) { mutableStateOf(0) }
    var expanded by remember(group) { mutableStateOf(false) }
    // 播放不再跳转到独立页面：点播放只是在本页把头部切换成播放器。
    var playing by remember(group) { mutableStateOf<PlayTarget?>(null) }

    // 竖屏播放时返回先收起播放器；横屏时由播放器自己的 BackHandler 先退全屏。
    BackHandler(enabled = playing != null) { playing = null }

    LaunchedEffect(group) {
        val record = initialPlay ?: container.store.progressFor(contentKeyOf(group.title))
        // 按最后一次播放记录自动切回当时的源，而不是永远用搜索结果的第一个。
        val matched = if (record != null && record.sourceId.isNotBlank()) {
            group.items.firstOrNull { it.sourceId == record.sourceId && it.vodId == record.vodId }
        } else null
        if (matched != null) selected = matched
        container.store.rememberItem(matched ?: group.items.first())
        if (initialPlay != null) {
            val target = matched ?: group.items.first()
            val count = PlaylistParser.parse(target.playFrom, target.playUrl).size
            playing = PlayTarget(
                target,
                initialPlay.groupIndex.coerceIn(0, (count - 1).coerceAtLeast(0)),
                initialPlay.episodeIndex,
            )
        }
    }

    // 搜索结果里有些源不给播放串，换源或取详情时补一次。
    // 依赖 sources 一起作为 key，等 sources 收集到之后再补，避免拿空列表直接放弃；
    // 网络请求切 IO，整体 try-catch 兜底，任何异常都不能把详情页带崩。
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
            val record = container.store.progressFor(base.contentKey)
            resumeRecord = record
            // 同一条记录才恢复线路；源不同时线路编号不通用。
            val wanted = if (record != null && record.sourceId == base.sourceId && record.vodId == base.vodId) {
                record.groupIndex
            } else 0
            val count = PlaylistParser.parse(base.playFrom, base.playUrl).size
            lineIndex = wanted.coerceIn(0, (count - 1).coerceAtLeast(0))
            favorite = container.store.isFavorite(base.key)
        }
    }

    val playGroups = remember(resolved) {
        PlaylistParser.parse(resolved.playFrom, resolved.playUrl)
    }
    val episodes = playGroups.getOrNull(lineIndex)?.episodes.orEmpty()

    val target = playing
    if (target != null) {
        PlayerScreen(
            container = container,
            item = target.item,
            groupIndex = target.groupIndex,
            episodeIndex = target.episodeIndex,
            // 播放器的返回只收起播放器，回到详情，不再新开/弹出一个页面。
            onBack = { playing = null },
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            DetailHeader(
                item = resolved,
                favorite = favorite,
                onBack = onBack,
                onToggleFavorite = {
                    scope.launch {
                        container.store.toggleFavorite(resolved)
                        favorite = container.store.isFavorite(resolved.key)
                    }
                },
            )
        }

        item {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val record = resumeRecord
                val label = when {
                    record != null -> "继续观看 ${record.episodeName.ifBlank { "上次进度" }}"
                    episodes.isNotEmpty() -> "播放第 1 集"
                    else -> "暂无播放地址"
                }
                PrimaryButton(
                    text = label,
                    icon = Icons.Filled.PlayArrow,
                    modifier = Modifier.weight(1f),
                    enabled = record != null || episodes.isNotEmpty(),
                    onClick = {
                        if (record != null) {
                            playing = PlayTarget(resolved, record.groupIndex, record.episodeIndex)
                        } else if (episodes.isNotEmpty()) {
                            playing = PlayTarget(resolved, lineIndex, 0)
                        }
                    },
                )
                SquareIconButton(
                    icon = if (favorite) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = "收藏",
                    active = favorite,
                    onClick = {
                        scope.launch {
                            container.store.toggleFavorite(resolved)
                            favorite = container.store.isFavorite(resolved.key)
                        }
                    },
                )
            }
        }

        if (resolved.description.isNotBlank()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        text = resolved.description.take(300),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.Text2,
                        maxLines = if (expanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (resolved.description.length > 120) {
                        Text(
                            text = if (expanded) "收起" else "展开",
                            style = MaterialTheme.typography.labelMedium,
                            color = Ink.Text3,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clickable { expanded = !expanded },
                        )
                    }
                }
            }
        }

        // 换源：同一部片在不同源里的集数不同，放在选集之前让人先选源。
        if (group.items.size > 1) {
            item { SectionHeader(title = "来源", trailing = "${group.items.size} 个") }
            item {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    group.items.forEach { item ->
                        LineChip(
                            text = item.sourceName,
                            selected = item.key == selected.key,
                            onClick = { selected = item },
                        )
                    }
                }
            }
        }

        if (playGroups.size > 1) {
            item { SectionHeader(title = "线路", trailing = "${playGroups.size} 条") }
            item {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    playGroups.forEachIndexed { index, playGroup ->
                        LineChip(
                            text = playGroup.from,
                            selected = index == lineIndex,
                            onClick = { lineIndex = index },
                        )
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = "选集",
                trailing = if (episodes.isEmpty()) null else "共 ${episodes.size} 集",
            )
        }

        item {
            if (episodes.isEmpty()) {
                Text(
                    text = "该源没有返回播放地址，可换一个源或检查采集站配置",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.Text2,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                val record = resumeRecord
                FlowPosterGrid(
                    columns = 5,
                    itemCount = episodes.size,
                    modifier = Modifier.padding(horizontal = 20.dp),
                    horizontalSpacing = 8.dp,
                    verticalSpacing = 8.dp,
                ) { index, itemModifier ->
                    val currentEp = if (record != null && record.groupIndex == lineIndex) {
                        record.episodeIndex
                    } else {
                        -1
                    }
                    EpisodeCell(
                        label = "${index + 1}",
                        modifier = itemModifier,
                        selected = currentEp == index,
                        seen = currentEp > index,
                        onClick = { playing = PlayTarget(resolved, lineIndex, index) },
                    )
                }
            }
        }
    }
}

/** 头部：海报模糊铺底 + 返回 + 海报 / 片名 / 元信息。 */
@Composable
private fun DetailHeader(
    item: VodItem,
    favorite: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.cover)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(22.dp)
                .background(Ink.Card),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color(0x990A0E14),
                            0.45f to Color(0x660A0E14),
                            0.82f to Color(0xE60A0E14),
                            1f to Ink.Base,
                        ),
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x6606080C))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x6606080C))
                    .clickable(onClick = onToggleFavorite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (favorite) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = "收藏",
                    tint = if (favorite) Color(0xFFF6BE4F) else Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            VodCover(
                title = item.title,
                cover = item.cover,
                modifier = Modifier.size(width = 96.dp, height = 136.dp),
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = listOfNotNull(
                        item.year.takeIf { it.isNotBlank() },
                        item.area.takeIf { it.isNotBlank() },
                        item.category.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { item.sourceName },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xB3FFFFFF),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.remark.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = item.remark,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFF6BE4F),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun BackRow(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Ink.Text1,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Ink.Text1,
            maxLines = 1,
        )
    }
}

/** 当前正在播放的目标：同一页面内切换详情/播放器时不需要动导航栈。 */
private data class PlayTarget(
    val item: VodItem,
    val groupIndex: Int,
    val episodeIndex: Int,
)
