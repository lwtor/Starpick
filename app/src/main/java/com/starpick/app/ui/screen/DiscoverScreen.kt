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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.starpick.app.core.model.DoubanSubject
import com.starpick.app.ui.component.EmptyHint
import com.starpick.app.ui.component.FlowPosterGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class HotTab(val label: String, val type: String) {
    Movie("电影", "movie"),
    Tv("电视剧", "tv"),
}

/**
 * 首页「发现」：豆瓣热门榜单。
 *
 * 结构（参考主流视频 App 的首页）：
 *  1. 顶部大卡片横向轮播（Hero 区），展示评分 / 更新状态，点击进入详情
 *  2. 电影 / 电视剧 分类切换
 *  3. 海报网格（评分角标 + 剧集信息）
 *
 * 数据只来自豆瓣榜单（片名 + 海报 + 评分）；点开条目后由导航层拿片名去用户的 CMS 源聚合搜索。
 */
@Composable
fun DiscoverScreen(
    container: AppContainer,
    onPick: (title: String) -> Unit,
) {
    var tab by remember { mutableStateOf(HotTab.Movie) }
    var loading by remember { mutableStateOf(false) }
    var subjects by remember { mutableStateOf<List<DoubanSubject>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { container.douban.hot(tab.type) }
            subjects = result
            if (result.isEmpty()) error = "豆瓣榜单暂时拿不到数据，请稍后重试"
            loading = false
        }
    }

    LaunchedEffect(tab) { load() }

    when {
        loading && subjects.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("正在加载豆瓣${tab.label}热门…")
                }
            }
        }

        error != null && subjects.isEmpty() -> EmptyHint("加载失败", error ?: "")

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // 分类切换
            item {
                CategoryTabs(selected = tab, onSelect = { tab = it })
            }

            // 英雄轮播区：取前 6 部做大卡片横向轮播。
            item {
                val hero = subjects.take(6)
                if (hero.isNotEmpty()) {
                    HeroCarousel(subjects = hero, onPick = onPick)
                }
            }

            // 海报网格标题
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${tab.label}热门",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "共 ${subjects.size} 部",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 海报网格：手写分行（不能用 LazyVerticalGrid，会与 LazyColumn 纵向滚动冲突崩溃）。
            item {
                if (loading && subjects.isNotEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    FlowPosterGrid(
                        columns = 3,
                        itemCount = subjects.size,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) { index, itemModifier ->
                        PosterCard(
                            subject = subjects[index],
                            modifier = itemModifier,
                            onClick = { onPick(subjects[index].title) },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/** 电影 / 电视剧 分类切换。 */
@Composable
private fun CategoryTabs(selected: HotTab, onSelect: (HotTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HotTab.entries.forEach { entry ->
            val isSelected = entry == selected
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.clickable { onSelect(entry) },
            ) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 英雄轮播：大卡片横向滑动，展示评分与更新状态。用 LazyRow 实现，稳定可靠。 */
@Composable
private fun HeroCarousel(subjects: List<DoubanSubject>, onPick: (String) -> Unit) {
    val listState = rememberLazyListState()
    var currentIndex by remember { mutableIntStateOf(0) }

    // 同步指示点：监听滚动位置，把当前可见的第一项当作当前页。
    LaunchedEffect(listState, subjects.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index -> currentIndex = index.coerceIn(0, (subjects.size - 1).coerceAtLeast(0)) }
    }

    // 自动轮播：每 5 秒滑到下一张；用户手动拖动打断后由 scroll 回调刷新当前位置。
    LaunchedEffect(listState, subjects.size) {
        if (subjects.size <= 1) return@LaunchedEffect
        while (true) {
            delay(5_000)
            if (!listState.isScrollInProgress) {
                val next = (currentIndex + 1) % subjects.size
                listState.animateScrollToItem(next)
            }
        }
    }

    Column {
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(subjects, key = { it.id }) { subject ->
                HeroCard(
                    subject = subject,
                    modifier = Modifier.fillParentMaxWidth(0.82f),
                    onClick = { onPick(subject.title) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 指示点
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            subjects.forEachIndexed { index, _ ->
                val active = index == currentIndex
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(width = if (active) 18.dp else 6.dp, height = 6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        ),
                )
            }
        }
    }
}

/** 英雄卡片：大图 + 底部渐变遮罩 + 片名 + 评分。 */
@Composable
private fun HeroCard(subject: DoubanSubject, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(shape)
            .clickable(onClick = onClick)
            .background(Color(0xFF2A3140)),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(subject.cover)
                .crossfade(true)
                .build(),
            contentDescription = subject.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // 底部渐变遮罩，保证文字可读。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.4f to Color.Transparent,
                            1f to Color(0xE6000000),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (subject.rate.isNotBlank()) {
                    Text(
                        text = subject.rate,
                        color = Color(0xFFFFB400),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (subject.isNew) {
                    Surface(
                        color = Color(0xFFE5484D),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = "新",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = subject.title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subject.episodesInfo.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subject.episodesInfo,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 海报卡片：封面 + 评分角标 + 片名 + 剧集信息。 */
@Composable
private fun PosterCard(subject: DoubanSubject, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Box {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(subject.cover)
                    .crossfade(true)
                    .build(),
                contentDescription = subject.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(shape)
                    .background(Color(0xFFE3EAF3)),
            )
            if (subject.rate.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = subject.rate,
                        color = Color(0xFFFFB400),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = subject.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subject.episodesInfo.isNotBlank()) {
            Text(
                text = subject.episodesInfo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
