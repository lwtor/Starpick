package com.starpick.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.starpick.app.AppContainer
import com.starpick.app.ui.component.Hint
import com.starpick.app.ui.component.SectionTitle
import kotlinx.coroutines.launch

@Composable
fun SourcesScreen(container: AppContainer) {
    val sources by container.store.sources.collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var pingResults by remember { mutableStateOf(emptyMap<String, String>()) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SectionTitle("采集源")
        Hint("填写苹果 CMS V10 站点地址，例如 https://your-site.example.com 。应用不内置任何采集站。")
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

        Spacer(Modifier.height(16.dp))
        if (sources.isEmpty()) {
            Hint("还没有源。搜索页需要至少一个启用的采集站。")
        } else {
            sources.forEach { site ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                text = site.name,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = site.enabled,
                                onCheckedChange = { enabled ->
                                    scope.launch { container.store.setEnabled(site.id, enabled) }
                                },
                            )
                        }
                        Text(
                            text = site.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        val result = if (container.api.ping(site)) "可用" else "不可用 / 超时"
                                        pingResults = pingResults + (site.id to result)
                                    }
                                },
                            ) {
                                Text(pingResults[site.id] ?: "测试")
                            }
                            TextButton(
                                onClick = { scope.launch { container.store.remove(site.id) } },
                            ) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
}
