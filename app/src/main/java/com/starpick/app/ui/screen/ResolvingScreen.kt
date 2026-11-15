package com.starpick.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.starpick.app.AppContainer
import com.starpick.app.core.model.SearchGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从豆瓣条目到详情页的过渡：拿片名到用户已启用的 CMS 源里聚合搜索，
 * 命中即进入详情，无源/无结果时给出可操作提示。
 */
@Composable
fun ResolvingScreen(
    container: AppContainer,
    title: String,
    onResolved: (SearchGroup) -> Unit,
    onBack: () -> Unit,
) {
    val sources by container.store.sources.collectAsState(initial = emptyList())
    var state by remember(title) {
        mutableStateOf<ResolveState>(ResolveState.Loading)
    }

    LaunchedEffect(title, sources) {
        val enabled = sources.filter { it.enabled }
        if (enabled.isEmpty()) {
            state = ResolveState.NeedSource
            return@LaunchedEffect
        }
        state = ResolveState.Loading
        val outcome = withContext(Dispatchers.IO) {
            container.aggregator.grouped(enabled, title)
        }
        val best = outcome.groups.orEmpty()
            .maxByOrNull { it.items.size }
        state = if (best != null) ResolveState.Done(best) else ResolveState.NotFound
    }

    Column(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
        }

        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                ResolveState.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "正在你的采集源里找《$title》…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ResolveState.NeedSource -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有可用的采集源", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "先去「设置 → 采集源」添加一个苹果 CMS V10 站点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onBack) { Text("返回") }
                }

                ResolveState.NotFound -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("没在源里找到这部片", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "换一个更短的关键词重试，或确认采集源正常",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onBack) { Text("返回") }
                }

                is ResolveState.Done -> {
                    // 命中后进入详情。用 remember 保证只回调一次，避免重复 push。
                    var fired by remember(s.group.title) { mutableStateOf(false) }
                    LaunchedEffect(s.group.title) {
                        if (!fired) {
                            fired = true
                            onResolved(s.group)
                        }
                    }
                    CircularProgressIndicator()
                }
            }
        }
    }
}

private sealed interface ResolveState {
    data object Loading : ResolveState
    data object NeedSource : ResolveState
    data object NotFound : ResolveState
    data class Done(val group: SearchGroup) : ResolveState
}
