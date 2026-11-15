package com.starpick.app.ui.screen

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.starpick.app.AppContainer
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.WatchProgress
import com.starpick.app.core.parser.PlaylistParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 播放页：ExoPlayer + 顶部返回栏 + 底部选集/倍速抽屉。
 *
 * 返回稳定性要点：
 *  - 进度协程只读 `player.currentPosition`，退出时先取消协程再 release，避免访问已释放的播放器。
 *  - 落盘用局部缓存的 position，不在 onDispose 里触碰 player。
 */
@Composable
fun PlayerScreen(
    container: AppContainer,
    item: VodItem,
    groupIndex: Int,
    episodeIndex: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // 解析全部线路与选集，供底部选集抽屉使用。
    val groups = remember(item) { PlaylistParser.parse(item.playFrom, item.playUrl) }

    // 当前线路：页内可切，初始取导航传入的 groupIndex。
    var currentGroup by remember(groupIndex) { mutableIntStateOf(groupIndex) }
    val episodes = remember(groups, currentGroup) {
        groups.getOrNull(currentGroup)?.episodes.orEmpty()
    }
    val episode = episodes.getOrNull(episodeIndex)

    if (episode == null || episodes.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("播放地址无效，返回换一集或换源", color = Color.White)
                TextButton(onClick = onBack) { Text("返回", color = Color.White) }
            }
        }
        return
    }

    // 当前播放的集：允许在页内切集（切集即重建播放器）。
    var currentEpisode by remember(currentGroup, episodeIndex) { mutableIntStateOf(episodeIndex) }
    val currentUrl = episodes[currentEpisode].url
    val currentName = episodes[currentEpisode].name

    val player = remember(currentUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(currentUrl))
            playWhenReady = true
            prepare()
        }
    }

    var showPanel by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(1f) }
    // 退出时落盘用的最新进度快照，避免 onDispose 访问已 release 的 player。
    var lastPosition by remember { mutableStateOf(0L) }
    var lastDuration by remember { mutableStateOf(0L) }

    // 进入恢复进度 + 每 5 秒刷新一次快照并落盘。
    LaunchedEffect(player, item.key) {
        val saved = container.store.progressFor(item.key)
        if (saved != null &&
            saved.groupIndex == currentGroup &&
            saved.episodeIndex == currentEpisode &&
            saved.positionMs > 0
        ) {
            player.seekTo(saved.positionMs)
        }
        while (isActive) {
            delay(5_000)
            lastPosition = player.currentPosition.coerceAtLeast(0L)
            lastDuration = player.duration.takeIf { it > 0 } ?: 0L
            container.store.saveProgress(
                WatchProgress(
                    key = item.key,
                    title = item.title,
                    cover = item.cover,
                    groupIndex = currentGroup,
                    episodeIndex = currentEpisode,
                    episodeName = currentName,
                    positionMs = lastPosition,
                    durationMs = lastDuration,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    // 释放播放器前先落一次盘；顺序：先释放，再尝试保存（用已缓存的快照，不触碰 player）。
    DisposableEffect(player) {
        onDispose {
            val record = WatchProgress(
                key = item.key,
                title = item.title,
                cover = item.cover,
                groupIndex = currentGroup,
                episodeIndex = currentEpisode,
                episodeName = currentName,
                positionMs = lastPosition,
                durationMs = lastDuration,
                updatedAt = System.currentTimeMillis(),
            )
            container.appScope.launch { container.store.saveProgress(record) }
            runCatching { player.release() }
        }
    }

    // 倍速即时生效。
    LaunchedEffect(playbackSpeed) {
        player.setPlaybackSpeed(playbackSpeed)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    this.player = player
                    useController = true
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        // 顶部：返回 + 标题，点击空白区可收起/展开底部面板。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = { showPanel = !showPanel },
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xCC000000), Color.Transparent),
                        ),
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = currentName,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "${playbackSpeed}x",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable(
                            interactionSource = null,
                            indication = null,
                        ) {
                            playbackSpeed = when (playbackSpeed) {
                                1f -> 1.25f
                                1.25f -> 1.5f
                                1.5f -> 2f
                                else -> 1f
                            }
                        }
                        .padding(12.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            // 底部面板：线路 + 选集 + 倍速。
            if (showPanel) {
                Surface(
                    color = Color(0xE6000000),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                ) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        // 线路
                        if (groups.size > 1) {
                            Text(
                                "线路",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            LazyRow(
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(groups.size) { index ->
                                    LineChip(
                                        label = groups[index].from,
                                        selected = index == currentGroup,
                                        onClick = {
                                            if (index != currentGroup) {
                                                currentGroup = index
                                                currentEpisode = 0
                                            }
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        Text(
                            "选集",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(episodes.size) { index ->
                                EpisodeChip(
                                    index = index,
                                    selected = index == currentEpisode,
                                    onClick = { currentEpisode = index },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeChip(index: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color.White else Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${index + 1}",
            color = if (selected) Color.Black else Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun LineChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color.White else Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
