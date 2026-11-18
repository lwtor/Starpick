package com.starpick.app.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Matrix
import android.view.ViewConfiguration
import android.view.TextureView
import android.view.View
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
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
        // 用容器的带缓存数据源：上游仍是「浏览器 UA + 允许跨协议重定向」的 HttpDataSource
        // （采集站 CDN 普遍按 UA 拦请求、且经常 302 跳到 http），前面多一层磁盘缓存，
        // 同一集重复播放 / 拖回看过的位置不再重新下载。
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(container.cachedDataSourceFactory))
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
    // 长按倍速：微信视频号式——按住进入 2x，右滑 3x、左滑 1.5x，松手恢复。
    // speedBeforeHold 记住按下前的常速，松手还原；holdSpeed 为 null 表示当前未长按。
    var speedBeforeHold by remember { mutableFloatStateOf(1f) }
    var holdSpeed by remember { mutableStateOf<Float?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var showSheet by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    // 已经预载（含磁盘缓存命中）到的位置，非 HLS 时退回用它画缓存段。
    var bufferedMs by remember(player) { mutableLongStateOf(0L) }
    // HLS：磁盘上真正落盘的分片区间（按比例），进度条逐段画，不再只画播放头前面那一截。
    var cachedRanges by remember(player) { mutableStateOf<List<CachedRange>>(emptyList()) }
    // 横向滑动快进 / 快退：拖动中只更新预览，松手才真正 seek，避免一路拖动触发几十次跳转。
    var swipeTargetMs by remember { mutableStateOf<Long?>(null) }
    var swipeDeltaMs by remember { mutableLongStateOf(0L) }
    // 提示的延迟消失用批次号控制：新的一次滑动能作废上一次的定时器。
    var swipeHideToken by remember { mutableIntStateOf(0) }
    // 单击 / 双击判别：记录上一次短按抬手时间，300ms 内的第二次算双击。
    var lastTapAt by remember { mutableLongStateOf(0L) }
    val gestureScope = rememberCoroutineScope()
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
            val saved = container.store.progressFor(item.historyKey)
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
        // 预加载是「持续补货」：每轮都按当前位置重算前后 5 分钟窗口里的空缺分片，
        // 与当前分片是否已缓存无关（缓存命中时照样要把周围的空缺补上）。
        var prefetchJob: Job? = null
        var lastSegment = -1
        // 只记录「正在下载」和「刚失败」的分片，不是永久黑名单：
        //  - inflight 防止同一分片被并发起多次请求；
        //  - 失败的过 30 秒冷却后可重试，之前没下完的、新进入窗口的都能被补上。
        val inflight = ConcurrentHashMap.newKeySet<String>()
        val failedAt = ConcurrentHashMap<String, Long>()
        while (isActive) {
            delay(500)
            val snapshot = withContext(Dispatchers.Main.immediate) {
                PlayerSnapshot(
                    position = player.currentPosition.coerceAtLeast(0L),
                    duration = player.duration.takeIf { it > 0 } ?: 0L,
                    playing = player.isPlaying,
                    buffered = player.bufferedPosition.coerceAtLeast(0L),
                    manifest = player.currentManifest,
                )
            }
            if (!scrubbing) positionMs = snapshot.position
            durationMs = snapshot.duration
            isPlaying = snapshot.playing
            bufferedMs = snapshot.buffered
            val hls = snapshot.manifest as? HlsManifest
            cachedRanges = hls?.let { hlsCachedRanges(it, container, snapshot.duration) }
                ?: emptyList()
            // 提前把当前位置前后各 5 分钟的空缺分片下进磁盘缓存。
            // 每轮按真实缓存状态重算，当前分片在不在缓存里都不影响周围缺口的补齐。
            if (hls != null && !scrubbing) {
                val index = currentSegmentIndex(hls, snapshot.position)
                if (index >= 0) {
                    if (lastSegment >= 0 && abs(index - lastSegment) > 3) {
                        // 跳播：取消旧批次；它 in-flight 的分片标记为取消，可立即重试。
                        prefetchJob?.cancel()
                        prefetchJob = null
                        inflight.clear()
                    }
                    lastSegment = index
                    if (prefetchJob?.isActive != true) {
                        val pending = pendingPrefetchUrls(
                            hls, index, container, inflight, failedAt,
                        )
                        if (pending.isNotEmpty()) {
                            prefetchJob = launch(Dispatchers.IO) {
                                for (url in pending) {
                                    currentCoroutineContext().ensureActive()
                                    inflight += url
                                    val ok = container.prefetchSegment(url)
                                    inflight -= url
                                    if (!ok) failedAt[url] = System.currentTimeMillis()
                                }
                            }
                        }
                    }
                }
            }
            lastPosition = snapshot.position
            lastDuration = snapshot.duration
            if (++tick % 10 == 0) {
                container.store.saveProgress(
                    WatchProgress(
                        key = item.historyKey,
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
                key = item.historyKey,
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

    LaunchedEffect(playbackSpeed, holdSpeed) {
        player.setPlaybackSpeed(holdSpeed ?: playbackSpeed)
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

        // 松手才 seek，并让提示多留 0.7 秒再消失（立刻消失会像闪了一下）。
        val commitSwipeSeek: () -> Unit = {
            val target = swipeTargetMs
            if (target != null) {
                player.seekTo(target)
                positionMs = target
                val token = ++swipeHideToken
                gestureScope.launch {
                    delay(700)
                    if (swipeHideToken == token) swipeTargetMs = null
                }
            }
        }

        // 整块区域的手势，一个状态机统一裁决，避免多个 detector 互相抢事件：
        //  - 按下先不动、超过阈值 → 长按倍速（微信视频号式）：默认 2x，右滑 3x / 左滑 1.5x，松手还原；
        //  - 按下立刻横滑 → 快进 / 快退预览，松手 seek；
        //  - 短按抬手 → 单击切控制条、双击播放暂停。
        // 纵向手势不处理，留给音量 / 亮度用。
        val holdTimeoutMs = 320L
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(player) {
                    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
                    // 长按倍速换挡阈值：按屏宽取 12%，滑动足够长才切 3x / 1.5x，避免误触。
                    val speedSlop = size.width * 0.12f
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downPos = down.position
                        val pressTime = System.currentTimeMillis()
                        var mode = 0 // 0=未决 1=拖动seek 2=长按倍速 3=纵向忽略
                        var anchorMs = 0L
                        var travelled = 0f
                        var spanMs = swipeSpanMs(0L)
                        var lastX = downPos.x

                        while (true) {
                            // 关键：手指按下后如果完全静止，系统不会发出新的移动事件，
                            // 原来「收到事件再比较时间」的写法会让长按判定永远等不到，
                            // 直到抬手才被当成短按（所以松开反而弹出控制条）。
                            // 这里给「等待事件」本身加超时，且按按下时刻算剩余时间——
                            // 中途的微小抖动不会重置长按计时。
                            val remaining = holdTimeoutMs - (System.currentTimeMillis() - pressTime)
                            val event = if (mode != 0) {
                                awaitPointerEvent(PointerEventPass.Main)
                            } else if (remaining <= 0) {
                                null
                            } else {
                                withTimeoutOrNull(remaining) {
                                    awaitPointerEvent(PointerEventPass.Main)
                                }
                            }

                            if (event == null) {
                                // 按住到时仍未裁决 → 长按倍速
                                mode = 2
                                speedBeforeHold = playbackSpeed
                                holdSpeed = 2f
                                swipeTargetMs = null
                                swipeHideToken++
                                continue
                            }

                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) {
                                // 抬手 / 取消：只有「从未裁决（mode==0）」才算点击。
                                // 一旦进了长按倍速 / seek / 纵向忽略，抬手绝不再触发点击逻辑，
                                // 否则长按松手会误弹出控制面板。
                                if (mode == 0) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastTapAt < 300L) {
                                        togglePlay()
                                        lastTapAt = 0L
                                    } else {
                                        lastTapAt = now
                                        if (showControls) showControls = false else nudgeControls()
                                    }
                                } else {
                                    if (mode == 1) commitSwipeSeek()
                                    holdSpeed = null
                                    swipeTargetMs = null
                                    // 非点击手势不参与双击判定，清掉计时避免影响下一次点击。
                                    lastTapAt = 0L
                                }
                                break
                            }

                            val dx = change.position.x - downPos.x
                            val dy = change.position.y - downPos.y

                            if (mode == 0) {
                                when {
                                    kotlin.math.abs(dx) > touchSlop &&
                                        kotlin.math.abs(dx) > kotlin.math.abs(dy) -> {
                                        // 先横移：判为 seek
                                        mode = 1
                                        change.consume()
                                        swipeHideToken++
                                        anchorMs = player.currentPosition.coerceAtLeast(0L)
                                        travelled = 0f
                                        lastX = change.position.x
                                        val total = player.duration
                                        spanMs = if (total > 0) swipeSpanMs(total) else swipeSpanMs(0L)
                                    }
                                    kotlin.math.abs(dy) > touchSlop -> {
                                        // 纵向移动：不处理，交出去（退化为无操作）
                                        mode = 3
                                    }
                                    // 仍在 touchSlop 内：保持未决，继续等超时或抬手
                                }
                            }

                            if (mode == 1) {
                                change.consume()
                                travelled += change.position.x - lastX
                                lastX = change.position.x
                                val total = player.duration
                                if (total > 0) {
                                    val delta = (travelled / size.width * spanMs).toLong()
                                    val target = (anchorMs + delta).coerceIn(0L, total)
                                    swipeDeltaMs = target - anchorMs
                                    swipeTargetMs = target
                                }
                            } else if (mode == 2) {
                                change.consume()
                                // 以按下点为基准：右滑 3x，左滑 1.5x，中间 2x。
                                // 阈值用屏宽占比，避免手指轻微偏移就换挡。
                                holdSpeed = when {
                                    dx > speedSlop -> 3f
                                    dx < -speedSlop -> 1.5f
                                    else -> 2f
                                }
                            }
                        }
                    }
                },
        )

        // 滑动反馈：小窗显示目标帧 + 「±多久 / 将跳到」；松手后自动消失。
        swipeTargetMs?.takeIf { durationMs > 0 }?.let { target ->
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SwipeFramePreview(
                    container = container,
                    url = ready.url,
                    mimeType = ready.mimeType,
                    targetMs = target,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .width(if (isLandscape) 200.dp else 148.dp)
                        .aspectRatio(16f / 9f)
                        .shadow(8.dp, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black)
                        .border(2.dp, Color(0xA6FFFFFF), RoundedCornerShape(10.dp)),
                )
                SwipeSeekHint(
                    deltaMs = swipeDeltaMs,
                    targetMs = target,
                    durationMs = durationMs,
                )
            }
        }

        // 长按倍速提示：贴右上角、落在顶栏之下，避开画面中央和控制条，松手即消失。
        holdSpeed?.let { speed ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .displayCutoutPadding()
                    .padding(end = 16.dp, top = 76.dp)
                    .background(Color(0xC4000000), RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    text = "${formatSpeed(speed)}x",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
        }

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
            bufferedMs = bufferedMs,
            cachedRanges = cachedRanges,
            isLandscape = isLandscape,
            visible = showControls,
            onBack = { if (isLandscape) exitFullscreen() else onExit() },
            onTogglePlay = togglePlay,
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
    bufferedMs: Long,
    cachedRanges: List<CachedRange>,
    isLandscape: Boolean,
    scrubbing: Boolean,
    scrubFraction: Float,
    visible: Boolean,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onEpisodes: () -> Unit,
    onFullscreen: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
) {
    val duration = 240
    val slide = tween<IntOffset>(durationMillis = duration)
    val fade = tween<Float>(durationMillis = duration)
    // 控制条上的文字比正文小一档原本是为让位给画面，但 10sp 在播放器里读不清，
    // 统一放大到 13sp；进度条本身尺寸不动，只把两侧的字和按钮撑大。
    val timeStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
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
                        fontSize = 15.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (episodeName.isNotBlank()) {
                        Text(
                            text = episodeName,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xB3FFFFFF),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 选集只在全屏时出现：竖屏下方本来就有完整选集列表，这里再放一个重复。
                if (isLandscape) TextAction("选集", onClick = onEpisodes)
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
                    .padding(start = 2.dp, end = 2.dp, top = 12.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左：播放 / 暂停
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }

                // 左：当前进度
                Text(
                    text = formatPosition(positionMs),
                    style = timeStyle,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.width(58.dp),
                )

                // 中：进度条（尺寸不变，只让两侧的字和按钮变大）
                PlayerSeekBar(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp),
                    fraction = if (scrubbing) scrubFraction else {
                        if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                    },
                    // HLS 按磁盘上的分片逐段画；非 HLS 退回播放器的预载位置。
                    cachedRanges = cachedRanges.ifEmpty {
                        if (bufferedMs > 0 && durationMs > 0) {
                            listOf(CachedRange(0f, (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)))
                        } else emptyList()
                    },
                    scrubbing = scrubbing,
                    onScrub = onScrub,
                    onScrubFinished = onScrubFinished,
                )

                // 右：总时长
                Text(
                    text = formatPosition(durationMs),
                    style = timeStyle,
                    color = Color(0xB3FFFFFF),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.width(66.dp),
                )

                // 右：全屏切换
                IconHit(
                    onClick = onFullscreen,
                    icon = if (isLandscape) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    description = "全屏",
                    size = 46.dp,
                    iconSize = 26.dp,
                )
            }
        }
    }
}

/**
 * 播放进度条：3dp 细轨 + 9dp 小圆点，拖动时加粗放大。
 *
 * 三段配色（从暗到亮）：未缓存底色 → 已缓存分片（白 55%）→ 已播段（金色）。
 * 缓存段不是「播放头前面一截」，而是磁盘上每一段分片各自的位置。
 * 手势自己接：横向拖动改进度，点一下轨道直接跳。
 */
@Composable
private fun PlayerSeekBar(
    modifier: Modifier = Modifier,
    fraction: Float,
    cachedRanges: List<CachedRange>,
    scrubbing: Boolean,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
) {
    val trackHeight = if (scrubbing) 5.dp else 3.dp
    val thumbSize = if (scrubbing) 14.dp else 9.dp
    val safeFraction = fraction.coerceIn(0f, 1f)
    // 已缓存段独立于播放点：拖动到未缓存区域时缓存段会短于金色段，直接画在金色下面即可，
    // 不要把它拉到播放点以后，否则「没缓存的地方」会被涂成已缓存，反而误导。
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
        // 底：未缓存 + 已落盘的分片。HLS 一段视频对应许多分片，逐段画才能如实反映
        // 「看过 / 拖到过 / 跳过」的位置；连续分片在 hlsCachedRanges 里已合并。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .drawBehind {
                    drawRect(color = Color(0x33FFFFFF), size = size)
                    cachedRanges.forEach { range ->
                        drawRect(
                            color = Color(0x8CFFFFFF),
                            topLeft = Offset(range.start * size.width, 0f),
                            size = Size(
                                ((range.end - range.start) * size.width).coerceAtLeast(0f),
                                size.height,
                            ),
                        )
                    }
                },
        )
        // 上：已播放
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

/**
 * 滑动时的小窗取帧预览。
 *
 * 主流应用（B 站 / YouTube / 腾讯视频）的进度条预览靠**服务端雪碧图**：
 * 上传时把视频按固定间隔抽帧拼成一张大图，客户端按时间换算格子去裁。
 * CMS 采集源只给播放地址，没有这种资源；`MediaMetadataRetriever` 又不认 m3u8，
 * 所以设备上唯一通用的做法是再开一个播放器 seek 到目标位置、只渲染一帧。
 *
 * 它和主播放器共用磁盘缓存，往回拖基本命中本地分片（快）；往前拖还没缓存时
 * 会真的去拉数据，慢一点但能出画面。找不到画面就显示黑底，不影响主画面播放。
 */
@Composable
private fun SwipeFramePreview(
    container: AppContainer,
    url: String,
    mimeType: String?,
    targetMs: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 取帧播放器按地址缓存：同一集内反复滑动复用同一个实例，换集才重建。
    val player = remember(url) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(container.cachedDataSourceFactory))
            .build()
            .apply {
                volume = 0f
                playWhenReady = false
                // 只要画面，关掉音轨能省一个解码器。
                setTrackSelectionParameters(
                    trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build(),
                )
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        .apply { if (mimeType != null) setMimeType(mimeType) }
                        .build(),
                )
                prepare()
            }
    }
    var broken by remember(player) { mutableStateOf(false) }
    // 视频原始尺寸：用来算「铺满裁剪」的变换矩阵。
    var videoWidth by remember(player) { mutableIntStateOf(player.videoSize.width) }
    var videoHeight by remember(player) { mutableIntStateOf(player.videoSize.height) }

    // 目标位置在拖动时每秒变几十次，但每次 seek 都要 flush 解码器：
    // 固定 200ms 取一次「最新值」，既跟得上又不会把解码器冲垮。
    val latestTarget = rememberUpdatedState(targetMs)
    LaunchedEffect(player) {
        var lastSeeked = -1L
        while (isActive) {
            delay(200)
            val target = latestTarget.value
            if (target != lastSeeked) {
                lastSeeked = target
                player.seekTo(target)
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                broken = true
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoWidth = (videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt()
                videoHeight = videoSize.height
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    if (broken) return

    // 不用 PlayerView：media3 1.11.1 已经没有 setSurfaceType，默认的 SurfaceView
    // 既不吃 Compose 的圆角裁剪（四角会露出直角画面），也不会做我们想要的铺满裁剪。
    // 换成 TextureView + 自己算矩阵，圆角和缩放都完全可控。
    val textureView = remember(context) { TextureView(context) }

    DisposableEffect(player, textureView) {
        player.setVideoTextureView(textureView)
        onDispose { player.clearVideoTextureView(textureView) }
    }

    // post 里执行，保证 layout 完成、能拿到 view 的真实宽高。
    // 另外挂 layout 监听：视频尺寸先到、view 后布局的情况（首次显示常见）也要补一次。
    val latestVideoSize = rememberUpdatedState(videoWidth to videoHeight)
    DisposableEffect(textureView) {
        val layoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val (w, h) = latestVideoSize.value
            (v as TextureView).applyCoverCrop(w, h)
        }
        textureView.addOnLayoutChangeListener(layoutListener)
        onDispose { textureView.removeOnLayoutChangeListener(layoutListener) }
    }
    LaunchedEffect(textureView, videoWidth, videoHeight) {
        textureView.post { textureView.applyCoverCrop(videoWidth, videoHeight) }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { textureView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 滑动快进 / 快退时的浮层：上面是「±多久」，下面是「跳到哪个时间 / 总时长」。
 *
 * 做成半透明黑底小卡片，压在画面中央；不挡住控制条，也不跟随手指移动
 * （跟手的小图标在视频上很难看清，静止的读数更有用）。
 */
@Composable
private fun SwipeSeekHint(
    deltaMs: Long,
    targetMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val ahead = deltaMs >= 0
    val magnitude = if (ahead) deltaMs else -deltaMs
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xB3000000))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = (if (ahead) "+" else "−") + formatPosition(magnitude),
            style = MaterialTheme.typography.titleMedium,
            color = Accent.Gold,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${formatPosition(targetMs)} / ${formatPosition(durationMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xB3FFFFFF),
        )
    }
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun IconHit(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    iconSize: androidx.compose.ui.unit.Dp = size * 0.5f,
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

/**
 * 横向滑动整屏宽度对应的跳转时长，按片长分三档。
 *
 * 不做连续比例（`时长 × 5%`）是因为 45 分钟的剧集只有 2 分钟、还是太磨人；
 * 分档后短片仍然细，剧集和电影一划就能走掉一大段，符合「粗调靠划、精调靠进度条」的习惯。
 */
private fun swipeSpanMs(durationMs: Long): Float = when {
    // 短视频（短剧、切片、MV）：一屏 1 分钟，够细。
    durationMs <= SHORT_FORM_MAX_MS -> 60_000f
    // 中长视频（常规剧集、综艺）：一屏 6 分钟。
    durationMs <= MID_FORM_MAX_MS -> 360_000f
    // 长视频（电影、长纪录片）：一屏 12 分钟。
    else -> 720_000f
}

/** 20 分钟以内算短视频。 */
private const val SHORT_FORM_MAX_MS = 20 * 60_000L

/** 70 分钟以内算中长视频，超过就是电影级。 */
private const val MID_FORM_MAX_MS = 70 * 60_000L

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()

/**
 * 铺满裁剪（centerCrop）。
 *
 * ExoPlayer 往 TextureView 上是「拉伸填满」，所以先按视频原始宽高比把画面还原，
 * 再等比放大到同时盖住 view 的宽和高；盖不住的那一侧换成越界，由 view 边界裁掉。
 *
 * 因为要抵消一次拉伸，两轴缩放系数一般不同（video 是 4:3、窗口是 16:9 时，
 * 纵向放大 1.33 倍、横向不变），这不是各向异性拉伸，而是还原成正确的等比画面。
 */
private fun TextureView.applyCoverCrop(videoWidth: Int, videoHeight: Int) {
    if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) return
    val viewW = width.toFloat()
    val viewH = height.toFloat()
    val cover = maxOf(viewW / videoWidth, viewH / videoHeight)
    setTransform(
        Matrix().apply {
            setScale(
                videoWidth * cover / viewW,
                videoHeight * cover / viewH,
                viewW / 2f,
                viewH / 2f,
            )
        },
    )
}

/** 一次轮询拿到的播放器状态快照（必须整体在同一帧主线程读取）。 */
private data class PlayerSnapshot(
    val position: Long,
    val duration: Long,
    val playing: Boolean,
    val buffered: Long,
    val manifest: Any?,
)

/** 进度条上的一段已落盘区间，0f~1f 相对整集时长。 */
private data class CachedRange(val start: Float, val end: Float)

/**
 * 当前播放位置落在播放列表的第几个分片上。
 * 以首片的 relativeStartTimeUs 为原点，兼容源站只返回一段滑动窗口的列表。
 */
private fun currentSegmentIndex(manifest: HlsManifest, positionMs: Long): Int {
    val segments = manifest.mediaPlaylist.segments
    if (segments.isEmpty() || positionMs < 0) return -1
    val atUs = segments.first().relativeStartTimeUs + positionMs * 1000L
    for (i in segments.indices) {
        val segment = segments[i]
        if (atUs >= segment.relativeStartTimeUs &&
            atUs < segment.relativeStartTimeUs + segment.durationUs
        ) return i
    }
    // 落在列表之外（首片之前 / 尾部之后）时贴住最近的一端。
    return if (atUs < segments.first().relativeStartTimeUs) 0 else segments.lastIndex
}

/** 预加载覆盖当前分片前后各 5 分钟。采集站一片通常 5~10 秒，按片数算根本不够看。 */
private const val PREFETCH_WINDOW_MS = 5L * 60L * 1000L

/** 下载失败的分片过多久允许重试，避免坏分片每次轮询都被重新请求。 */
private const val PREFETCH_RETRY_MS = 30_000L

/**
 * 列出当前位置前后 5 分钟内、磁盘上还没有的空缺分片。
 *
 * 判断依据只有「真实缓存状态」，不看当前分片是否已命中缓存——否则从缓存里
 * 续播时，周围没下到的缺口会被整段忽略。先排即将播放的（更重要），再排回看方向。
 * 正在下载的跳过；刚失败的在冷却期内跳过，过后自动重试。
 */
private fun pendingPrefetchUrls(
    manifest: HlsManifest,
    index: Int,
    container: AppContainer,
    inflight: Set<String>,
    failedAt: Map<String, Long>,
): List<String> {
    val playlist = manifest.mediaPlaylist
    val segments = playlist.segments
    if (index !in segments.indices) return emptyList()
    val current = segments[index]
    val fromUs = current.relativeStartTimeUs - PREFETCH_WINDOW_MS * 1000L
    val toUs = current.relativeStartTimeUs + current.durationUs + PREFETCH_WINDOW_MS * 1000L
    val now = System.currentTimeMillis()

    val pending = mutableListOf<String>()
    fun collect(range: IntProgression) {
        for (i in range) {
            if (i !in segments.indices) continue
            val segment = segments[i]
            val start = segment.relativeStartTimeUs
            if (segment.url.isBlank() || start + segment.durationUs <= fromUs || start >= toUs) {
                continue
            }
            val url = runCatching { java.net.URI(playlist.baseUri).resolve(segment.url).toString() }
                .getOrDefault(segment.url)
            if (url in inflight) continue
            val lastFail = failedAt[url]
            if (lastFail != null && now - lastFail < PREFETCH_RETRY_MS) continue
            if (container.mediaCache.getCachedSpans(container.cacheKeyForUrl(url)).isNotEmpty()) {
                continue
            }
            pending += url
        }
    }
    collect((index + 1) until segments.size)
    collect((index - 1) downTo 0)
    return pending
}

/**
 * 把 HLS 媒体播放列表里的分片映射到时间轴，只留下磁盘上真有数据的分片。
 *
 * `bufferedPosition` 只能表示「播放头前方内存里预读了多少」，播过的和拖过的地方都看不到；
 * 这里改问 `SimpleCache`：每个分片 URL 的 key 有没有 span。命中的分片按自己的
 * `relativeStartTimeUs / durationUs` 画成一小段，于是看过、跳过、拖过的片段都能留在进度条上。
 * 连续分片合并成一段，避免几百个 Rect 把绘制拖慢。
 */
private fun hlsCachedRanges(
    manifest: HlsManifest,
    container: AppContainer,
    durationMs: Long,
): List<CachedRange> {
    val playlist: HlsMediaPlaylist = manifest.mediaPlaylist
    val segments = playlist.segments
    if (durationMs <= 0 || segments.isEmpty()) return emptyList()

    val originUs = segments.first().relativeStartTimeUs
    val ranges = mutableListOf<CachedRange>()
    for (segment in segments) {
        if (segment.url.isBlank() || segment.durationUs <= 0) continue
        val url = runCatching { java.net.URI(playlist.baseUri).resolve(segment.url).toString() }
            .getOrDefault(segment.url)
        if (container.mediaCache.getCachedSpans(container.cacheKeyForUrl(url)).isEmpty()) continue
        val startMs = (segment.relativeStartTimeUs - originUs).coerceAtLeast(0L) / 1000L
        val endMs = (segment.relativeStartTimeUs - originUs + segment.durationUs)
            .coerceAtLeast(0L) / 1000L
        if (endMs <= 0L || startMs >= durationMs) continue
        ranges += CachedRange(
            start = (startMs.toFloat() / durationMs).coerceIn(0f, 1f),
            end = (endMs.toFloat() / durationMs).coerceIn(0f, 1f),
        )
    }

    val merged = mutableListOf<CachedRange>()
    ranges.sortedBy { it.start }.forEach { range ->
        val last = merged.lastOrNull()
        if (last == null || range.start > last.end + 0.001f) {
            merged += range
        } else {
            merged[merged.lastIndex] = last.copy(end = maxOf(last.end, range.end))
        }
    }
    return merged
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
