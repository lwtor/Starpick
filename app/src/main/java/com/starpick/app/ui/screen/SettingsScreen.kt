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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
                                    val ok = withContext(Dispatchers.IO) {
                                        runCatching { container.api.ping(site) }.getOrDefault(false)
                                    }
                                    onResult(ok)
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
            GroupCard(title = "关于") {
                VersionRow(container)
                RowItem(
                    title = "应用版本",
                    first = false,
                    showChevron = false,
                    trailing = {
                        Text(
                            text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.labelMedium,
                            color = Ink.Text3,
                        )
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
    onPing: ((Boolean) -> Unit) -> Unit,
    onRemove: () -> Unit,
) {
    var ping by remember(site.id) { mutableStateOf<Boolean?>(null) }
    var pinging by remember(site.id) { mutableStateOf(false) }

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
            val dot = when (ping) {
                true -> Accent.Online
                false -> Accent.New
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
                            ping == true -> "可用"
                            ping == false -> "不可用"
                            else -> "测试"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = Accent.Brand,
                        modifier = Modifier.clickable(enabled = !pinging) {
                            pinging = true
                            onPing { ok ->
                                ping = ok
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
    var installError by remember { mutableStateOf<String?>(null) }

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

                is UpdateUiState.Available -> PrimaryButton(
                    text = "下载",
                    modifier = Modifier.height(34.dp),
                    onClick = { container.downloadUpdate(s.versionCode, s.asset) },
                )

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
                            InstallResult.Launched -> installError = null
                            is InstallResult.Failure -> installError = "安装失败：${r.message}"
                        }
                    },
                )

                is UpdateUiState.Failed -> GhostButton(
                    text = "重试",
                    modifier = Modifier.height(34.dp),
                    onClick = { container.checkUpdate() },
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

        installError?.let { message ->
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

private fun formatMegabytes(bytes: Long): String =
    "%.1f MB".format(bytes / 1024.0 / 1024.0)

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
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { container.api.ping(site) }.getOrDefault(false)
                        }
                        testResult = if (ok) "连接正常，可以保存" else "连不上，检查地址或网络"
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
