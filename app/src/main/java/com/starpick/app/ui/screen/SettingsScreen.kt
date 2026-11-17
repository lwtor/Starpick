package com.starpick.app.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starpick.app.AppContainer
import com.starpick.app.BuildConfig
import com.starpick.app.UpdateUiState
import com.starpick.app.core.api.SourceHealth
import com.starpick.app.core.model.SourceSite
import com.starpick.app.ui.component.GhostButton
import com.starpick.app.ui.component.PrimaryButton
import com.starpick.app.ui.theme.Accent
import com.starpick.app.ui.theme.Ink
import dev.veneranative.updater.InstallResult
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置：内容源 / 播放与存储 / 关于。
 *
 * 改版前所有东西塞在一条长列表里：采集源全挤在同一张卡、每个源三行小字和开关并列，
 * 「关于」还兼收版本更新、版本号、仓库、免责声明 —— 读起来是「一团」而不是「三类事」。
 *
 * 现在的结构原则：
 * 1. **一个分区只讲一件事**，分区标题右侧给出该类内容的汇总（几个源在跑 / 缓存占用）；
 * 2. **采集源一个一卡** —— 它是唯一需要逐个体检的对象，合并成行会让状态互相干扰；
 * 3. **读优先于点**：状态用彩色胶囊，操作按钮降到右侧弱色，删除不再常驻红字；
 * 4. 与 Play 无关的信息（仓库、免责）不进主流程，收到最底的「关于」。
 */
@Composable
fun SettingsScreen(container: AppContainer) {
    val sources by container.store.sources.collectAsState()
    val scope = rememberCoroutineScope()
    var showAddSheet by remember { mutableStateOf(false) }
    val enabledCount = sources.count { it.enabled }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
    ) {
        item { ScreenHeader() }

        item {
            SectionHeader(
                title = "内容源",
                trailing = if (sources.isEmpty()) null else "$enabledCount / ${sources.size} 已启用",
                topPadding = 24.dp,
            )
        }

        if (sources.isEmpty()) {
            item { EmptySourceCard(onAdd = { showAddSheet = true }) }
        } else {
            items(sources, key = { it.id }) { site ->
                Column {
                    SourceCard(
                        site = site,
                        onToggle = { enabled ->
                            scope.launch { container.store.setEnabled(site.id, enabled) }
                        },
                        onPing = { onResult ->
                            scope.launch {
                                val health = withContext(Dispatchers.IO) {
                                    runCatching { container.api.probe(site) }
                                        .getOrDefault(SourceHealth.UNKNOWN)
                                }
                                onResult(health)
                            }
                        },
                        onRemove = { scope.launch { container.store.remove(site.id) } },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            item { AddSourceButton(onClick = { showAddSheet = true }) }
        }

        item { SectionHeader(title = "播放与存储", topPadding = 26.dp) }
        item { CacheCard(container) }

        item { SectionHeader(title = "关于", topPadding = 26.dp) }
        item { UpdatePanel(container) }
        item {
            Spacer(Modifier.height(8.dp))
            AboutPanel()
        }
    }

    if (showAddSheet) {
        AddSourceSheet(container = container, onDismiss = { showAddSheet = false })
    }
}

// ---------------------------------------------------------------- 页头与分区

@Composable
private fun ScreenHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineSmall,
                color = Ink.Text1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "配置只保存在本机，不上传任何服务器",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.Text3,
            )
        }
        Spacer(Modifier.width(12.dp))
        // 版本放页头：用户找不到版本时第一眼就在这，不用往下翻。
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Ink.Card)
                .border(1.dp, Ink.Line, RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.Text2,
            )
        }
    }
}

/** 分区标题：比正文重、比页头轻，一眼能切出「这是第几类」。 */
@Composable
private fun SectionHeader(title: String, trailing: String? = null, topPadding: Dp = 20.dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.3.sp),
            color = Ink.Text2,
            fontWeight = FontWeight.SemiBold,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = Ink.Text3,
            )
        }
    }
}

/** 统一卡片外观：16dp 圆角 + 极淡描边，靠明度分层而不是阴影。 */
private fun Modifier.settingsCard(): Modifier = this
    .fillMaxWidth()
    .clip(RoundedCornerShape(16.dp))
    .background(Ink.Card)
    .border(1.dp, Ink.Line, RoundedCornerShape(16.dp))

// ---------------------------------------------------------------- 内容源

@Composable
private fun EmptySourceCard(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .settingsCard()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "还没有内容源",
            style = MaterialTheme.typography.titleSmall,
            color = Ink.Text1,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "搜索和首页都要靠采集站取数据\n填一个苹果 CMS V10 站点地址就能开始",
            style = MaterialTheme.typography.labelSmall.copy(lineHeight = 18.sp),
            color = Ink.Text3,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            text = "添加采集源",
            icon = Icons.Filled.Add,
            onClick = onAdd,
        )
    }
}

/**
 * 单个采集源一张卡。
 *
 * 三层信息，从上到下逐级减弱：**它是谁**（名称 / 域名）、**它能不能用**（状态胶囊）、
 * **要不要它参与**（开关）。删除退到最右侧的灰色图标，避免和状态抢视线。
 */
@Composable
private fun SourceCard(
    site: SourceSite,
    onToggle: (Boolean) -> Unit,
    onPing: ((SourceHealth) -> Unit) -> Unit,
    onRemove: () -> Unit,
) {
    var health by remember(site.id) { mutableStateOf<SourceHealth?>(null) }
    var pinging by remember(site.id) { mutableStateOf(false) }

    val ping: () -> Unit = {
        if (!pinging) {
            pinging = true
            onPing { result ->
                health = result
                pinging = false
            }
        }
    }

    // 进页面自动测一次：满屏「测试」按钮只会让人更糊涂，先给结论再让人重测。
    LaunchedEffect(site.id) {
        if (health == null && !pinging) ping()
    }

    Column(
        modifier = Modifier
            .settingsCard()
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = site.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                    color = if (site.enabled) Ink.Text1 else Ink.Text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = site.baseUrl
                        .removePrefix("https://")
                        .removePrefix("http://")
                        .trimEnd('/'),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = Ink.Text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = site.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.scale(0.88f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Accent.Brand,
                    uncheckedThumbColor = Ink.Text2,
                    uncheckedTrackColor = Ink.Selected,
                    uncheckedBorderColor = Ink.LineStrong,
                ),
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            val (label, color) = when {
                pinging -> "测试中" to Ink.Text3
                health == SourceHealth.ONLINE -> "可用" to Accent.Online
                health == SourceHealth.OFFLINE -> "不可用" to Accent.New
                health == SourceHealth.UNKNOWN -> "超时未测准" to Accent.Gold
                else -> "未测试" to Ink.Text3
            }
            // 胶囊只负责「显示结论」，重测统一交给右边的文字按钮，避免同一件事两个入口。
            StatusPill(text = label, color = color)
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (pinging) "…" else "重测",
                style = MaterialTheme.typography.labelMedium,
                color = if (pinging) Ink.Text3 else Accent.Brand,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !pinging, onClick = ping)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = "删除该源",
                    tint = Ink.Text3,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 空心虚线按钮：和实心卡片区分开，天然读作「新建一项」。 */
@Composable
private fun AddSourceButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .drawBehind {
                drawRoundRect(
                    color = Ink.LineStrong,
                    cornerRadius = CornerRadius(14.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)),
                    ),
                )
            }
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = Accent.Brand,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = "添加采集源",
            style = MaterialTheme.typography.labelLarge,
            color = Accent.Brand,
        )
    }
}

// ---------------------------------------------------------------- 播放与存储

/**
 * 缓存卡：数字 + 占用条 + 一个动作。
 *
 * 缓存是「边看边写」的副产物，用户看不见它从哪来，所以必须一次说清三件事：
 * 占了多少、最多能占多少、怎么清掉。占用条让「快满了」这件事不用读数字就能感知。
 */
@Composable
private fun CacheCard(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var used by remember { mutableLongStateOf(0L) }
    var clearing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        used = withContext(Dispatchers.IO) { container.mediaCacheBytes }
    }

    val limit = container.mediaCacheLimitBytes
    val fraction = if (limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 0f
    val percentText = when {
        used <= 0 -> "0%"
        fraction < 0.01f -> "<1%"
        else -> "${(fraction * 100).toInt()}%"
    }

    Column(modifier = Modifier.settingsCard().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Storage,
                contentDescription = null,
                tint = Ink.Text2,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "边看边缓存",
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                    color = Ink.Text1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = notice ?: "重看已缓存的集数不再走网络",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = if (notice != null) Accent.Gold else Ink.Text3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (clearing) {
                CircularProgressIndicator(
                    color = Accent.Gold,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                GhostButton(
                    text = "清除",
                    modifier = Modifier.height(32.dp),
                    enabled = used > 0,
                    onClick = {
                        scope.launch {
                            clearing = true
                            notice = null
                            val freed = container.clearMediaCache()
                            used = withContext(Dispatchers.IO) { container.mediaCacheBytes }
                            clearing = false
                            notice = if (freed > 0) {
                                "已清除 ${formatCacheSize(freed)}，之后播放会重新累积"
                            } else {
                                "缓存本来就是空的"
                            }
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Ink.Line),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Accent.Gold),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = percentText,
                style = MaterialTheme.typography.labelMedium,
                color = Ink.Text2,
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth()) {
            Text(
                text = "已用 ${formatCacheSize(used)}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = Ink.Text3,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "上限 ${formatCacheSize(limit)}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = Ink.Text3,
            )
        }
    }
}

// ---------------------------------------------------------------- 版本更新

/**
 * 版本更新：检查 → 下载（带进度条）→ 安装，全部内联，不弹窗。
 *
 * 状态聚合成一个胶囊放在标题右侧，动作按钮永远在最右；下载中额外给一条
 * 进度条和「取消」。这样无论处在哪个状态，视线落点都固定在同一处。
 */
@Composable
private fun UpdatePanel(container: AppContainer) {
    val state by container.update.collectAsState()
    var actionError by remember { mutableStateOf<String?>(null) }

    val openInBrowser: () -> Unit = {
        actionError = if (container.openDownloadInBrowser()) {
            null
        } else {
            "没有找到可用的浏览器，无法外部下载"
        }
    }

    val downloading = state as? UpdateUiState.Downloading
    val percent = downloading?.fraction?.let { (it * 100).toInt() }

    // 状态胶囊：只放结论，不重复按钮上的字。
    val (badgeText, badgeColor) = when (val s = state) {
        UpdateUiState.Idle -> null to Ink.Text3
        UpdateUiState.Checking -> "检查中" to Ink.Text3
        is UpdateUiState.UpToDate -> "已是最新" to Accent.Online
        is UpdateUiState.Available -> "新版本 ${s.tag}" to Accent.Gold
        is UpdateUiState.Downloading -> "${percent ?: 0}%" to Accent.Brand
        is UpdateUiState.Ready -> "待安装" to Accent.Gold
        is UpdateUiState.Failed -> "失败" to WarnColor
    }

    Column(modifier = Modifier.settingsCard().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = Ink.Text2,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "版本更新",
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                        color = Ink.Text1,
                    )
                    if (badgeText != null) {
                        Spacer(Modifier.width(8.dp))
                        StatusPill(text = badgeText, color = badgeColor)
                    }
                }
                Spacer(Modifier.height(3.dp))
                val failed = state as? UpdateUiState.Failed
                Text(
                    text = failed?.message ?: "可直接在应用内更新，也可交给浏览器下载",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = if (failed != null) WarnColor else Ink.Text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            when (val s = state) {
                UpdateUiState.Idle -> GhostButton(
                    text = "检查",
                    modifier = Modifier.height(32.dp),
                    onClick = { container.checkUpdate() },
                )

                UpdateUiState.Checking -> CircularProgressIndicator(
                    color = Accent.Brand,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )

                is UpdateUiState.UpToDate -> GhostButton(
                    text = "重新检查",
                    modifier = Modifier.height(32.dp),
                    onClick = { container.checkUpdate() },
                )

                is UpdateUiState.Available -> PrimaryButton(
                    text = "下载",
                    modifier = Modifier.height(32.dp),
                    onClick = { container.downloadUpdate(s.versionCode, s.asset) },
                )

                is UpdateUiState.Downloading -> GhostButton(
                    text = "取消",
                    modifier = Modifier.height(32.dp),
                    onClick = { container.cancelDownload() },
                )

                is UpdateUiState.Ready -> PrimaryButton(
                    text = "安装",
                    modifier = Modifier.height(32.dp),
                    onClick = {
                        when (val r = container.installUpdate(s.file)) {
                            InstallResult.Launched -> actionError = null
                            is InstallResult.Failure -> actionError = "安装失败：${r.message}"
                        }
                    },
                )

                is UpdateUiState.Failed -> GhostButton(
                    text = "重试",
                    modifier = Modifier.height(32.dp),
                    onClick = { container.retryDownload() },
                )
            }
        }

        if (downloading != null) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Ink.Line),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((downloading.fraction ?: 0f).coerceIn(0f, 1f))
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Accent.Gold),
                )
            }
        }

        // 应用内下载走 GitHub 的 302 + 配额，卡住时浏览器是唯一出路，入口必须一直可见。
        if (state is UpdateUiState.Available ||
            state is UpdateUiState.Downloading ||
            state is UpdateUiState.Failed
        ) {
            Spacer(Modifier.height(6.dp))
            BrowserLink(
                text = if (state is UpdateUiState.Downloading) "太慢？用浏览器下载" else "用浏览器下载",
                onClick = openInBrowser,
            )
        }

        actionError?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = WarnColor,
            )
        }
    }
}

/**
 * 次级操作：把 APK 直链交给系统浏览器。
 *
 * 用文字而不是纯图标：下不来的时候用户很难猜到图标含义；
 * 同时又做成无边框浅色文字，避免和右侧的主按钮抢注意力。
 */
@Composable
private fun BrowserLink(
    onClick: () -> Unit,
    text: String = "用浏览器下载",
) {
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.OpenInBrowser,
            contentDescription = null,
            tint = Accent.Brand,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Accent.Brand,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------- 关于

@Composable
private fun AboutPanel() {
    val context = LocalContext.current
    Column(modifier = Modifier.settingsCard()) {
        InfoRow(
            title = "应用版本",
            value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            subtitle = "打包于 ${BuildConfig.BUILD_TIME}",
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            thickness = 1.dp,
            color = Ink.Line,
        )
        InfoRow(
            title = "开源仓库",
            value = "GitHub",
            showExternal = true,
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/lwtor/Starpick")),
                    )
                }
            },
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            thickness = 1.dp,
            color = Ink.Line,
        )
        Text(
            text = "星匣不内置、不预置任何影视内容，播放源由用户自行配置。\n本项目以 MIT 协议开源，与任何内容提供方无关。",
            style = MaterialTheme.typography.labelSmall.copy(lineHeight = 18.sp),
            color = Ink.Text3,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun InfoRow(
    title: String,
    value: String,
    subtitle: String? = null,
    showExternal: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = Ink.Text1,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = Ink.Text3,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = Ink.Text2,
        )
        if (showExternal) {
            Spacer(Modifier.width(5.dp))
            Icon(
                imageVector = Icons.Filled.OpenInBrowser,
                contentDescription = null,
                tint = Ink.Text3,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- 小组件

/** 状态胶囊：底色是同色 14% 透明，读起来像标签而不是按钮。 */
@Composable
private fun StatusPill(
    text: String,
    color: Color,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = color,
            maxLines = 1,
        )
    }
}

/** 缓存大小按量级取单位，1 GB 上限显示成「1.0 GB」比「1024 MB」好读。 */
private fun formatCacheSize(bytes: Long): String = when {
    bytes >= 3L * 1024 * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    else -> "%.0f MB".format(bytes / 1024.0 / 1024.0)
}

private val WarnColor = Color(0xFFFF6B6B)

/** 添加采集源：底部弹层，只填域名，路径自动补全。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AddSourceSheet(container: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Ink.Text1,
        unfocusedTextColor = Ink.Text1,
        focusedBorderColor = Accent.Brand,
        unfocusedBorderColor = Ink.LineStrong,
        focusedContainerColor = Ink.Card,
        unfocusedContainerColor = Ink.Card,
        focusedLabelColor = Accent.Brand,
        unfocusedLabelColor = Ink.Text3,
        cursorColor = Accent.Brand,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp),
        ) {
            Text(
                text = "添加采集源",
                style = MaterialTheme.typography.titleLarge,
                color = Ink.Text1,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "填写苹果 CMS V10 站点地址，应用不内置任何采集站。",
                style = MaterialTheme.typography.labelMedium,
                color = Ink.Text3,
            )
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称（可留空）") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    testResult = null
                },
                label = { Text("站点地址") },
                placeholder = { Text("https://example.com", color = Ink.Text3) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "只需填域名，App 会自动请求 /api.php/provide/vod/",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.Text3,
            )

            testResult?.let { result ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = result,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (result.startsWith("连接正常")) Accent.Online else Accent.New,
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(
                    text = if (testing) "测试中…" else "测试连接",
                    modifier = Modifier.weight(1f),
                    enabled = !testing && url.isNotBlank(),
                ) {
                    testing = true
                    testResult = null
                    scope.launch {
                        val site = SourceSite(
                            id = UUID.randomUUID().toString(),
                            name = name.ifBlank { "未命名源" },
                            baseUrl = url.trim(),
                        )
                        val health = withContext(Dispatchers.IO) {
                            runCatching { container.api.probe(site) }
                                .getOrDefault(SourceHealth.UNKNOWN)
                        }
                        testResult = when (health) {
                            SourceHealth.ONLINE -> "连接正常，可以保存"
                            SourceHealth.OFFLINE -> "连不上，检查地址或网络"
                            SourceHealth.UNKNOWN -> "网络超时没测通，可以先保存，之后在列表里重测"
                        }
                        testing = false
                    }
                }
                PrimaryButton(
                    text = if (saving) "保存中…" else "保存并启用",
                    modifier = Modifier.weight(1f),
                    enabled = !saving && url.isNotBlank(),
                ) {
                    saving = true
                    scope.launch {
                        container.store.add(name.ifBlank { "未命名源" }, url)
                        saving = false
                        onDismiss()
                    }
                }
            }
        }
    }
}
