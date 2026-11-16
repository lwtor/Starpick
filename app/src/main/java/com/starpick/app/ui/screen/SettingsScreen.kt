package com.starpick.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import dev.veneranative.updater.UpdateCheck
import dev.veneranative.updater.UpdateStatus
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置：采集源管理 + 版本更新。
 *
 * 分组卡片 + 行式布局：每行 56dp，右侧只放开关或「值 + 箭头」，
 * 采集源的可视化落到三个信号：状态点（能否连通）、延迟（快不快）、开关（参不参与聚合）。
 */
@Composable
fun SettingsScreen(container: AppContainer) {
    val sources by container.store.sources.collectAsState()
    val scope = rememberCoroutineScope()
    var showAddSheet by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineSmall,
                color = Ink.Text1,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
            )
        }

        item {
            GroupCard(
                title = if (sources.isEmpty()) "采集源" else "采集源 · ${sources.count { it.enabled }} 个已启用",
            ) {
                if (sources.isEmpty()) {
                    RowItem(
                        title = "还没有源",
                        subtitle = "搜索和首页都需要至少一个采集站",
                        first = true,
                        showChevron = false,
                    )
                } else {
                    sources.forEachIndexed { index, site ->
                        SourceRow(
                            site = site,
                            first = index == 0,
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
                    }
                }
                RowItem(
                    title = "添加采集源",
                    first = false,
                    showChevron = false,
                    titleColor = Accent.Brand,
                    leading = {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            tint = Accent.Brand,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = { showAddSheet = true },
                )
            }
        }

        item {
            GroupCard(title = "播放缓存") {
                MediaCacheRow(container)
            }
        }

        item {
            GroupCard(title = "关于") {
                VersionRow(container)
                RowItem(
                    title = "应用版本",
                    first = false,
                    showChevron = false,
                    trailing = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                style = MaterialTheme.typography.labelMedium,
                                color = Ink.Text3,
                            )
                            // 打包时间：判断「手上这个包是不是最新」的唯一可靠依据。
                            Text(
                                text = "打包于 ${BuildConfig.BUILD_TIME}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Ink.Text3,
                            )
                        }
                    },
                )
                RowItem(
                    title = "开源仓库",
                    first = false,
                    trailing = {
                        Text(
                            text = "GitHub",
                            style = MaterialTheme.typography.labelMedium,
                            color = Ink.Text3,
                        )
                    },
                )
                Text(
                    text = "星匣不内置、不预置任何影视内容，播放源由用户自行配置。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.Text3,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }

    if (showAddSheet) {
        AddSourceSheet(
            container = container,
            onDismiss = { showAddSheet = false },
        )
    }
}

// ---------------------------------------------------------------- 结构件

@Composable
private fun GroupCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = Ink.Text3,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Ink.Card)
                .border(1.dp, Ink.Line, RoundedCornerShape(14.dp)),
            content = content,
        )
    }
}

@Composable
private fun RowItem(
    title: String,
    subtitle: String? = null,
    first: Boolean = false,
    showChevron: Boolean = true,
    titleColor: androidx.compose.ui.graphics.Color = Ink.Text1,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Column {
        if (!first) {
            HorizontalDivider(
                modifier = Modifier.padding(start = if (leading == null) 16.dp else 46.dp),
                thickness = 1.dp,
                color = Ink.Line,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink.Text3,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
            if (showChevron) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = Ink.Text3,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SourceRow(
    site: SourceSite,
    first: Boolean,
    onToggle: (Boolean) -> Unit,
    onPing: ((SourceHealth) -> Unit) -> Unit,
    onRemove: () -> Unit,
) {
    var health by remember(site.id) { mutableStateOf<SourceHealth?>(null) }
    var pinging by remember(site.id) { mutableStateOf(false) }

    // 进设置页先自动测一次：以前一行行都是「测试」，用户看不出到底谁挂了。
    LaunchedEffect(site.id) {
        if (health == null && !pinging) {
            pinging = true
            onPing { result ->
                health = result
                pinging = false
            }
        }
    }

    Column {
        if (!first) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 46.dp),
                thickness = 1.dp,
                color = Ink.Line,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dot = when (health) {
                SourceHealth.ONLINE -> Accent.Online
                SourceHealth.OFFLINE -> Accent.New
                // 测不准用金色而不是红色：超时不代表源站挂了。
                SourceHealth.UNKNOWN -> Accent.Gold
                null -> Ink.Text3
            }
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(dot),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = site.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (site.enabled) Ink.Text1 else Ink.Text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = site.baseUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.Text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = when {
                            pinging -> "测试中…"
                            health == SourceHealth.ONLINE -> "可用"
                            health == SourceHealth.OFFLINE -> "不可用，点此重测"
                            health == SourceHealth.UNKNOWN -> "超时未测准，点此重测"
                            else -> "测试"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = when (health) {
                            SourceHealth.ONLINE -> Accent.Online
                            SourceHealth.OFFLINE -> Accent.New
                            SourceHealth.UNKNOWN -> Accent.Gold
                            null -> Accent.Brand
                        },
                        modifier = Modifier.clickable(enabled = !pinging) {
                            pinging = true
                            onPing { result ->
                                health = result
                                pinging = false
                            }
                        },
                    )
                    Text(
                        text = "删除",
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink.Text3,
                        modifier = Modifier.clickable(onClick = onRemove),
                    )
                }
            }
            Switch(
                checked = site.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                    checkedTrackColor = Accent.Brand,
                    uncheckedThumbColor = Ink.Text2,
                    uncheckedTrackColor = Ink.Selected,
                    uncheckedBorderColor = Ink.LineStrong,
                ),
            )
        }
    }
}

/**
 * 版本更新行：检查 → 下载（实时进度条）→ 安装，一条链路全部内联，不打弹窗。
 *
 * 进度不是转圈猜的：下载走库的流式接口，每 256 KB 回填一次字节数，
 * 所以能看到「45% · 11.2 / 24.4 MB」这种真实读数。
 */
@Composable
private fun VersionRow(container: AppContainer) {
    val state by container.update.collectAsState()
    var actionError by remember { mutableStateOf<String?>(null) }

    // 浏览器兜底：应用内下载走的是 GitHub 的 302 + API 配额，卡住时浏览器是唯一出路。
    val openInBrowser: () -> Unit = {
        actionError = if (container.openDownloadInBrowser()) {
            null
        } else {
            "没有找到可用的浏览器，无法外部下载"
        }
    }

    val statusText = when (val s = state) {
        UpdateUiState.Idle -> "点右侧检查是否有新版本"
        UpdateUiState.Checking -> "正在检查…"
        is UpdateUiState.UpToDate -> "已是最新版本（${s.tag}）"
        is UpdateUiState.Available -> "发现新版本 ${s.tag}"
        is UpdateUiState.Downloading -> buildString {
            append("正在下载 ")
            append(s.fraction?.let { "${(it * 100).toInt()}%" } ?: "…")
            if (s.totalBytes != null && s.totalBytes > 0) {
                append(" · ")
                append(formatMegabytes(s.bytes))
                append(" / ")
                append(formatMegabytes(s.totalBytes))
            }
            if (s.bytesPerSecond > 0) {
                append(" · ")
                append(formatSpeed(s.bytesPerSecond))
                val remaining = (s.totalBytes ?: 0L) - s.bytes
                if (remaining > 0) {
                    val seconds = remaining / s.bytesPerSecond
                    if (seconds in 1..7200) {
                        append("，剩 ")
                        append(formatEta(seconds))
                    }
                }
            }
        }

        is UpdateUiState.Ready -> "新版本已下载，点「安装」"
        is UpdateUiState.Failed -> "失败：${s.message}"
    }

    val accentStatus = state is UpdateUiState.Available ||
        state is UpdateUiState.Ready ||
        state is UpdateUiState.Downloading

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = Ink.Text2,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "版本更新",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink.Text1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        state is UpdateUiState.Failed -> WarnColor
                        accentStatus -> Accent.Gold
                        else -> Ink.Text3
                    },
                )
            }
            Spacer(Modifier.width(10.dp))
            when (val s = state) {
                UpdateUiState.Idle -> GhostButton(
                    text = "检查更新",
                    modifier = Modifier.height(34.dp),
                    onClick = { container.checkUpdate() },
                )

                UpdateUiState.Checking -> CircularProgressIndicator(
                    color = Accent.Brand,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )

                is UpdateUiState.UpToDate -> GhostButton(
                    text = "重新检查",
                    modifier = Modifier.height(34.dp),
                    onClick = { container.checkUpdate() },
                )

                is UpdateUiState.Available -> Row(verticalAlignment = Alignment.CenterVertically) {
                    BrowserLink(onClick = openInBrowser)
                    Spacer(Modifier.width(6.dp))
                    PrimaryButton(
                        text = "下载",
                        modifier = Modifier.height(34.dp),
                        onClick = { container.downloadUpdate(s.versionCode, s.asset) },
                    )
                }

                is UpdateUiState.Downloading -> Text(
                    text = s.fraction?.let { "${(it * 100).toInt()}%" } ?: "…",
                    style = MaterialTheme.typography.labelLarge,
                    color = Accent.Gold,
                    fontWeight = FontWeight.Bold,
                )

                is UpdateUiState.Ready -> PrimaryButton(
                    text = "安装",
                    modifier = Modifier.height(34.dp),
                    onClick = {
                        when (val r = container.installUpdate(s.file)) {
                            InstallResult.Launched -> actionError = null
                            is InstallResult.Failure -> actionError = "安装失败：${r.message}"
                        }
                    },
                )

                is UpdateUiState.Failed -> GhostButton(
                    text = "重试",
                    modifier = Modifier.height(34.dp),
                    onClick = { container.retryDownload() },
                )
            }
        }

        val downloading = state as? UpdateUiState.Downloading
        if (downloading != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 46.dp, end = 16.dp, bottom = 12.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Ink.Line),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((downloading.fraction ?: 0f).coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Accent.Gold),
                )
            }
        }

        // 下载中 / 失败时补一条浏览器通道：应用内下载卡住时用户没有别的出路。
        if (state is UpdateUiState.Downloading || state is UpdateUiState.Failed) {
            BrowserLink(
                text = if (state is UpdateUiState.Downloading) "下载太慢？改用浏览器下载" else "改用浏览器下载",
                modifier = Modifier.padding(start = 42.dp, bottom = 8.dp),
                onClick = openInBrowser,
            )
        }

        actionError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = WarnColor,
                modifier = Modifier.padding(start = 46.dp, end = 16.dp, bottom = 10.dp),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(start = 46.dp), thickness = 1.dp, color = Ink.Line)
    }
}

/**
 * 次级操作：把 APK 直链交给系统浏览器。
 *
 * 用文字而不是纯图标，是因为「下不下来」时用户很难猜到图标含义；
 * 又做成无边框的浅色文字，避免和在右侧的主按钮抢注意力。
 */
@Composable
private fun BrowserLink(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = "浏览器",
) {
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.OpenInBrowser,
            contentDescription = null,
            tint = Accent.Brand,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Accent.Brand,
            maxLines = 1,
        )
    }
}

/**
 * 播放缓存行：显示占用 / 上限，可一键清空。
 *
 * 缓存是「边看边写」的副产物，用户看不见它从哪来，所以这里必须给出
 * 明确的三件事：占了多少、最多能占多少、怎么清掉。
 */
@Composable
private fun MediaCacheRow(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var used by remember { mutableLongStateOf(0L) }
    var clearing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        used = withContext(Dispatchers.IO) { container.mediaCacheBytes }
    }

    RowItem(
        title = "边看边缓存",
        subtitle = notice ?: "已用 ${formatCacheSize(used)} / 上限 " +
            "${formatCacheSize(container.mediaCacheLimitBytes)}，重看不重复下载",
        first = true,
        showChevron = false,
        leading = {
            Icon(
                imageVector = Icons.Filled.Storage,
                contentDescription = null,
                tint = Ink.Text2,
                modifier = Modifier.size(18.dp),
            )
        },
        trailing = {
            if (clearing) {
                CircularProgressIndicator(
                    color = Accent.Gold,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                GhostButton(
                    text = "清除",
                    modifier = Modifier.height(34.dp),
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
        },
    )
}

private fun formatMegabytes(bytes: Long): String =
    "%.1f MB".format(bytes / 1024.0 / 1024.0)

/** 下载速度：慢连接下 KB/s 更有信息量，别一律换算成 MB。 */
private fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024L * 1024 -> "%.1f MB/s".format(bytesPerSecond / 1024.0 / 1024.0)
    bytesPerSecond >= 1024 -> "%.0f KB/s".format(bytesPerSecond / 1024.0)
    else -> "${bytesPerSecond} B/s"
}

private fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "${seconds} 秒"
    seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
    else -> "很久"
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
                    .background(androidx.compose.ui.graphics.Color(0x38FFFFFF)),
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
