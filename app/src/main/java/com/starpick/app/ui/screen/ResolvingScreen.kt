package com.starpick.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.coreNameOf
import com.starpick.app.core.model.titleMatches
import com.starpick.app.core.model.titleMatchScore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull

/**
 * 从豆瓣条目到详情页的过渡：拿片名到用户已启用的 CMS 源里聚合搜索。
 *
 * 只等第一个命中的源，不等齐全部（单源最长 8 秒，等齐要好几秒），
 * 剩余源交给详情页在后台继续补齐。
 */
@Composable
fun ResolvingScreen(
    container: AppContainer,
    title: String,
    onResolved: (SearchGroup) -> Unit,
    onBack: () -> Unit,
) {
    val sources by container.store.sources.collectAsState()
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
        // 用核心片名去搜：带「第一季」这类后缀时采集站常模糊匹配到不相干的片子。
        // 拿到第一个「标题确实匹配」的源才进详情，不再等齐所有源。
        val keyword = coreNameOf(title).ifBlank { title }
        val best = container.aggregator.progressive(enabled, keyword)
            .mapNotNull { outcome -> pickGroup(outcome.groups.orEmpty(), title) }
            .firstOrNull()
        state = if (best != null) ResolveState.Done(best) else ResolveState.NotFound
    }

    Column(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
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
                    // 命中后进入详情。onResolved 只回调一次（由 LaunchedEffect 的 key 保证），
                    // 这里只短暂渲染一个进度圈，随即被导航层替换。
                    LaunchedEffect(Unit) { onResolved(s.group) }
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/**
 * 从一批分组里挑出与 [expected] 同一部片的一组。
 *
 * 采集站搜索是模糊的，不校验标题就会把「越狱二人组[电影解说]」当成「越狱兔」。
 * 先按核心片名过滤掉不相关的组，再综合匹配质量与源数量择优；
 * [preferred] 是详情页已知的当前条目，命中时优先保留，避免换源时把正在播的顶掉。
 */
internal fun pickGroup(
    groups: List<SearchGroup>,
    expected: String,
    preferred: VodItem? = null,
): SearchGroup? {
    val related = groups.filter { group ->
        group.items.any { titleMatches(expected, it.title) }
    }
    if (related.isEmpty()) return null
    val matched = preferred?.let { fb ->
        related.firstOrNull { group ->
            group.items.any { it.sourceId == fb.sourceId && it.vodId == fb.vodId }
        }
    }
    if (matched != null) return matched
    // 先按匹配质量（含季号吻合），同源数打平时取匹配更好的；质量相同再比源数量。
    return related.maxWith(
        compareBy<SearchGroup> { group -> group.items.maxOf { titleMatchScore(expected, it.title) } }
            .thenBy { it.items.size },
    )
}

private sealed interface ResolveState {
    data object Loading : ResolveState
    data object NeedSource : ResolveState
    data object NotFound : ResolveState
    data class Done(val group: SearchGroup) : ResolveState
}
