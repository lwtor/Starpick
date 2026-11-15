package com.starpick.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.starpick.app.AppContainer
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.search.SearchOutcome
import com.starpick.app.ui.component.Hint
import com.starpick.app.ui.component.VodCover
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(container: AppContainer, onOpen: (SearchGroup) -> Unit) {
    var query by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<SearchOutcome?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sources by container.store.sources.collectAsState(initial = emptyList())
    val hasEnabledSource = sources.any { it.enabled }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("片名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (query.isNotBlank() && hasEnabledSource && !loading) {
                    loading = true
                    scope.launch {
                        outcome = container.aggregator.grouped(sources, query.trim())
                        loading = false
                    }
                }
            },
            enabled = hasEnabledSource && !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (loading) "聚合中…" else "搜索 ${sources.count { it.enabled }} 个源")
        }

        if (!hasEnabledSource) {
            Hint("先在「源」页添加一个苹果 CMS V10 采集站，应用不内置任何源")
        }
        outcome?.failedSources?.takeIf { it.isNotEmpty() }?.let { failed ->
            Hint("这些源超时或失败：${failed.joinToString("、")}")
        }
        if (outcome != null && !loading && outcome?.groups.isNullOrEmpty()) {
            Hint("没有找到结果，换个关键词或检查源地址")
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            items(outcome?.groups.orEmpty()) { group ->
                val first = group.items.first()
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(group) },
                ) {
                    Row(modifier = Modifier.padding(10.dp)) {
                        VodCover(
                            title = first.title,
                            cover = first.cover,
                            modifier = Modifier.size(width = 60.dp, height = 84.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(first.title, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${group.items.size} 个源可用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = listOfNotNull(
                                    first.remark.takeIf { it.isNotBlank() },
                                    first.year.takeIf { it.isNotBlank() },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
