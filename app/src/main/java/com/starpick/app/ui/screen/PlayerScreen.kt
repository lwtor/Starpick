package com.starpick.app.ui.screen

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

@Composable
fun PlayerScreen(
    container: AppContainer,
    item: VodItem,
    groupIndex: Int,
    episodeIndex: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val episode = remember(item) {
        PlaylistParser.parse(item.playFrom, item.playUrl)
            .getOrNull(groupIndex)
            ?.episodes
            ?.getOrNull(episodeIndex)
    }

    if (episode == null) {
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

    val player = remember(episode.url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(episode.url))
            playWhenReady = true
            prepare()
        }
    }

    val makeRecord = {
        WatchProgress(
            key = item.key,
            title = item.title,
            cover = item.cover,
            groupIndex = groupIndex,
            episodeIndex = episodeIndex,
            episodeName = episode.name,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0 } ?: 0L,
            updatedAt = System.currentTimeMillis(),
        )
    }

    // 进入时恢复进度，播放中每 5 秒落一次盘。
    // 自动连播、倍速手势等后续在这里挂 Player.Listener 即可。
    LaunchedEffect(player) {
        val saved = container.store.progressFor(item.key)
        if (saved != null &&
            saved.groupIndex == groupIndex &&
            saved.episodeIndex == episodeIndex &&
            saved.positionMs > 0
        ) {
            player.seekTo(saved.positionMs)
        }
        while (isActive) {
            delay(5_000)
            container.store.saveProgress(makeRecord())
        }
    }

    DisposableEffect(player) {
        onDispose {
            container.appScope.launch { container.store.saveProgress(makeRecord()) }
            player.release()
        }
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
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
                .background(Color(0x66000000)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回", color = Color.White) }
            Text(
                text = "${item.title} · ${episode.name}",
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}
