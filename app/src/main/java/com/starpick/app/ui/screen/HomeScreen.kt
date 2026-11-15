package com.starpick.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.starpick.app.AppContainer
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.WatchProgress
import com.starpick.app.ui.component.Hint
import com.starpick.app.ui.component.SectionTitle
import com.starpick.app.ui.component.UpdateCard
import com.starpick.app.ui.component.VodCover
import com.starpick.app.ui.component.formatPosition

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpen: (SearchGroup) -> Unit,
    onResume: (VodItem, WatchProgress) -> Unit,
) {
    val favorites by container.store.favorites.collectAsState(initial = emptyList())
    val progress by container.store.progress.collectAsState(initial = emptyList())
    val recent by container.store.recent.collectAsState(initial = emptyList())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { UpdateCard(container) }
        item { SectionTitle("继续观看") }
        if (progress.isEmpty()) {
            item { Hint("还没有观看记录，去搜索一部剧开始吧") }
        } else {
            items(progress.take(5)) { record ->
                val item = recent.firstOrNull { it.key == record.key }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (item != null) {
                                    Modifier.clickable { onResume(item, record) }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(10.dp),
                    ) {
                        VodCover(
                            title = record.title,
                            cover = record.cover,
                            modifier = Modifier.size(width = 60.dp, height = 84.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(record.title, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${record.episodeName} · 看到 ${formatPosition(record.positionMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (item == null) {
                                Text(
                                    text = "条目缓存已清理，请从片匣重新打开",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
        item { SectionTitle("我的片匣") }
        if (favorites.isEmpty()) {
            item { Hint("在详情里点「加入片匣」，剧会收在这里") }
        } else {
            items(favorites) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(SearchGroup(item.title, listOf(item))) },
                ) {
                    Row(modifier = Modifier.padding(10.dp)) {
                        VodCover(
                            title = item.title,
                            cover = item.cover,
                            modifier = Modifier.size(width = 60.dp, height = 84.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(item.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = listOfNotNull(
                                    item.remark.takeIf { it.isNotBlank() },
                                    item.sourceName.takeIf { it.isNotBlank() },
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
