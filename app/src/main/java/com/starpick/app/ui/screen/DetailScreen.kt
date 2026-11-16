package com.starpick.app.ui.screen

import android.content.res.Configuration
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.platform.LocalConfiguration
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
import com.starpick.app.core.model.WatchProgress
import com.starpick.app.core.model.contentKeyOf
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
 * 详情页 = 播放页：同一个页面内完成「看详情 → 点播放 → 切集 → 换源」。
 *
 * 播放器不是跳转过去的独立页面，而是内嵌在本页顶部：
 *  - 未开始播放：顶部是海报头图；
 *  - 开始播放后：顶部原地切换为 16:9 播放器（横屏自动铺满全屏），
 *    下面的简介 / 换源 / 线路 / 选集全部保留，随时可见可点。
 * 返回键：横屏播放中先退全屏，其余情况直接退出本页（不再多一层「收起播放器」）。
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
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

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
    // 播放只是本页顶部的一个状态切换，不动导航栈。
    var playing by remember(group) { mutableStateOf<PlayTarget?>(null) }

    // 横屏播放中：由 PlayerSurface 的 BackHandler 先响应，只退全屏；
    // 其余情况（含横屏但未播放）直接退出详情页。
    BackHandler(enabled = !isLandscape || playing == null) { onBack() }

    LaunchedEffect(group) {
        val record = initialPlay ?: container.store.progressFor(contentKeyOf(group.title))
        // 按最后一次播放记录自动切回当时的源，而不是永远用搜索结果的第一个。
        val matched = if (record != null && record.sourceId.isNotBlank()) {
            group.items.firstOrNull { it.sourceId == record.sourceId && it.vodId == record.vodId }
        } else null
        val base = matched ?: group.items.first()
        if (matched != null) selected = matched
        container.store.rememberItem(base)
        if (initialPlay != null) {
            val count = PlaylistParser.parse(base.playFrom, base.playUrl).size
            val groupIndex = initialPlay.groupIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
            lineIndex = groupIndex
            playing = PlayTarget(base, groupIndex, initialPlay.episodeIndex)
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
            // 从「继续观看」直接进播放时，若条目需要补详情，同步把播放器换成补全后的源。
            val nowPlaying = playing
            if (nowPlaying != null && nowPlaying.item.key == base.key && nowPlaying.item.playUrl != base.playUrl) {
                playing = nowPlaying.copy(item = base)
            }
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isLandscape && target != null) Color.Black else Ink.Base),
    ) {
        // 顶部：未播放时是海报头图，开始播放后原地换成内嵌播放器。
        if (target != null) {
            PlayerSurface(
                container = container,
                item = target.item,
                groupIndex = target.groupIndex,
                episodeIndex = target.episodeIndex,
                onGroupChange = { g -> playing = playing?.copy(groupIndex = g, episodeIndex = 0) },
                onEpisodeChange = { e -> playing = playing?.copy(episodeIndex = e) },
                onExit = onBack,
                modifier = if (isLandscape) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                },
            )
        } else {
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

        if (!(isLandscape && target != null)) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                // 播放中补一条紧凑片名条，否则顶部换成播放器后看不到片名。
                if (target != null) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = target.item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val epName = playGroups
                                    .getOrNull(target.groupIndex)?.episodes
                                    ?.getOrNull(target.episodeIndex)?.name
                                if (!epName.isNullOrBlank()) {
                                    Text(
                                        text = "正在播放 · $epName",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Ink.Text3,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
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
                }

                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (target != null) {
                            // 已经在播放：主按钮只提示状态，换集走下方选集区。
                            PrimaryButton(
                                text = "播放中 · 在下方选集切换",
                                icon = Icons.Filled.PlayArrow,
                                modifier = Modifier.weight(1f),
                                enabled = false,
                                onClick = {},
                            )
                        } else {
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
                        // 高亮优先级：正在播放的集 > 上次观看记录。
                        val record = resumeRecord
                        val activeEpisode = when {
                            target != null && target.item.key == resolved.key && target.groupIndex == lineIndex ->
                                target.episodeIndex
                            record != null && record.groupIndex == lineIndex ->
                                record.episodeIndex
                            else -> -1
                        }
                        FlowPosterGrid(
                            columns = 5,
                            itemCount = episodes.size,
                            modifier = Modifier.padding(horizontal = 20.dp),
                            horizontalSpacing = 8.dp,
                            verticalSpacing = 8.dp,
                        ) { index, itemModifier ->
                            EpisodeCell(
                                label = "${index + 1}",
                                modifier = itemModifier,
                                selected = activeEpisode == index,
                                seen = activeEpisode > index,
                                onClick = {
                                    if (target != null && target.item.key == resolved.key) {
                                        // 已在播放：只切集，播放器原地换片源，不重建页面。
                                        playing = target.copy(groupIndex = lineIndex, episodeIndex = index)
                                    } else {
                                        playing = PlayTarget(resolved, lineIndex, index)
                                    }
                                },
                            )
                        }
                    }
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

/** 当前播放目标：同一页面内切换详情/播放器时不需要动导航栈。 */
private data class PlayTarget(
    val item: VodItem,
    val groupIndex: Int,
    val episodeIndex: Int,
)
