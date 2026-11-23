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
import com.starpick.app.core.model.MatchAnchor
import com.starpick.app.core.model.MediaTag
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.WatchProgress
import com.starpick.app.core.model.historyKeyOf
import com.starpick.app.core.parser.PlaylistParser
import com.starpick.app.core.search.SearchOutcome
import com.starpick.app.core.search.pickGroup
import com.starpick.app.core.search.match.MatchTrace
import com.starpick.app.ui.component.EmptyState
import com.starpick.app.ui.component.EpisodeCell
import com.starpick.app.ui.component.FlowPosterGrid
import com.starpick.app.ui.component.GhostButton
import com.starpick.app.ui.component.LineChip
import com.starpick.app.ui.component.PrimaryButton
import com.starpick.app.ui.component.SectionHeader
import com.starpick.app.ui.theme.Accent
import com.starpick.app.ui.theme.Ink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 详情页 = 播放页，三段式布局（同一个页面，无跳转）：
 *  1. 顶部：16:9 区域，未播放时是豆瓣封面，点播放后 Crossfade 淡入播放器（横屏铺满全屏）。
 *  2. 中部：豆瓣资料卡（评分 / 年份 / 类型 / 地区 / 时长 / 导演 / 演员 / 短评 / 简介），
 *     查不到豆瓣信息时显示兜底卡，但播放、选集等一律照常。
 *  3. 底部：视频源及源相关信息（线路数 / 集数 / 播放地址状态）、线路、选集。
 */
/**
 * 匹配前等豆瓣补年份的上限。
 *
 * 采集站搜索本身要几秒，这 1.5 秒基本被并行吸收，感知不到；但豆瓣卡住时
 * 不能跟着卡——没有年份的匹配虽然弱，也比一直转圈好。
 */
private const val YEAR_LOOKUP_TIMEOUT_MS = 1_500L

/**
 * 详情页自动补源的页数上限。
 *
 * 采集站一页 20 条，常用词第一页常被新上架的沾边内容占满，正片在两三页之后。
 * 自动翻到 3 页为止，剩下的交给用户点「加载更多源」——不替用户把几十页流量和等待一次性花掉。
 * 源站声明的 pagecount 比这个数小的话，提前到底。
 */
private const val MAX_DETAIL_SEARCH_PAGES = 3

@Composable
fun DetailScreen(
    container: AppContainer,
    group: SearchGroup,
    initialPlay: WatchProgress? = null,
    /**
     * 非空时在后台按这个锚点补齐其余源：入口只带了一条记录（历史 / 收藏）或只等到
     * 第一个命中的源时，页面先开出来，剩下的源随后补进列表，不必干等好几秒。
     */
    enrichAnchor: MatchAnchor? = null,
    onBack: () -> Unit,
    /** 串片纠错：用同一锚点重新在全源里择优。 */
    onRematch: (MatchAnchor) -> Unit = {},
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
    // 按 key 去重：同一 sourceId:vodId 只留一条，避免多个胶囊同时满足选中条件。
    var items by remember(group) { mutableStateOf(group.items.distinctBy { it.key }) }
    var selected by remember(group) { mutableStateOf(group.items.first()) }
    var resolved by remember(group) { mutableStateOf(group.items.first()) }
    var favorite by remember(group) { mutableStateOf(false) }
    var resumeRecord by remember(group) { mutableStateOf<WatchProgress?>(null) }
    var lineIndex by remember(group) { mutableStateOf(0) }
    var expanded by remember(group) { mutableStateOf(false) }
    var douban by remember(group) { mutableStateOf<DoubanDetail?>(null) }
    var doubanLoading by remember(group) { mutableStateOf(true) }
    // 是否仍在后台找源：只有豆瓣条目起步（占位组）时为真，补到真实源或确认找不到后清零。
    var enriching by remember(group) {
        mutableStateOf(enrichAnchor != null && group.items.all { it.sourceId.isBlank() })
    }
    // 翻页状态：自动阶段翻满 3 页，之后由「加载更多源」按钮接着翻。
    // collected 保留累计结果，否则每次手动翻页都要从第 1 页重新搜一遍。
    var collected by remember(group) { mutableStateOf<SearchOutcome?>(null) }
    var nextPage by remember(group) { mutableStateOf(1) }
    var moreLoading by remember(group) { mutableStateOf(false) }
    var moreExhausted by remember(group) { mutableStateOf(false) }
    // 播放只是本页顶部的一个状态切换，不动导航栈。
    var playing by remember(group) { mutableStateOf<PlayTarget?>(null) }

    // 占位条目（sourceId 空）表示"还没解析到源"，不能当成真源展示或持久化。
    val hasRealSource = items.any { it.sourceId.isNotBlank() }

    // 横屏播放中：由 PlayerSurface 的 BackHandler 先响应，只退全屏；
    // 其余情况（含横屏但未播放）直接退出详情页。
    BackHandler(enabled = !isLandscape || playing == null) { onBack() }

    // 豆瓣详情有两个用途：渲染资料卡，以及给匹配锚点补年份。
    //
    // 后者不是锦上添花。从「搜索演员名」进来时锚点是 `subjectsByTag` 建的，而那个接口
    // 不返回年份，于是 `YearConflictRule` / `YearMatchRule` 全线空转——采集站里
    // 「功夫熊猫」「功夫第一季」「石家庄功夫」几十条同名沾边条目一条都拦不住。
    // 查询词优先取锚点标题：那是豆瓣自己的片名，用它查到的年份才是这部片的年份。
    val doubanLookup = enrichAnchor?.title ?: group.title
    val doubanId = enrichAnchor?.doubanId.orEmpty()
    val doubanGate = remember(group, doubanLookup, doubanId) { CompletableDeferred<DoubanDetail?>() }
    LaunchedEffect(doubanLookup, doubanId) {
        doubanLoading = true
        val anchor = enrichAnchor
        val detail = withContext(Dispatchers.IO) {
            // 有豆瓣 id 就按 id 取：按片名 suggest 会把「功夫」兜底成《功夫女足》(2026)，
            // 页面上的年份/评分跟着跳，用户以为串片。没有 id（视频源入口）才退回片名查。
            if (anchor != null && anchor.doubanId.isNotBlank()) {
                container.douban.detailById(anchor.doubanId, anchor.title, anchor.cover, anchor.year)
            } else {
                container.douban.detailByTitle(doubanLookup)
            }
        }
        douban = detail
        doubanLoading = false
        doubanGate.complete(detail)
    }

    LaunchedEffect(group) {
        val record = initialPlay ?: container.store.progressFor(historyKeyOf(group.title))
        // 按最后一次播放记录自动切回当时的源，而不是永远用搜索结果的第一个。
        val matched = if (record != null && record.sourceId.isNotBlank()) {
            group.items.firstOrNull { it.sourceId == record.sourceId && it.vodId == record.vodId }
        } else null
        val base = matched ?: group.items.first()
        // 占位条目不是真源：不写历史/续播状态，等后台补到真实源后再由 enrich 更新。
        if (base.sourceId.isBlank()) return@LaunchedEffect
        if (matched != null) selected = matched
        container.store.rememberItem(base)
        if (initialPlay != null) {
            // 换到别的源时线路/集数可能都比记录少，两个下标都要 clamp，否则会黑屏。
            val lines = PlaylistParser.parse(base.playFrom, base.playUrl)
            val recorded = initialPlay.groupIndex.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
            // 同源记录直接沿用上次那条线路：上次能播就说明这条 CDN 是通的，
            // 按「像不像直链」重选反而可能踩到已失效的线路。跨源记录才重新挑。
            val sameSource = initialPlay.sourceId == base.sourceId && initialPlay.vodId == base.vodId
            val groupIndex = if (sameSource) recorded else PlaylistParser.preferredGroupIndex(lines)
            val episodeCount = lines.getOrNull(groupIndex)?.episodes?.size ?: 0
            val episodeIndex = initialPlay.episodeIndex.coerceIn(0, (episodeCount - 1).coerceAtLeast(0))
            lineIndex = groupIndex
            playing = PlayTarget(base, groupIndex, episodeIndex)
        }
    }

    /**
     * 把一次匹配结果应用到源列表。自动翻页和手动加载共用，保证「不打断播放」的判断只有一处。
     * 占位条目（sourceId 空）会被真实源整体替换；已有真源时只在数量增加时更新。
     */
    fun applyFoundSources(target: SearchGroup?) {
        val found = target?.items.orEmpty().filter { it.sourceId.isNotBlank() }
            .distinctBy { it.key }
        if (found.isEmpty()) return
        val current = items
        if (current.any { it.sourceId.isNotBlank() } && found.size <= current.size) return
        items = found
        // selected / resolved 必须始终指向 items 里的某一条，否则视频源胶囊一条都不亮。
        // 从「继续观看」进入时 playing 已有值，旧逻辑跳过修正，selected 仍停在占位项上，
        // 而占位项的 key 已被真实源替换，item.key == selected.key 对任何胶囊都不成立。
        // 优先对齐正在播放的源，其次保留原选中，最后退回第一条；这里只改选中态，不打断播放。
        val keep = found.firstOrNull { it.key == playing?.item?.key }
            ?: found.firstOrNull { it.key == selected.key }
            ?: found.first()
        if (keep != selected) selected = keep
        if (keep != resolved) resolved = keep
    }

    // 后台按锚点补齐视频源。两种情况走这里：入口是豆瓣条目（占位组），或入口只有一条历史记录。
    // 搜索词用 anchor.searchKeyword —— 剥掉季号 / 年份 / 方括号，但保留「·」这类符号。
    // 采集站是子串匹配，搜「某某 第一季」在片名写作「某某」的站上必然 0 结果；
    // 反过来搜「某某」在两种写法下都命中，命中面只增不减。季号筛选交给匹配管线的季号规则。
    LaunchedEffect(enrichAnchor) {
        val incoming = enrichAnchor ?: return@LaunchedEffect
        val enabled = container.store.sources.value.filter { it.enabled }
        if (enabled.isEmpty()) {
            enriching = false
            return@LaunchedEffect
        }
        // 最多等豆瓣 1.5 秒把年份补齐。等到了，《功夫》就能靠 2004 一次性否掉
        // 《功夫熊猫》《功夫第一季》《功夫足球》；等不到（豆瓣慢或挂了）照常往下走，
        // 宁可这一轮匹配弱一点，也不让人对着「正在找源」干等。
        val detail = withTimeoutOrNull(YEAR_LOOKUP_TIMEOUT_MS) { doubanGate.await() }
        val anchor = incoming.copy(year = incoming.year.ifBlank { detail?.year.orEmpty() })

        MatchTrace.begin("匹配《${anchor.title}》")
        if (incoming.year.isBlank() && anchor.year.isNotBlank()) {
            MatchTrace.note("豆瓣补年份: ${anchor.year}（原来没有年份，沾边条目全靠片名判）")
        }
        MatchTrace.anchor(anchor)
        try {
            var outcome = withContext(Dispatchers.IO) {
                container.aggregator.groupedWithVariants(
                    sites = enabled,
                    keyword = anchor.searchKeyword,
                    // 豆瓣用译名、采集站用原名时，只靠译名永远搜不出结果。
                    aliases = listOf(anchor.originalTitle),
                    // 这条路径用户没有「换个词重搜」的手段，对沉默的源也要尽力找齐。
                    fillSilentSources = true,
                )
            }
            collected = outcome
            applyFoundSources(pickGroup(outcome.groups.orEmpty(), anchor))
            MatchTrace.note(
                "第 1 页: ${outcome.items.size} 条" +
                    if (outcome.pageCount > 0) " / 源站共 ${outcome.pageCount} 页" else "",
            )

            // 不再只在「完全没匹配上」时才翻：常用词第一页常常只有 1 个真源，
            // 多翻两页能把散落在后面的语言版本补齐。源站页数比上限小就提前到底。
            val ceiling = if (outcome.pageCount > 0) {
                minOf(MAX_DETAIL_SEARCH_PAGES, outcome.pageCount)
            } else {
                MAX_DETAIL_SEARCH_PAGES
            }
            var page = 1
            while (page < ceiling) {
                val pageNo = page + 1
                val more = withContext(Dispatchers.IO) {
                    container.aggregator.groupedWithVariants(
                        sites = enabled,
                        keyword = anchor.searchKeyword,
                        page = pageNo,
                    )
                }
                page = pageNo
                if (more.items.isEmpty()) {
                    moreExhausted = true
                    MatchTrace.note("第 $page 页为空，提前到底")
                    break
                }
                outcome = container.aggregator.merge(listOf(outcome, more))
                collected = outcome
                // 合并后的组包含前面页的条目，这里不重复写候选明细，只留决策。
                applyFoundSources(pickGroup(outcome.groups.orEmpty(), anchor, trace = false))
                MatchTrace.note("翻到第 $page 页: 本页 ${more.items.size} 条 / 累计 ${outcome.items.size} 条")
            }
            nextPage = page + 1
            if (outcome.pageCount > 0 && nextPage > outcome.pageCount) moreExhausted = true
            MatchTrace.note(
                "自动加载: 第 1~$page 页" +
                    if (moreExhausted) "（已到底）" else "（可继续点「加载更多源」）",
            )
            MatchTrace.result(items.count { it.sourceId.isNotBlank() })
        } finally {
            MatchTrace.end()
            enriching = false
        }
    }

    /** 手动往后翻一页：从 [nextPage] 继续，不重搜前面几页，也不打扰正在播放的源。 */
    fun loadMoreSources() {
        val incoming = enrichAnchor ?: return
        if (moreLoading || moreExhausted || enriching) return
        val enabled = container.store.sources.value.filter { it.enabled }
        if (enabled.isEmpty()) {
            moreExhausted = true
            return
        }
        moreLoading = true
        scope.launch {
            MatchTrace.begin("继续找《${incoming.title}》第 $nextPage 页")
            try {
                val page = withContext(Dispatchers.IO) {
                    container.aggregator.groupedWithVariants(
                        sites = enabled,
                        keyword = incoming.searchKeyword,
                        page = nextPage,
                    )
                }
                if (page.items.isEmpty()) {
                    moreExhausted = true
                    MatchTrace.note("第 $nextPage 页为空，没有更多了")
                } else {
                    val merged = collected?.let { container.aggregator.merge(listOf(it, page)) } ?: page
                    collected = merged
                    val anchor = incoming.copy(year = incoming.year.ifBlank { douban?.year.orEmpty() })
                    applyFoundSources(pickGroup(merged.groups.orEmpty(), anchor, trace = false))
                    MatchTrace.note("第 $nextPage 页: ${page.items.size} 条 / 累计 ${merged.items.size} 条")
                    nextPage++
                    if (merged.pageCount > 0 && nextPage > merged.pageCount) moreExhausted = true
                }
                MatchTrace.result(items.count { it.sourceId.isNotBlank() })
            } finally {
                MatchTrace.end()
                moreLoading = false
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
            val record = container.store.progressFor(base.historyKey)
            resumeRecord = record
            val lines = PlaylistParser.parse(base.playFrom, base.playUrl)
            val preferred = PlaylistParser.preferredGroupIndex(lines)
            // 同源记录优先沿用上次线路（不再要求是直链：分享页靠探测也能播），
            // 只有没记录或跨源记录时才按「像视频流」重选。
            val recorded = record
                ?.takeIf { it.sourceId == base.sourceId && it.vodId == base.vodId }
                ?.groupIndex
            lineIndex = (recorded ?: preferred).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
            favorite = container.store.isFavorite(base.key)
        }
    }

    val playGroups = remember(resolved) {
        PlaylistParser.parse(resolved.playFrom, resolved.playUrl)
    }
    // lineIndex 由异步补源 / 换源写入，可能与当前 resolved 的线路数不一致；
    // 渲染与取集统一收敛，保证「有线路时恰好一条高亮」，也不会因越界出现空选集。
    val safeLineIndex = lineIndex.coerceIn(0, (playGroups.size - 1).coerceAtLeast(0))
    val episodes = playGroups.getOrNull(safeLineIndex)?.episodes.orEmpty()

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
                            // 换线路保留当前集号：同一条片的不同线路集数基本一致，
                            // 退回第 1 集反而丢进度（播放器内部会按新线路集数收敛）。
                            onGroupChange = { g -> playing = playing?.copy(groupIndex = g) },
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
                                enriching -> "正在查找播放源…"
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
                // 串片纠错入口：标注这些源是按哪部豆瓣片匹配来的，不对可一键重选。
                if (enrichAnchor != null && playing == null) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "源匹配自豆瓣《${enrichAnchor.title}》",
                                style = MaterialTheme.typography.labelSmall,
                                color = Ink.Text3,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "重新匹配",
                                style = MaterialTheme.typography.labelMedium,
                                color = Accent.Brand,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onRematch(enrichAnchor) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                item {
                    SectionHeader(
                        title = "视频源",
                        trailing = if (hasRealSource) "${items.size} 个" else null,
                    )
                }
                if (!hasRealSource) {
                    // 豆瓣条目刚进来、源还在后台找：给出诚实的状态，而不是一个空壳源。
                    item {
                        Text(
                            text = if (enriching) "正在你的采集源里找这部片…" else "没在源里找到这部片，可点上方「重新匹配」重试",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink.Text2,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    item {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items.forEach { item ->
                                LineChip(
                                    // 同一部片在一个源里可能有粤语 / 国语 / 英文多条，
                                    // 只显示源名会变成三个一模一样的按钮，用户没法选。
                                    text = MediaTag.of(item.title)
                                        ?.let { "${item.sourceName} · $it" }
                                        ?: item.sourceName,
                                    selected = item.key == selected.key,
                                    onClick = {
                                        // 点源只做「选中」：更新线路 / 选集列表，不打断当前播放。
                                        // 真正切源发生在点某一集时，避免误碰一次源就把正在看的进度丢掉。
                                        selected = item
                                    },
                                )
                            }
                        }
                    }
                    // 当前源的附加信息：线路数 / 集数 / 播放地址状态。
                    item {
                        SourceInfoRow(
                            item = resolved,
                            groupCount = playGroups.size,
                            episodeCount = episodes.size,
                            doubanYear = douban?.year.orEmpty(),
                        )
                    }
                }

                // 只有豆瓣入口带可靠锚点（年份 / 原名 / 季号）时才能判断新条目是不是同一部片，
                // 所以按钮只在这一条路径出现；普通搜索入口可回搜索页「加载更多」。
                if (enrichAnchor != null && !enriching) {
                    item {
                        when {
                            moreLoading -> Text(
                                text = "正在加载更多源…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ink.Text2,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            )

                            moreExhausted -> Text(
                                text = if (hasRealSource) "已加载全部结果 · 共 ${nextPage - 1} 页" else "没有更多了",
                                style = MaterialTheme.typography.labelSmall,
                                color = Ink.Text3,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            )

                            else -> GhostButton(
                                text = "加载更多源",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                onClick = ::loadMoreSources,
                            )
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
                                    selected = index == safeLineIndex,
                                    onClick = {
                                        // 线路同视频源：只切换选集列表，点集才切播放地址。
                                        lineIndex = index
                                    },
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
                            active != null && active.item.key == resolved.key && active.groupIndex == safeLineIndex ->
                                active.episodeIndex
                            record != null && record.groupIndex == safeLineIndex -> record.episodeIndex
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
                                // 只标记最后播放的那一集；之前播过的不再铺成一片「已看过」的暗格子。
                                onClick = {
                                    val active = playing
                                    if (active != null && active.item.key == resolved.key) {
                                        playing = active.copy(groupIndex = safeLineIndex, episodeIndex = index)
                                    } else {
                                        playing = PlayTarget(resolved, safeLineIndex, index)
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
private fun SourceInfoRow(item: VodItem, groupCount: Int, episodeCount: Int, doubanYear: String = "") {
    // 源站年份只在这里显示，不进顶部资料卡——同一部片在不同源上写的年份常常不同
    // （《功夫》豆瓣 2004，源站写 2026 的更新年份），混在豆瓣信息里就变成「年份乱跳」。
    val sourceYear = Regex("(?:19|20)[0-9]{2}").find(item.year)?.value.orEmpty()
    val showYear = sourceYear.isNotBlank() && sourceYear != doubanYear
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("当前：${item.sourceName}", style = MaterialTheme.typography.labelMedium, color = Ink.Text1)
        if (showYear) {
            Spacer(Modifier.width(8.dp))
            Text("源站 $sourceYear", style = MaterialTheme.typography.labelSmall, color = Ink.Text3)
        }
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
