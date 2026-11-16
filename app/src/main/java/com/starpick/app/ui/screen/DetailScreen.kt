package com.starpick.app.ui.screen

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.FloatingActionButton
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
import com.starpick.app.core.model.DoubanDetail
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.WatchProgress
import com.starpick.app.core.model.contentKeyOf
import com.starpick.app.core.parser.PlaylistParser
import com.starpick.app.core.parser.isDirect
import com.starpick.app.ui.component.EmptyState
import com.starpick.app.ui.component.EpisodeCell
import com.starpick.app.ui.component.FlowPosterGrid
import com.starpick.app.ui.component.LineChip
import com.starpick.app.ui.component.PrimaryButton
import com.starpick.app.ui.component.SectionHeader
import com.starpick.app.ui.theme.Accent
import com.starpick.app.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 详情页 = 播放页，三段式布局（同一个页面，无跳转）：
 *  1. 顶部：16:9 区域，未播放时是豆瓣封面，点播放后 Crossfade 淡入播放器（横屏铺满全屏）。
 *  2. 中部：豆瓣资料卡（评分 / 年份 / 类型 / 地区 / 时长 / 导演 / 演员 / 短评 / 简介），
 *     查不到豆瓣信息时显示兜底卡，但播放、选集等一律照常。
 *  3. 底部：视频源及源相关信息（线路数 / 集数 / 播放地址状态）、线路、选集。
 */
@Composable
fun DetailScreen(
    container: AppContainer,
    group: SearchGroup,
    initialPlay: WatchProgress? = null,
    /**
     * 非空时在后台按这个片名补齐其余源：入口只带了一条记录（历史 / 收藏）或只等到
     * 第一个命中的源时，页面先开出来，剩下的源随后补进列表，不必干等好几秒。
     */
    enrichTitle: String? = null,
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

    // 源列表独立成一个可变状态：后台补齐时只换这一份，不动 selected / playing 等，
    // 正在播放或已选好线路不会被重置。
    var items by remember(group) { mutableStateOf(group.items) }
    var selected by remember(group) { mutableStateOf(group.items.first()) }
    var resolved by remember(group) { mutableStateOf(group.items.first()) }
    var favorite by remember(group) { mutableStateOf(false) }
    var resumeRecord by remember(group) { mutableStateOf<WatchProgress?>(null) }
    var lineIndex by remember(group) { mutableStateOf(0) }
    var expanded by remember(group) { mutableStateOf(false) }
    var douban by remember(group) { mutableStateOf<DoubanDetail?>(null) }
    var doubanLoading by remember(group) { mutableStateOf(true) }
    // 播放只是本页顶部的一个状态切换，不动导航栈。
    var playing by remember(group) { mutableStateOf<PlayTarget?>(null) }

    // 横屏播放中：由 PlayerSurface 的 BackHandler 先响应，只退全屏；
    // 其余情况（含横屏但未播放）直接退出详情页。
    BackHandler(enabled = !isLandscape || playing == null) { onBack() }

    // 按片名查豆瓣详情：失败就留 null，页面显示兜底卡，绝不影响播放与选集。
    LaunchedEffect(group.title) {
        doubanLoading = true
        val detail = withContext(Dispatchers.IO) {
            container.douban.detailByTitle(group.title)
        }
        douban = detail
        doubanLoading = false
    }

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
            // 换到别的源时线路/集数可能都比记录少，两个下标都要 clamp，否则会黑屏。
            val lines = PlaylistParser.parse(base.playFrom, base.playUrl)
            val recorded = initialPlay.groupIndex.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
            // 记录里那条线路如果是分享页（`/share/<hash>`）就换一条真正的视频流线路：
            // 采集站常把分享页排在第一条，默认播它只会一直转圈。
            val groupIndex = if (lines.getOrNull(recorded)?.isDirect() == true) {
                recorded
            } else {
                PlaylistParser.preferredGroupIndex(lines)
            }
            val episodeCount = lines.getOrNull(groupIndex)?.episodes?.size ?: 0
            val episodeIndex = initialPlay.episodeIndex.coerceIn(0, (episodeCount - 1).coerceAtLeast(0))
            lineIndex = groupIndex
            playing = PlayTarget(base, groupIndex, episodeIndex)
        }
    }

    // 入口只带了一条记录（历史 / 收藏）或只等到第一个命中的源时，这里在后台把其余源补齐。
    // 只更新源列表，不动 selected / 线路 / 播放状态，避免补源时把正在看的进度重置。
    LaunchedEffect(enrichTitle) {
        val keyword = enrichTitle ?: return@LaunchedEffect
        val enabled = container.store.sources.value.filter { it.enabled }
        if (enabled.isEmpty()) return@LaunchedEffect
        container.aggregator.progressive(enabled, keyword).collect { outcome ->
            val target = pickGroup(outcome.groups.orEmpty(), selected) ?: return@collect
            if (target.items.size <= items.size) return@collect
            items = target.items
            // 当前源的字段可能被新结果补全（如封面/简介），值不同才换引用，
            // 值相同不换，避免白白重启下面的 LaunchedEffect(selected) 把线路重置。
            val current = target.items.firstOrNull { it.key == selected.key }
            if (current != null && current != selected && playing == null) {
                selected = current
                resolved = current
            }
        }
    }

    // 搜索结果里有些源不给播放串，换源或取详情时补一次。
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
            val lines = PlaylistParser.parse(base.playFrom, base.playUrl)
            val preferred = PlaylistParser.preferredGroupIndex(lines)
            // 记录里那条线路若不是直链（分享页），改用像视频流的那条线路。
            val recorded = record
                ?.takeIf { it.sourceId == base.sourceId && it.vodId == base.vodId }
                ?.groupIndex
                ?.takeIf { lines.getOrNull(it)?.isDirect() == true }
            lineIndex = (recorded ?: preferred).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
            favorite = container.store.isFavorite(base.key)
        }
    }

    val playGroups = remember(resolved) {
        PlaylistParser.parse(resolved.playFrom, resolved.playUrl)
    }
    val episodes = playGroups.getOrNull(lineIndex)?.episodes.orEmpty()

    /**
     * 用记录里的线路/集号在当前源上开播。
     *
     * 记录可能来自另一个源：线路数和集数都可能比当前源多，不收敛就会跳到不存在的集，
     * 播放器只剩「播放地址无效」。
     */
    fun startFromRecord(record: WatchProgress) {
        val groupIndex = record.groupIndex.coerceIn(0, (playGroups.size - 1).coerceAtLeast(0))
        val count = playGroups.getOrNull(groupIndex)?.episodes?.size ?: 0
        val episodeIndex = record.episodeIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
        lineIndex = groupIndex
        playing = PlayTarget(resolved, groupIndex, episodeIndex)
    }

    // 播放失败时重新拉一次详情：采集站的播放地址会过期，重新取往往就好了。
    val refreshSource: () -> Unit = {
        val target = playing
        if (target != null) {
            scope.launch {
                val site = sources.firstOrNull { it.id == target.item.sourceId }
                val fresh = site?.let {
                    withContext(Dispatchers.IO) {
                        runCatching { container.api.detail(it, target.item.vodId) }.getOrNull()
                    }
                }
                if (fresh != null) {
                    items = items.map { if (it.key == fresh.key) fresh else it }
                    if (selected.key == fresh.key) {
                        selected = fresh
                        resolved = fresh
                    }
                    val lines = PlaylistParser.parse(fresh.playFrom, fresh.playUrl)
                    val gi = target.groupIndex.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
                    val count = lines.getOrNull(gi)?.episodes?.size ?: 0
                    val ei = target.episodeIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
                    playing = target.copy(item = fresh, groupIndex = gi, episodeIndex = ei)
                }
            }
        }
    }

    // 播放失败时切到下一个源：采集站的线路互相独立，换一个往往就能看了。
    val switchSource: () -> Unit = {
        val active = playing
        if (active != null && items.size > 1) {
            val index = items.indexOfFirst { it.key == active.item.key }
            val next = items[(index + 1).mod(items.size)]
            selected = next
            resolved = next
            val lines = PlaylistParser.parse(next.playFrom, next.playUrl)
            playing = PlayTarget(next, PlaylistParser.preferredGroupIndex(lines), 0)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isLandscape && playing != null) Color.Black else Ink.Base),
    ) {
        // 状态栏避让放在内容列外层，顶部盒子才能严格保持 16:9。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isLandscape && playing != null) Modifier else Modifier.statusBarsPadding()),
        ) {
            // ── 1. 顶部：严格 16:9 的封面（豆瓣海报）⇄ 播放器，Crossfade 平滑切换。
            Box(
                modifier = if (isLandscape && playing != null) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxWidth().aspectRatio(5f / 3f)
                },
            ) {
            Crossfade(
                targetState = playing != null,
                animationSpec = tween(durationMillis = 320),
                label = "cover-to-player",
            ) { inPlayer ->
                if (inPlayer) {
                    val target = playing
                    if (target != null) {
                        PlayerSurface(
                            container = container,
                            item = target.item,
                            groupIndex = target.groupIndex,
                            episodeIndex = target.episodeIndex,
                            onGroupChange = { g -> playing = playing?.copy(groupIndex = g, episodeIndex = 0) },
                            onEpisodeChange = { e -> playing = playing?.copy(episodeIndex = e) },
                            onExit = onBack,
                            onRefreshSource = refreshSource,
                            onSwitchSource = switchSource,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    CoverHero(
                        title = group.title,
                        cover = douban?.cover?.takeIf { it.isNotBlank() } ?: resolved.cover,
                        rate = douban?.rate.orEmpty(),
                        year = douban?.year.orEmpty(),
                        onBack = onBack,
                        onPlay = {
                            val record = resumeRecord
                            if (record != null) {
                                startFromRecord(record)
                            } else if (episodes.isNotEmpty()) {
                                playing = PlayTarget(resolved, lineIndex, 0)
                            }
                        },
                    )
                }
            }
        }

        // ── 2 / 3. 下方详情：横屏播放时让位给全屏播放器，其余时候照常显示。
        if (!(isLandscape && playing != null)) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                // 播放中补一条紧凑片名条，方便边看边确认当前集。
                if (playing != null) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = group.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val playTarget = playing
                                val epName = if (playTarget != null) {
                                    playGroups.getOrNull(playTarget.groupIndex)?.episodes
                                        ?.getOrNull(playTarget.episodeIndex)?.name
                                } else null
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
                        }
                    }
                }

                // ── 中部：豆瓣资料卡 / 兜底卡。
                item {
                    DoubanInfoCard(
                        detail = douban,
                        loading = doubanLoading,
                        description = resolved.description,
                        expanded = expanded,
                    ) { expanded = !expanded }
                }

                // 播放入口（收藏已改为顶部悬浮 FAB）。
                if (playing == null) {
                    item {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
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
                                modifier = Modifier.fillMaxWidth(),
                                enabled = record != null || episodes.isNotEmpty(),
                                onClick = {
                                    val record = resumeRecord
                                    if (record != null) {
                                        startFromRecord(record)
                                    } else if (episodes.isNotEmpty()) {
                                        playing = PlayTarget(resolved, lineIndex, 0)
                                    }
                                },
                            )
                        }
                    }
                }

                // ── 底部：视频源及源相关信息。
                item { SectionHeader(title = "视频源", trailing = "${items.size} 个") }
                item {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items.forEach { item ->
                            LineChip(
                                text = item.sourceName,
                                selected = item.key == selected.key,
                                onClick = {
                                    selected = item
                                    // 播放中换源：立刻切到新源，不然点了没反应，会以为播不出来。
                                    val active = playing
                                    if (active != null && active.item.key != item.key) {
                                        playing = PlayTarget(item, 0, 0)
                                    }
                                },
                            )
                        }
                    }
                }
                // 当前源的附加信息：线路数 / 集数 / 播放地址状态。
                item { SourceInfoRow(item = resolved, groupCount = playGroups.size, episodeCount = episodes.size) }

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
                        val active = playing
                        val activeEpisode = when {
                            active != null && active.item.key == resolved.key && active.groupIndex == lineIndex ->
                                active.episodeIndex
                            record != null && record.groupIndex == lineIndex -> record.episodeIndex
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
                                    val active = playing
                                    if (active != null && active.item.key == resolved.key) {
                                        playing = active.copy(groupIndex = lineIndex, episodeIndex = index)
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

        // 收藏改为悬浮 FAB：竖屏常驻右下角，横屏全屏播放时让位给播放器。
        if (!(isLandscape && playing != null)) {
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        container.store.toggleFavorite(resolved)
                        favorite = container.store.isFavorite(resolved.key)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .navigationBarsPadding(),
                containerColor = if (favorite) Accent.Gold else Ink.Raised,
                contentColor = if (favorite) Color.Black else Ink.Text1,
            ) {
                Icon(
                    imageVector = if (favorite) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = "收藏",
                )
            }
        }
    }
}

/** 顶部封面态：豆瓣海报铺底 + 渐变 + 片名/评分元信息 + 中央播放键。 */
@Composable
private fun CoverHero(
    title: String,
    cover: String,
    rate: String,
    year: String,
    onBack: () -> Unit,
    onPlay: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Ink.Card)) {
        if (cover.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(cover).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(12.dp),
            )
        }
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color(0x990A0E14),
                        0.5f to Color(0x660A0E14),
                        1f to Color(0xF20A0E14),
                    ),
                ),
            ),
        )

        // 返回键。
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
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

        // 封面中央覆盖层：片名 + 元信息 + 播放键，整块可点播放。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onPlay)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (rate.isNotBlank()) {
                Text(
                    text = "豆瓣 $rate",
                    style = MaterialTheme.typography.labelMedium,
                    color = Accent.Gold,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (year.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(text = year, style = MaterialTheme.typography.labelSmall, color = Color(0xB3FFFFFF))
            }
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
}

/** 中部：豆瓣资料卡。有数据显示完整信息；无数据显示兜底卡，但简介照常显示。 */
@Composable
private fun DoubanInfoCard(
    detail: DoubanDetail?,
    loading: Boolean,
    description: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        if (loading) {
            // 加载中先占位，避免「兜底卡闪一下再跳豆瓣卡」的突兀。
            Spacer(Modifier.height(52.dp))
        } else if (detail != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (detail.rate.isNotBlank()) {
                    Text(detail.rate, style = MaterialTheme.typography.headlineSmall, color = Accent.Gold, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                }
                Text("豆瓣评分", style = MaterialTheme.typography.labelSmall, color = Ink.Text3)
            }
            val meta = listOfNotNull(
                detail.year.takeIf { it.isNotBlank() },
                detail.types.joinToString(" / ").takeIf { it.isNotBlank() },
                detail.region.takeIf { it.isNotBlank() },
                detail.duration.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(meta, style = MaterialTheme.typography.bodySmall, color = Ink.Text2)
            }
            if (detail.directors.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                InfoLine(label = "导演", value = detail.directors.joinToString(" / "))
            }
            if (detail.actors.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                InfoLine(label = "主演", value = detail.actors.take(8).joinToString(" / "))
            }
            if (detail.comment.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text("「${detail.comment}」", style = MaterialTheme.typography.bodySmall, color = Accent.Gold)
            }
        } else {
            // 无豆瓣信息兜底：明确告知，但不阻断任何播放/选集操作。
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Ink.Card)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "暂无豆瓣影片信息",
                    style = MaterialTheme.typography.labelMedium,
                    color = Ink.Text2,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "不影响播放与选集",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.Text3,
                )
            }
        }

        // 简介用采集站剧情简介，与豆瓣短评互补。
        if (description.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = description.take(300),
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.Text2,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (description.length > 120) {
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = Ink.Text3,
                    modifier = Modifier.padding(top = 6.dp).clickable(onClick = onToggleExpand),
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(text = "$label  ", style = MaterialTheme.typography.bodySmall, color = Ink.Text3)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Ink.Text2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 当前源附加信息：线路数 / 集数 / 播放地址状态。 */
@Composable
private fun SourceInfoRow(item: VodItem, groupCount: Int, episodeCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("当前：${item.sourceName}", style = MaterialTheme.typography.labelMedium, color = Ink.Text1)
        Spacer(Modifier.width(12.dp))
        Text("$groupCount 条线路", style = MaterialTheme.typography.labelSmall, color = Ink.Text3)
        Spacer(Modifier.width(10.dp))
        Text("$episodeCount 集", style = MaterialTheme.typography.labelSmall, color = Ink.Text3)
        Spacer(Modifier.weight(1f))
        Text(
            text = if (item.playUrl.isNotBlank()) "有播放地址" else "待解析",
            style = MaterialTheme.typography.labelSmall,
            color = if (item.playUrl.isNotBlank()) Accent.Gold else Ink.Text3,
        )
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
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = Ink.Text1, maxLines = 1)
    }
}

/** 当前播放目标：同一页面内切换详情/播放器时不需要动导航栈。 */
private data class PlayTarget(
    val item: VodItem,
    val groupIndex: Int,
    val episodeIndex: Int,
)
