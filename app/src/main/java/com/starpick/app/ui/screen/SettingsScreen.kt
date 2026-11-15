package com.starpick.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.starpick.app.AppContainer
import com.starpick.app.BuildConfig
import com.starpick.app.ui.component.SectionTitle
import dev.veneranative.updater.UpdateCheck
import kotlinx.coroutines.launch

/**
 * 设置页：版本信息 + 手动检查更新 + 采集源管理。
 * 「源」从底部 tab 并入这里，界面更清爽。
 */
@Composable
fun SettingsScreen(container: AppContainer) {
    val sources by container.store.sources.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp,
            vertical = 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 关于
        item {
            SectionTitle("关于")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "星匣",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Starpick · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    ManualUpdateSection(container)
                }
            }
        }

        // 采集源
        item {
            SectionTitle("采集源")
            SourceEditor(container)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        if (sources.isEmpty()) {
            item {
                Text(
                    "还没有源。搜索页需要至少一个启用的采集站。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(sources, key = { it.id }) { site ->
                SourceRow(
                    site = site,
                    onToggle = { enabled -> scope.launch { container.store.setEnabled(site.id, enabled) } },
                    onPing = { onResult ->
                        scope.launch {
                            onResult(if (container.api.ping(site)) "可用" else "不可用 / 超时")
                        }
                    },
                    onRemove = { scope.launch { container.store.remove(site.id) } },
                )
            }
        }
    }
}

@Composable
private fun ManualUpdateSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column {
        Text(
            text = "版本更新",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = message ?: "检查 GitHub Release 是否有新版本",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    if (!checking) {
                        checking = true
                        scope.launch {
                            message = when (val r = try {
                                container.updater.check(BuildConfig.VERSION_CODE.toLong())
                            } catch (e: Exception) {
                                null
                            }) {
                                is UpdateCheck.Available -> "发现新版本 code=${r.versionCode}"
                                is UpdateCheck.UpToDate -> "已是最新版本"
                                UpdateCheck.NoInstallableAsset -> "该发布中没有可安装的 APK"
                                null -> "检查失败，请检查网络"
                            }
                            checking = false
                        }
                    }
                },
                enabled = !checking,
            ) {
                Text(if (checking) "检查中…" else "检查更新")
            }
        }
    }
}

@Composable
private fun SourceEditor(container: AppContainer) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column {
        Text(
            "填写苹果 CMS V10 站点地址，应用不内置任何采集站。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名称（可留空）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("站点地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (url.isNotBlank()) {
                    scope.launch {
                        container.store.add(name.ifBlank { "未命名源" }, url)
                        name = ""
                        url = ""
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("添加") }
    }
}

@Composable
private fun SourceRow(
    site: com.starpick.app.core.model.SourceSite,
    onToggle: (Boolean) -> Unit,
    onPing: ((String) -> Unit) -> Unit,
    onRemove: () -> Unit,
) {
    var pingResult by remember(site.id) { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = site.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = site.enabled, onCheckedChange = onToggle)
            }
            Text(
                text = site.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onPing { pingResult = it } }) {
                    Text(pingResult ?: "测试")
                }
                TextButton(onClick = onRemove) { Text("删除") }
            }
        }
    }
}
