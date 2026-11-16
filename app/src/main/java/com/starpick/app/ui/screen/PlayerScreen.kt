package com.starpick.app.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.starpick.app.AppContainer
import com.starpick.app.core.api.ResolvedStream
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
import java.net.UnknownHostException

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
    /** 播放失败时让详情页重新拉一次详情，拿到刷新后的播放地址。 */
    onRefreshSource: () -> Unit = {},
    /** 播放失败时切到下一个源（采集站换源经常能救回来）。 */
    onSwitchSource: (() -> Unit)? = null,
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
                OverlayButton("重新获取地址") { onRefreshSource() }
            }
        }
        return
    }

    // 播放失败 / 重试：reloadToken 变化就会重建播放器。
    var reloadToken by remember { mutableIntStateOf(0) }
    var failure by remember(currentUrl, reloadToken) { mutableStateOf<String?>(null) }
    var buffering by remember(currentUrl, reloadToken) { mutableStateOf(true) }
    // 每个播放地址只静默重试一次，避免坏地址无限重连。
    var autoRetried by remember(currentUrl) { mutableStateOf(false) }
    // 自动兜底：同地址重试一次 → 换一条没试过的线路 → 换下一个源。
    // 采集站经常「一条线路死、另一条活」，让用户自己去点换源太重了。
    val triedLines = remember(item.key) { mutableStateListOf<Int>() }
    // 按内容归并（跨源同一部片）：避免 A→B→C→A 来回无限换源。
    var sourceSwitched by remember(item.contentKey) { mutableStateOf(false) }
    var notice by remember(item.key) { mutableStateOf<String?>(null) }
    // 只要出过一帧画面就不再按「超时」判失败，慢源不该被误杀。
    var everReady by remember(currentUrl) { mutableStateOf(false) }

    // 采集站的地址不一定是直链：`/share/<hash>` 这类藏了扩展名的 HLS、或者干脆是分享网页，
    // 原样丢给播放器会解析失败。先探一次，再按探测结果给 MIME 提示。
    var stream by remember(currentUrl) { mutableStateOf<ResolvedStream?>(null) }
    LaunchedEffect(currentUrl) {
        stream = withContext(Dispatchers.IO) { container.streams.resolve(currentUrl) }
    }
    val ready = stream
    if (ready == null) {
        Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = Accent.Gold,
                strokeWidth = 2.dp,
            )
        }
        return
    }

    val player = remember(ready, reloadToken) {
        // 采集站的 CDN 普遍按 UA 拦请求、且经常 302 跳到 http，
        // 默认数据源既不带浏览器 UA 也不允许跨协议重定向，会直接判成「播不出来」。
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(HTTP_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
            .apply {
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(ready.url)
                        .apply { if (ready.mimeType != null) setMimeType(ready.mimeType) }
                        .build(),
                )
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

    // 引入时恢复进度。必须同源同线路同集：换了源之后进度就不可比了，
    // 硬跳过去会落到一个不存在的时长上（表现为一进去就卡住/黑屏）。
    LaunchedEffect(player, item.key) {
        withContext(Dispatchers.Main.immediate) {
            val saved = container.store.progressFor(item.contentKey)
            if (saved != null &&
                saved.sourceId == item.sourceId &&
                saved.vodId == item.vodId &&
                saved.groupIndex == groupIndex &&
                saved.episodeIndex == safeEpisode &&
                saved.positionMs > 0
            ) {
                player.seekTo(saved.positionMs)
            }
        }
    }

    // 播放状态与错误：坏地址不再是一片没有反馈的黑屏。
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> buffering = true
                    Player.STATE_READY, Player.STATE_ENDED -> {
                        buffering = false
                        failure = null
                        notice = null
                        everReady = true
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                buffering = false
                // 1) 同一地址先静默重试一次：偶发断流、握手超时很常见。
                if (!autoRetried) {
                    autoRetried = true
                    reloadToken++
                    return
                }
                triedLines.add(groupIndex)
                // 2) 换一条还没试过的线路。同一部片不同线路的 CDN 是独立的，一条死一条活。
                val nextLine = groups.indices.firstOrNull { it != groupIndex && it !in triedLines }
                if (nextLine != null) {
                    notice = "该线路播放失败，已自动切到下一条线路"
                    onGroupChange(nextLine)
                    return
                }
                // 3) 同源所有线路都不行，再换下一个源，但每个内容只自动换一次。
                if (!sourceSwitched && onSwitchSource != null) {
                    sourceSwitched = true
                    notice = "这个源播放失败，已自动换源"
                    onSwitchSource()
                    return
                }
                failure = describePlaybackError(error)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 缓冲久了才判失败。之前是 15 秒，慢源（自建站 / 海外 CDN）首帧常常要 20 秒以上，
    // 会被误判成「源站没有响应」；而且只要出过画面就不再判超时，短暂卡顿不该打断播放。
    LaunchedEffect(buffering, failure, everReady) {
        if (!buffering || failure != null || everReady) return@LaunchedEffect
        delay(30_000)
        if (failure == null) {
            failure = "源站长时间没有响应（可能已关站、换了域名或被网络拦截），可以换源试试"
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

    // 控制条 3 秒无操作自动收起（暂停时常驻，拖动进度时不收起）。
    // epoch 每次交互 +1 → LaunchedEffect 重启 → 倒计时从头算。
    var controlsEpoch by remember { mutableIntStateOf(0) }
    val nudgeControls: () -> Unit = {
        showControls = true
        controlsEpoch++
    }
    LaunchedEffect(showControls, controlsEpoch, isPlaying, scrubbing) {
        if (showControls && isPlaying && !scrubbing) {
            delay(3_000)
            showControls = false
        }
    }

    // 开播或换集：控制条重新出现，给用户一个「这是哪一集」的确认。
    LaunchedEffect(currentUrl) {
        showControls = true
        controlsEpoch++
    }

    val togglePlay: () -> Unit = {
        player.playWhenReady = !player.playWhenReady
        isPlaying = player.playWhenReady
        nudgeControls()
    }
    val playEpisode: (Int) -> Unit = { index ->
        onEpisodeChange(index.coerceIn(0, (episodes.size - 1).coerceAtLeast(0)))
        showSheet = false
        nudgeControls()
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
                    // 顶部容器为 5:3，视频按裁剪填充，避免上下黑边。
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    // 默认控制器样式与深色设计冲突，全部自绘。
                    useController = false
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        // 整块区域的点击：单击切换控制条显隐，双击播放 / 暂停。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (showControls) {
                                showControls = false
                            } else {
                                nudgeControls()
                            }
                        },
                        onDoubleTap = { togglePlay() },
                    )
                },
        )

        // 失败时给出可操作的出口，而不是继续转圈。
        val errorText = failure
        if (errorText != null) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xD9000000)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "播放失败",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0x99FFFFFF),
                        modifier = Modifier
                            .padding(horizontal = 24.dp, vertical = 6.dp)
                            .width(if (isLandscape) 420.dp else 260.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = ready.url.substringAfter("://").substringBefore("/"),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0x59FFFFFF),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (onSwitchSource != null) {
                            OverlayButton("换源") {
                                failure = null
                                buffering = true
                                autoRetried = false
                                notice = null
                                triedLines.clear()
                                onSwitchSource()
                            }
                        }
                        OverlayButton("重试") {
                            failure = null
                            buffering = true
                            autoRetried = false
                            notice = null
                            triedLines.clear()
                            sourceSwitched = false
                            reloadToken++
                            onRefreshSource()
                        }
                        if (safeEpisode < episodes.size - 1) {
                            OverlayButton("下一集") { playEpisode(safeEpisode + 1) }
                        }
                        OverlayButton("返回") { onExit() }
                    }
                }
            }
        } else if (buffering) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = Accent.Gold,
                    strokeWidth = 2.dp,
                )
                val hint = notice
                if (hint != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0x99FFFFFF),
                    )
                }
            }
        }

        PlayerControls(
            title = item.title,
            episodeName = currentName ?: "",
            isPlaying = isPlaying,
            positionMs = if (scrubbing) (scrubFraction * durationMs.toFloat()).toLong() else positionMs,
            durationMs = durationMs,
            playbackSpeed = playbackSpeed,
            isLandscape = isLandscape,
            visible = showControls,
            onBack = { if (isLandscape) exitFullscreen() else onExit() },
            onTogglePlay = togglePlay,
            onSpeed = {
                playbackSpeed = when (playbackSpeed) {
                    1f -> 1.25f
                    1.25f -> 1.5f
                    1.5f -> 2f
                    else -> 1f
                }
                nudgeControls()
            },
            onEpisodes = {
                showSheet = true
                nudgeControls()
            },
            onFullscreen = {
                activity?.requestedOrientation = if (isLandscape) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
                nudgeControls()
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
                nudgeControls()
            },
        )
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

/**
 * 控制层：顶部标题条 + 底部播放条，两条各自带出入动画。
 *
 * 顶栏从上往下滑入、底栏从下往上滑入，隐藏时反向滑出并淡出，
 * 滑块式而不是硬切，暂停/播放时不会「闪」一下。
 */
@Composable
private fun PlayerControls(
    title: String,
    episodeName: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    isLandscape: Boolean,
    scrubbing: Boolean,
    scrubFraction: Float,
    visible: Boolean,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSpeed: () -> Unit,
    onEpisodes: () -> Unit,
    onFullscreen: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
) {
    val duration = 240
    val slide = tween<IntOffset>(durationMillis = duration)
    val fade = tween<Float>(durationMillis = duration)
    // 顶栏热区吃掉点击，避免点标题条空白处把控制条关掉。
    val swallowTap = Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = {},
    )

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(animationSpec = slide) { -it } + fadeIn(animationSpec = fade),
            exit = slideOutVertically(animationSpec = slide) { -it } + fadeOut(animationSpec = fade),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color(0xC9000000),
                                0.65f to Color(0x66000000),
                                1f to Color.Transparent,
                            ),
                        ),
                    )
                    .then(swallowTap)
                    .then(if (isLandscape) Modifier.displayCutoutPadding() else Modifier)
                    .padding(start = 2.dp, end = 8.dp, top = 2.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconHit(onBack, Icons.AutoMirrored.Filled.ArrowBack, "返回")
                Column(Modifier.weight(1f).padding(end = 4.dp)) {
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
                TextAction("选集", onClick = onEpisodes)
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
        }

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(animationSpec = slide) { it } + fadeIn(animationSpec = fade),
            exit = slideOutVertically(animationSpec = slide) { it } + fadeOut(animationSpec = fade),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.4f to Color(0x73000000),
                                1f to Color(0xE6000000),
                            ),
                        ),
                    )
                    .then(swallowTap)
                    .padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左：播放 / 暂停
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }

                // 左：当前进度
                Text(
                    text = formatPosition(positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xE6FFFFFF),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.width(50.dp),
                )

                // 中：进度条
                PlayerSeekBar(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp),
                    fraction = if (scrubbing) scrubFraction else {
                        if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                    },
                    scrubbing = scrubbing,
                    onScrub = onScrub,
                    onScrubFinished = onScrubFinished,
                )

                // 右：总时长
                Text(
                    text = formatPosition(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0x99FFFFFF),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.width(56.dp),
                )

                // 右：全屏切换
                IconHit(
                    onClick = onFullscreen,
                    icon = if (isLandscape) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    description = "全屏",
                    size = 38.dp,
                    iconSize = 22.dp,
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
    modifier: Modifier = Modifier,
    fraction: Float,
    scrubbing: Boolean,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
) {
    val trackHeight = if (scrubbing) 5.dp else 3.dp
    val thumbSize = if (scrubbing) 14.dp else 9.dp
    val safeFraction = fraction.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .height(28.dp)
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
    iconSize: androidx.compose.ui.unit.Dp = size * 0.45f,
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
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun OverlayButton(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x33FFFFFF))
            .border(1.dp, Color(0x4DFFFFFF), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 把 ExoPlayer 的错误码翻成人话，方便判断是源的问题还是地址过期。 */
/**
 * 播放错误的人类可读说明。
 *
 * 采集站最常挂的方式是「整个域名不再解析」（关站 / 换域名 / 被墙），
 * 这时报「网络连接失败」会让人以为是 App 的问题，所以单独识别 DNS 失败。
 */
private fun describePlaybackError(error: PlaybackException): String {
    if (error.causedByUnknownHost()) {
        return "源站域名已失效（可能已关站或换了新域名），请换源或到设置里更新源地址"
    }
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "网络连接失败，源站可能已失效"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "源站拒绝访问或地址已过期（防盗链 / 404）"
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "播放地址不存在或已过期"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
            "播放地址格式不支持（可能是网页而不是视频流）"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED ->
            "设备解码失败，换一个源或换一集试试"
        else -> error.errorCodeName
    }
}

private fun Throwable.causedByUnknownHost(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth++ < 10) {
        if (current is UnknownHostException) return true
        current = current.cause
    }
    return false
}

private const val HTTP_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/122.0 Mobile Safari/537.36"

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
