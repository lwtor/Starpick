package com.starpick.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.starpick.app.AppContainer
import com.starpick.app.core.model.DoubanSubject
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.WatchProgress
import com.starpick.app.ui.component.EmptyState
import com.starpick.app.ui.component.FlowPosterGrid
import com.starpick.app.ui.component.PosterCard
import com.starpick.app.ui.component.PrimaryButton
import com.starpick.app.ui.component.SectionHeader
import com.starpick.app.ui.component.SegmentedTabs
import com.starpick.app.ui.component.ThinProgress
import com.starpick.app.ui.theme.Accent
import com.starpick.app.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val HOT_TABS = listOf(
    "movie" to "电影",
    "tv" to "电视剧",
)

/**
 * 首页「发现」。
 *
 * 结构参考主流视频 App：
 *  1. 整屏 Hero（豆瓣热门前 6 部）—— 一张图讲完「是什么、几分、要不要看」
 *  2. 继续观看（有播放记录才出现，横滑卡片带金色进度条）
 *  3. 电影 / 电视剧 分段切换 + 海报网格
 *
 * 数据只来自豆瓣榜单；点开条目后由导航层拿片名去用户的 CMS 源聚合搜索。
 * 没有任何采集源时，本页降级为「添加入口」，避免新用户看到空列表以为 App 坏了。
 */
@Composable
fun DiscoverScreen(
    container: AppContainer,
    onPick: (title: String) -> Unit,
    onResume: (VodItem, WatchProgress) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val sources by container.store.sources.collectAsState(initial = emptyList())
    val progress by container.store.progress.collectAsState(initial = emptyList())
    val recent by container.store.recent.collectAsState(initial = emptyList())

    var tabIndex by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var subjects by remember { mutableStateOf<List<DoubanSubject>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val load: () -> Unit = {
        if (!loading) {
            loading = true
            error = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    container.douban.hot(HOT_TABS[tabIndex].first)
                }
                subjects = result
                if (result.isEmpty()) error = "豆瓣榜单暂时拿不到数据"
                loading = false
            }
        }
    }

    LaunchedEffect(tabIndex) { load() }

    // 没有源：先解决「能不能用」，再谈「看什么」。
    if (sources.isEmpty()) {
        OnboardingContent(onOpenSettings = onOpenSettings)
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            // Hero 顶到状态栏下，因此这一项不再加顶部 inset。
            item {
                if (subjects.isNotEmpty()) {
                    HeroSection(subjects = subjects.take(6), onPick = onPick)
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(392.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (error != null) {
                            EmptyState(
                                title = "加载失败",
                                description = error ?: "",
                                actionLabel = "重试",
                                onAction = load,
                            )
                        } else {
                            CircularProgressIndicator(color = Accent.Brand, strokeWidth = 2.dp)
                        }
                    }
                }
            }

            if (progress.isNotEmpty()) {
                item { SectionHeader(title = "继续观看") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(progress.take(8), key = { it.key }) { record ->
                            val item = recent.firstOrNull { it.key == record.key }
                            ContinueCard(
                                record = record,
                                enabled = item != null,
                                onClick = { if (item != null) onResume(item, record) },
                            )
                        }
                    }
                }
            }

            item {
                Row(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp)) {
                    SegmentedTabs(
                        options = HOT_TABS.map { it.second },
                        selectedIndex = tabIndex,
                        onSelect = { if (it != tabIndex) tabIndex = it },
                    )
                }
            }

            item {
                SectionHeader(
                    title = "本周热门",
                    trailing = if (subjects.isEmpty()) null else "共 ${subjects.size} 部",
                )
            }

            item {
                if (loading && subjects.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Accent.Brand, strokeWidth = 2.dp)
                    }
                } else {
                    FlowPosterGrid(
                        columns = 3,
                        itemCount = subjects.size,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    ) { index, itemModifier ->
                        val s = subjects[index]
                        PosterCard(
                            title = s.title,
                            cover = s.cover,
                            rate = s.rate.takeIf { it.isNotBlank() },
                            badge = if (s.isNew) "新" else null,
                            subtitle = s.episodesInfo.takeIf { it.isNotBlank() },
                            modifier = itemModifier,
                            onClick = { onPick(s.title) },
                        )
                    }
                }
            }
        }

        // 品牌行固定在顶部（压在 Hero 上），随内容滚动始终保持可达。
        TopBar(
            loading = loading,
            onRefresh = load,
            onSearch = onOpenSearch,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

/** 顶部品牌行：星匣 + 豆瓣实时热门 + 刷新 / 搜索。 */
@Composable
private fun TopBar(
    loading: Boolean,
    onRefresh: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color(0xA60A0E14),
                        1f to Color.Transparent,
                    ),
                ),
            )
            .statusBarsPadding()
            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = Accent.Gold,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "星匣",
            style = MaterialTheme.typography.titleMedium,
            color = Ink.Text1,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "豆瓣实时热门",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xB3FFFFFF),
        )
        Spacer(Modifier.weight(1f))
        RoundIcon(Icons.Filled.Refresh, "刷新榜单", enabled = !loading, onClick = onRefresh)
        RoundIcon(Icons.Filled.Search, "搜索", enabled = true, onClick = onSearch)
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) Color.White else Color(0x59FFFFFF),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Hero：整屏大图 + 底部渐变 + 片名/评分/主按钮，6 秒自动翻页。 */
@Composable
private fun HeroSection(subjects: List<DoubanSubject>, onPick: (String) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { subjects.size })

    LaunchedEffect(subjects.size) {
        if (subjects.size <= 1) return@LaunchedEffect
        while (true) {
            delay(6_000)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % subjects.size)
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth().height(392.dp)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val subject = subjects[page]
            Box(modifier = Modifier.fillMaxSize().clickable { onPick(subject.title) }) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(subject.cover)
                        .crossfade(true)
                        .build(),
                    contentDescription = subject.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(Ink.Raised),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color(0xA60A0E14),
                                    0.3f to Color.Transparent,
                                    0.5f to Color.Transparent,
                                    0.8f to Color(0xDB0A0E14),
                                    1f to Ink.Base,
                                ),
                            ),
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                ) {
                    Text(
                        text = if (subject.isNew) "豆瓣热门 · 新上榜" else "豆瓣热门",
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent.Gold,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = subject.title,
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (subject.rate.isNotBlank()) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = Accent.Gold,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = subject.rate,
                                style = MaterialTheme.typography.titleSmall,
                                color = Accent.Gold,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            text = subject.episodesInfo.ifBlank { "豆瓣电影热门榜" },
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xB8FFFFFF),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    PrimaryButton(
                        text = "立即观看",
                        icon = Icons.Filled.PlayArrow,
                        onClick = { onPick(subject.title) },
                    )
                }
            }
        }

        // 翻页指示：细条，压在图片下沿。
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            subjects.indices.forEach { i ->
                val on = i == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .size(width = if (on) 18.dp else 6.dp, height = 3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (on) Accent.Gold else Color(0x3DFFFFFF)),
                )
            }
        }
    }
}

/** 继续观看卡片：16:9 缩略图 + 播放图标 + 进度条。 */
@Composable
private fun ContinueCard(record: WatchProgress, enabled: Boolean, onClick: () -> Unit) {
    val fraction = if (record.durationMs > 0) {
        (record.positionMs.toFloat() / record.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val remainMinutes = (record.durationMs - record.positionMs).coerceAtLeast(0L) / 60_000
    Column(
        modifier = Modifier
            .width(146.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Ink.Raised),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(record.cover)
                    .crossfade(true)
                    .build(),
                contentDescription = record.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x2E0A0E14)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(Color(0x8C0A0E14)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            ThinProgress(
                fraction = fraction,
                modifier = Modifier.align(Alignment.BottomCenter),
                height = 2.5.dp,
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = record.title,
            style = MaterialTheme.typography.labelLarge,
            color = Ink.Text1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = buildString {
                append(record.episodeName.ifBlank { "上次看到" })
                if (remainMinutes > 0) {
                    append(" · 剩 ")
                    append(remainMinutes)
                    append(" 分钟")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = Ink.Text3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 无源引导：把「为什么空 + 怎么填」一次说清，并给出唯一行动。 */
@Composable
private fun OnboardingContent(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.VideoLibrary,
            contentDescription = null,
            tint = Accent.Gold,
            modifier = Modifier.size(46.dp),
        )
        Spacer(Modifier.height(22.dp))
        Text(
            text = "还没有可用的片源",
            style = MaterialTheme.typography.headlineSmall,
            color = Ink.Text1,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "星匣不内置任何内容。添加一个苹果 CMS V10 采集源，就能开始搜索和播放。",
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.Text2,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(26.dp))
        PrimaryButton(text = "添加采集源", onClick = onOpenSettings)
        Spacer(Modifier.height(30.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StepLine("1", "拿到一个 V10 站点地址", "形如 https://example.com")
            StepLine("2", "粘贴到「设置 → 采集源」", "可以添加多个源，搜索时全部并发")
            StepLine("3", "回到首页点任意海报", "片名会拿去你的源里聚合搜索")
        }
    }
}

@Composable
private fun StepLine(no: String, title: String, hint: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = no,
            style = MaterialTheme.typography.labelLarge,
            color = Accent.Gold,
            modifier = Modifier.width(18.dp),
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.Text1,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = Ink.Text3,
            )
        }
    }
}
