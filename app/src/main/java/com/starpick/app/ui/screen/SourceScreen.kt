package com.starpick.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starpick.app.AppContainer
import com.starpick.app.core.api.SourceHealth
import com.starpick.app.core.model.SourceSite
import com.starpick.app.ui.component.EmptyState
import com.starpick.app.ui.component.GhostButton
import com.starpick.app.ui.component.PrimaryButton
import com.starpick.app.ui.component.StatusPill
import com.starpick.app.ui.component.settingsCard
import com.starpick.app.ui.theme.Accent
import com.starpick.app.ui.theme.Ink
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内容源的探活结果。
 *
 * 存在导航层而不是页面里：检测是用户主动点出来的结果，切走再回来不该归零重跑 ——
 * 那正是上一版「动不动就检测」的毛病。进程活着，结论就留着。
 */
class SourceHealthUiState {
    val health = mutableStateMapOf<String, SourceHealth>()
    var checking by mutableStateOf(false)
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
}

/**
 * 内容源管理（设置里的二级页）。
 *
 * 采集源属于「配一次就不管」的设置项，长期摆在设置首屏会挤掉真正常看的东西
 * （缓存占用、版本更新）。这里收进独立页面，首屏只留一行摘要。
 *
 * 页面只有一个主动作：**全部检测**。不做进页面自动探活 —— 用户配了七八个源，
 * 每进一次设置就打十几个请求既慢又容易被源站限流；要结论就点一次。
 */
@Composable
fun SourceScreen(
    container: AppContainer,
    state: SourceHealthUiState,
    onBack: () -> Unit,
) {
    val sources by container.store.sources.collectAsState()
    val scope = rememberCoroutineScope()
    var showAddSheet by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    // 检测挂在页面 scope 上：退出页面即取消。取消后把标记复位，否则下次进来会卡在「检测中」。
    DisposableEffect(Unit) {
        onDispose {
            state.checking = false
            state.done = 0
        }
    }

    val checkAll: () -> Unit = {
        if (!state.checking && sources.isNotEmpty()) {
            scope.launch {
                state.checking = true
                state.done = 0
                state.total = sources.size
                // 并发探活：每个源独立超时，互不拖累。串行时八个源最坏要等三分钟。
                coroutineScope {
                    sources.forEach { site ->
                        launch {
                            val health = withContext(Dispatchers.IO) {
                                runCatching { container.api.probe(site) }
                                    .getOrDefault(SourceHealth.UNKNOWN)
                            }
                            state.health[site.id] = health
                            state.done += 1
                        }
                    }
                }
                state.checking = false
            }
        }
    }

    val checkedCount = sources.count { state.health.containsKey(it.id) }
    val onlineCount = sources.count { state.health[it.id] == SourceHealth.ONLINE }
    val offlineCount = sources.count { state.health[it.id] == SourceHealth.OFFLINE }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        SourceTopBar(
            checking = state.checking,
            done = state.done,
            total = state.total,
            canCheck = sources.isNotEmpty(),
            onBack = onBack,
            onCheckAll = checkAll,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 28.dp),
        ) {
            item {
                val hint = when {
                    sources.isEmpty() ->
                        "搜索、首页都要靠采集站取数据，填一个苹果 CMS V10 站点地址就能用。"
                    state.checking ->
                        "正在逐个访问源站，慢的站点要等到超时；此时返回会中断检测。"
                    checkedCount == 0 ->
                        "源站状态不会自动检查。想看哪些还能用，点右上角「全部检测」跑一遍。"
                    else -> buildString {
                        append("上次检测：$checkedCount 个站点里 $onlineCount 个可用")
                        if (offlineCount > 0) append("，$offlineCount 个连不上")
                        append("。「超时」是没等到回应，不代表源站坏了。")
                    }
                }
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 17.sp),
                    color = Ink.Text3,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }

            if (sources.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有内容源",
                        description = "星匣不内置任何影视内容，源由你自己配置。",
                        icon = Icons.Filled.Dns,
                        actionLabel = "添加采集源",
                        onAction = { showAddSheet = true },
                    )
                }
            } else {
                items(sources, key = { it.id }) { site ->
                    Column {
                        SourceRow(
                            site = site,
                            health = state.health[site.id],
                            onToggle = { enabled ->
                                scope.launch { container.store.setEnabled(site.id, enabled) }
                            },
                            onRemove = { scope.launch { container.store.remove(site.id) } },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }

                item { AddSourceButton(onClick = { showAddSheet = true }) }

                item {
                    Text(
                        text = "只有点「全部检测」才会访问源站，平时不做任何探测。",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = Ink.Text3,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        AddSourceSheet(container = container, onDismiss = { showAddSheet = false })
    }
}

// ---------------------------------------------------------------- 顶栏

@Composable
private fun SourceTopBar(
    checking: Boolean,
    done: Int,
    total: Int,
    canCheck: Boolean,
    onBack: () -> Unit,
    onCheckAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Ink.Text1,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "内容源",
            style = MaterialTheme.typography.titleLarge,
            color = Ink.Text1,
            modifier = Modifier.weight(1f),
        )
        GhostButton(
            text = if (checking) "检测中 $done/$total" else "全部检测",
            icon = if (checking) null else Icons.Filled.Refresh,
            modifier = Modifier.height(34.dp),
            enabled = !checking && canCheck,
            onClick = onCheckAll,
        )
    }
}

// ---------------------------------------------------------------- 列表项

/**
 * 单个采集源两行：**它是谁**（名称 / 域名 + 开关）、**它现在怎么样**（状态胶囊 + 删除）。
 *
 * 不再每张卡配一个「重测」：单个源想复测，点一次「全部检测」的成本也就几秒，
 * 多一个入口只会让这一页到处是小按钮。
 */
@Composable
private fun SourceRow(
    site: SourceSite,
    health: SourceHealth?,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .settingsCard()
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = site.name,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                color = if (site.enabled) Ink.Text1 else Ink.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = site.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.scale(0.86f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Accent.Brand,
                    uncheckedThumbColor = Ink.Text2,
                    uncheckedTrackColor = Ink.Selected,
                    uncheckedBorderColor = Ink.LineStrong,
                ),
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = site.baseUrl
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .trimEnd('/'),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = Ink.Text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            val (label, color) = when (health) {
                SourceHealth.ONLINE -> "可用" to Accent.Online
                SourceHealth.OFFLINE -> "连不上" to Accent.New
                SourceHealth.UNKNOWN -> "超时" to Accent.Gold
                null -> "未检测" to Ink.Text3
            }
            StatusPill(text = label, color = color)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = "删除该源",
                    tint = Ink.Text3,
                    modifier = Modifier.size(17.dp),
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

// ---------------------------------------------------------------- 添加弹层

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
                            SourceHealth.UNKNOWN -> "网络超时没测通，可以先保存，之后在列表里一起检测"
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
