package com.starpick.app.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.starpick.app.AppContainer
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.WatchProgress
import com.starpick.app.core.parser.PlaylistParser
import com.starpick.app.ui.component.EpisodeCell
import com.starpick.app.ui.component.FlowPosterGrid
import com.starpick.app.ui.component.LineChip
import com.starpick.app.ui.component.formatPosition
import com.starpick.app.ui.theme.Accent
import com.starpick.app.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内嵌播放器：只负责「一块视频画面 + 控制层 + 选集抽屉」，不再是独立页面。
 *
 * 由 [DetailScreen] 把它放在详情页顶部（竖屏 16:9、横屏铺满），
 * 详情信息 / 简介 / 换源 / 线路 / 选集始终在它下方，全程同一个页面。
 * 尺寸、背景、返回语义全部交给调用方通过 [modifier] 与 [onExit] 决定。
 *
 * 线程纪律（这是之前崩溃过的点）：
 *  - ExoPlayer 的所有读写都在主线程，轮询协程显式切 Dispatchers.Main.immediate。
 *  - 退出时先取消轮询（LaunchedEffect 自动），再用缓存的位置落盘，最后 release。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSurface(
    container: AppContainer,
    item: VodItem,
    groupIndex: Int,
    episodeIndex: Int,
    onGroupChange: (Int) -> Unit,
    onEpisodeChange: (Int) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val groups = remember(item) { PlaylistParser.parse(item.playFrom, item.playUrl) }
    val episodes = remember(groups, groupIndex) {
        groups.getOrNull(groupIndex)?.episodes.orEmpty()
    }

    // 当前集完全由详情页传入，播放器内部不再另存一份，避免两处状态打架。
    val safeEpisode = episodeIndex.coerceAtMost((episodes.size - 1).coerceAtLeast(0))
    val currentUrl = episodes.getOrNull(safeEpisode)?.url
    val currentName = episodes.getOrNull(safeEpisode)?.name

    if (currentUrl.isNullOrBlank() || episodes.isEmpty()) {
        Box(
            modifier = modifier.background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("播放地址无效", color = Color.White)
                Text(
                    text = "在下方换一集或换源",
                    color = Accent.Gold,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clickable(onClick = onExit)
                        .padding(8.dp),
                )
            }
        }
        return
    }

    val player = remember(currentUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(currentUrl))
            playWhenReady = true
            prepare()
        }
    }

    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var showControls by remember { mutableStateOf(true) }
    var showSheet by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var lastPosition by remember { mutableStateOf(0L) }
    var lastDuration by remember { mutableStateOf(0L) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 横屏返回只退全屏（转回竖屏），不离开详情页。
    val exitFullscreen: () -> Unit = {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    // 横屏时本组件独占全屏，它的 BackHandler 注册得比详情页晚，优先响应；
    // 竖屏时禁用，返回交给详情页处理（直接退出详情，不再多一层"收起播放器"）。
    BackHandler(enabled = isLandscape && !showSheet) { exitFullscreen() }

    // 引入时恢复进度（仅当记录属于当前线路 + 当前集）。
    LaunchedEffect(player, item.key) {
        withContext(Dispatchers.Main.immediate) {
            val saved = container.store.progressFor(item.contentKey)
            if (saved != null &&
                saved.groupIndex == groupIndex &&
                saved.episodeIndex == safeEpisode &&
                saved.positionMs > 0
            ) {
                player.seekTo(saved.positionMs)
            }
        }
    }

    // 500ms 轮询：刷新 UI 状态；每 5 秒落一次盘。
    LaunchedEffect(player) {
        var tick = 0
        while (isActive) {
            delay(500)
            val snapshot = withContext(Dispatchers.Main.immediate) {
                Triple(
                    player.currentPosition.coerceAtLeast(0L),
                    player.duration.takeIf { it > 0 } ?: 0L,
                    player.isPlaying,
                )
            }
            val (pos, dur, playing) = snapshot
            if (!scrubbing) positionMs = pos
            durationMs = dur
            isPlaying = playing
            lastPosition = pos
            lastDuration = dur
            if (++tick % 10 == 0) {
                container.store.saveProgress(
                    WatchProgress(
                        key = item.contentKey,
                        title = item.title,
                        cover = item.cover,
                        sourceId = item.sourceId,
                        vodId = item.vodId,
                        groupIndex = groupIndex,
                        episodeIndex = safeEpisode,
                        episodeName = currentName ?: "",
                        positionMs = lastPosition,
                        durationMs = lastDuration,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    // 退出：先落盘（用缓存快照，不触碰 player），再切主线程 release。
    DisposableEffect(player) {
        onDispose {
            val record = WatchProgress(
                key = item.contentKey,
                title = item.title,
                cover = item.cover,
                sourceId = item.sourceId,
                vodId = item.vodId,
                groupIndex = groupIndex,
                episodeIndex = safeEpisode,
                episodeName = currentName ?: "",
                positionMs = lastPosition,
                durationMs = lastDuration,
                updatedAt = System.currentTimeMillis(),
            )
            container.appScope.launch { container.store.saveProgress(record) }
            // 让「继续观看」拿到的是最后实际播放的源，而不是第一次打开详情时的那个。
            container.appScope.launch { container.store.rememberItem(item) }
            container.appScope.launch(Dispatchers.Main.immediate) {
                runCatching { player.release() }
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(playbackSpeed) {
        player.setPlaybackSpeed(playbackSpeed)
    }

    // 横屏即全屏：隐藏系统栏；退出时恢复。
    LaunchedEffect(isLandscape) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (isLandscape) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 控制条 3.5 秒自动淡出（暂停时常驻，拖动进度时不淡出）。
    LaunchedEffect(showControls, isPlaying, scrubbing) {
        if (showControls && isPlaying && !scrubbing) {
            delay(3_500)
            showControls = false
        }
    }

    val togglePlay: () -> Unit = {
        player.playWhenReady = !player.playWhenReady
        isPlaying = player.playWhenReady
        showControls = true
    }
    val seekBy: (Long) -> Unit = { delta ->
        val target = (player.currentPosition + delta).coerceIn(0L, durationMs.coerceAtLeast(0L))
        player.seekTo(target)
        positionMs = target
        showControls = true
    }
    val playEpisode: (Int) -> Unit = { index ->
        onEpisodeChange(index.coerceIn(0, (episodes.size - 1).coerceAtLeast(0)))
        showSheet = false
        showControls = true
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    this.player = player
                    // 默认控制器样式与深色设计冲突，全部自绘。
                    useController = false
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        // 整块区域的点击：单击显隐控制条，双击播放 / 暂停。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls },
                        onDoubleTap = { togglePlay() },
                    )
                },
        )

        if (showControls) {
            PlayerControls(
                title = item.title,
                episodeName = currentName ?: "",
                isPlaying = isPlaying,
                positionMs = if (scrubbing) (scrubFraction * durationMs.toFloat()).toLong() else positionMs,
                durationMs = durationMs,
                playbackSpeed = playbackSpeed,
                hasPrev = safeEpisode > 0,
                hasNext = safeEpisode < episodes.size - 1,
                isLandscape = isLandscape,
                onBack = { if (isLandscape) exitFullscreen() else onExit() },
                onTogglePlay = togglePlay,
                onSeekBy = seekBy,
                onPrev = { playEpisode(safeEpisode - 1) },
                onNext = { playEpisode(safeEpisode + 1) },
                onSpeed = {
                    playbackSpeed = when (playbackSpeed) {
                        1f -> 1.25f
                        1.25f -> 1.5f
                        1.5f -> 2f
                        else -> 1f
                    }
                },
                onEpisodes = { showSheet = true },
                onFullscreen = {
                    activity?.requestedOrientation = if (isLandscape) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                },
                scrubbing = scrubbing,
                scrubFraction = scrubFraction,
                onScrub = {
                    scrubbing = true
                    scrubFraction = it
                    showControls = true
                },
                onScrubFinished = {
                    // 时长还没拿到时不 seek，否则会把进度打到 0。
                    if (durationMs > 0) {
                        val target = (scrubFraction * durationMs.toFloat()).toLong()
                        player.seekTo(target)
                        positionMs = target
                    }
                    scrubbing = false
                },
            )
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = Ink.Raised,
            contentColor = Ink.Text1,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 6.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x38FFFFFF)),
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "选集",
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink.Text1,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "共 ${episodes.size} 集",
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink.Text3,
                    )
                }

                if (groups.size > 1) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        groups.forEachIndexed { index, group ->
                            LineChip(
                                text = group.from,
                                selected = index == groupIndex,
                                onClick = {
                                    if (index != groupIndex) {
                                        onGroupChange(index)
                                        onEpisodeChange(0)
                                    }
                                },
                            )
                        }
                    }
                }

                FlowPosterGrid(
                    columns = 5,
                    itemCount = episodes.size,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalSpacing = 8.dp,
                    verticalSpacing = 8.dp,
                ) { index, itemModifier ->
                    EpisodeCell(
                        label = "${index + 1}",
                        modifier = itemModifier,
                        selected = index == safeEpisode,
                        seen = index < safeEpisode,
                        onClick = { playEpisode(index) },
                    )
                }

                Text(
                    text = "双击画面播放 / 暂停，选集不打断当前播放",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.Text3,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** 控制层：顶部渐变 + 中央播放键 + 底部进度与功能行。 */
@Composable
private fun PlayerControls(
    title: String,
    episodeName: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    hasPrev: Boolean,
    hasNext: Boolean,
    isLandscape: Boolean,
    scrubbing: Boolean,
    scrubFraction: Float,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSpeed: () -> Unit,
    onEpisodes: () -> Unit,
    onFullscreen: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color(0xB3000000),
                            1f to Color.Transparent,
                        ),
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconHit(onBack, Icons.AutoMirrored.Filled.ArrowBack, "返回")
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (episodeName.isNotBlank()) {
                    Text(
                        text = episodeName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0x99FFFFFF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = "${formatSpeed(playbackSpeed)}x",
                style = MaterialTheme.typography.labelMedium,
                color = Accent.Gold,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSpeed)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 40.dp else 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasPrev) {
                IconHit(onPrev, Icons.Filled.SkipPrevious, "上一集", 40.dp)
            }
            TextAction("−10s") { onSeekBy(-10_000L) }
            PlayPauseButton(isPlaying, onTogglePlay, big = isLandscape)
            TextAction("+10s") { onSeekBy(10_000L) }
            if (hasNext) {
                IconHit(onNext, Icons.Filled.SkipNext, "下一集", 40.dp)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            1f to Color(0xCC000000),
                        ),
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            PlayerSeekBar(
                fraction = if (scrubbing) scrubFraction else {
                    if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                },
                scrubbing = scrubbing,
                onScrub = onScrub,
                onScrubFinished = onScrubFinished,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${formatPosition(positionMs)} / ${formatPosition(durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xB3FFFFFF),
                )
                Spacer(Modifier.weight(1f))
                TextAction("选集", onClick = onEpisodes)
                IconHit(
                    onClick = onFullscreen,
                    icon = if (isLandscape) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    description = "全屏",
                    size = 34.dp,
                )
            }
        }
    }
}

/**
 * 播放进度条：3dp 细轨 + 9dp 小圆点，拖动时加粗放大，金色已播段。
 * 手势自己接：横向拖动改进度，点一下轨道直接跳。
 */
@Composable
private fun PlayerSeekBar(
    fraction: Float,
    scrubbing: Boolean,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
) {
    val trackHeight = if (scrubbing) 5.dp else 3.dp
    val thumbSize = if (scrubbing) 14.dp else 9.dp
    val safeFraction = fraction.coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onScrub((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        onScrub((change.position.x / size.width.toFloat()).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onScrubFinished() },
                    onDragCancel = { onScrubFinished() },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onScrub((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                    onScrubFinished()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(Color(0x33FFFFFF)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(safeFraction)
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(Accent.Gold),
        )
        // 拇指用两侧空白 weight 顶到目标位置，避免再引一层 BoxWithConstraints。
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(safeFraction.coerceAtLeast(0.0001f)))
            Box(
                modifier = Modifier
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(if (scrubbing) Accent.Gold else Color.White),
            )
            Box(Modifier.weight((1f - safeFraction).coerceAtLeast(0.0001f)))
        }
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit, big: Boolean = false) {
    val size = if (big) 62.dp else 52.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 2))
            .background(Color(0x2EFFFFFF))
            .border(1.dp, Color(0x47FFFFFF), RoundedCornerShape(size / 2))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "暂停" else "播放",
            tint = Color.White,
            modifier = Modifier.size(if (big) 26.dp else 22.dp),
        )
    }
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun IconHit(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 2))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(size * 0.45f),
        )
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
