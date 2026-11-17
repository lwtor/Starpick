package com.starpick.app.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starpick.app.AppContainer
import com.starpick.app.BuildConfig
import com.starpick.app.UpdateUiState
import com.starpick.app.core.api.SourceHealth
import com.starpick.app.ui.component.GhostButton
import com.starpick.app.ui.component.PrimaryButton
import com.starpick.app.ui.component.SettingsSectionHeader
import com.starpick.app.ui.component.StatusPill
import com.starpick.app.ui.component.WarnColor
import com.starpick.app.ui.component.formatCacheSize
import com.starpick.app.ui.component.settingsCard
import com.starpick.app.ui.theme.Accent
import com.starpick.app.ui.theme.Ink
import dev.veneranative.updater.InstallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置：内容源（入口）/ 播放与存储 / 关于。
 *
 * 三条原则：
 * 1. **配一次就不管的东西只留入口**。采集源列表、增删、探活全在二级页（[SourceScreen]），
 *    首屏只给一行「几个站点、几个可用」；否则设置页一半屏是被几个源长期占着的。
 * 2. **一个分区只讲一件事**，分区标题右侧给出该类内容的汇总（缓存占用 / 更新状态）。
 * 3. **读优先于点**：状态用彩色胶囊，操作按钮常驻最右；与播放无关的信息收到最底「关于」。
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    health: SourceHealthUiState,
    onOpenSources: () -> Unit,
) {
    val sources by container.store.sources.collectAsState()
    val enabledCount = sources.count { it.enabled }
    val checkedCount = sources.count { health.health.containsKey(it.id) }
    val onlineCount = sources.count { health.health[it.id] == SourceHealth.ONLINE }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
    ) {
        item { ScreenHeader() }

        item { SettingsSectionHeader(title = "内容源", topPadding = 24.dp) }
        item {
            SourcesEntryCard(
                total = sources.size,
                enabled = enabledCount,
                checked = checkedCount,
                online = onlineCount,
                onClick = onOpenSources,
            )
        }

        item { SettingsSectionHeader(title = "播放与存储", topPadding = 26.dp) }
        item { CacheCard(container) }

        item { SettingsSectionHeader(title = "关于", topPadding = 26.dp) }
        item { UpdatePanel(container) }
        item {
            Spacer(Modifier.height(8.dp))
            AboutPanel()
        }
    }
}

// ---------------------------------------------------------------- 页头

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

// ---------------------------------------------------------------- 内容源入口

/**
 * 内容源在首屏只是一行入口：**几个站点、几个已启用、上次检测几个可用**。
 *
 * 这三样是「我要不要去改配置」的判断依据，看完就够了；至于哪个源挂了、要加要删，
 * 都属于低频且有破坏性的操作，收进二级页再动。
 */
@Composable
private fun SourcesEntryCard(
    total: Int,
    enabled: Int,
    checked: Int,
    online: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .settingsCard()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Dns,
            contentDescription = null,
            tint = Ink.Text2,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "内容源",
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                color = Ink.Text1,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = when {
                    total == 0 -> "还没有添加采集站，搜索和首页都要用它"
                    checked == 0 -> "$enabled 个已启用 · 共 $total 个站点"
                    else -> "$enabled 个已启用 · 共 $total 个站点 · $online 个可用"
                },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = Ink.Text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (total == 0) "添加" else "管理",
            style = MaterialTheme.typography.labelMedium,
            color = Accent.Brand,
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Ink.Text3,
            modifier = Modifier.size(18.dp),
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
