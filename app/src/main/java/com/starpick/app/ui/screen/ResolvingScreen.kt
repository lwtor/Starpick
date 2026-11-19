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
import com.starpick.app.core.model.MatchAnchor
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.anchorMatchScore
import com.starpick.app.core.model.coreNameOf
import com.starpick.app.core.model.isStrongAnchorMatch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 从豆瓣条目到详情页的过渡：拿片名到用户已启用的 CMS 源里聚合搜索。
 *
 * 只等第一个命中的源，不等齐全部（单源最长 8 秒，等齐要好几秒），
 * 剩余源交给详情页在后台继续补齐。
 */
@Composable
fun ResolvingScreen(
    container: AppContainer,
    anchor: MatchAnchor,
    onResolved: (SearchGroup) -> Unit,
    onBack: () -> Unit,
) {
    val title = anchor.title
    val sources by container.store.sources.collectAsState()
    var state by remember(anchor) {
        mutableStateOf<ResolveState>(ResolveState.Loading)
    }

    LaunchedEffect(anchor, sources) {
        val enabled = sources.filter { it.enabled }
        if (enabled.isEmpty()) {
            state = ResolveState.NeedSource
            return@LaunchedEffect
        }
        state = ResolveState.Loading
        // 用核心片名去搜：带「第一季」这类后缀时采集站常模糊匹配到不相干的片子。
        val keyword = coreNameOf(title).ifBlank { title }
        // 全局择优：progressive 每来一个源就给一次累积快照，取其中评分最高的一组。
        // 强命中（核心名完全相等且年份/季号不冲突）可立即进详情，不必再等；
        // 否则等满 RESOLVE_WINDOW_MS 让所有源都参与比较，避免「第一个源的沾边结果」抢先锁定。
        val best = resolveBestGroup(container, enabled, keyword, anchor)
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
 * 从一批分组里挑出与 [anchor] 同一部片的一组。
 *
 * 采集站搜索是模糊的，不校验就会把「越狱二人组[电影解说]」当成「越狱兔」。
 * 用锚点多因子打分过滤 + 择优：核心名相等才算高分，年份/季号冲突直接否决，
 * 原名命中加分。[preferred] 是详情页当前条目，命中时优先保留，避免补源时把正在播的顶掉。
 */
internal fun pickGroup(
    groups: List<SearchGroup>,
    anchor: MatchAnchor,
    preferred: VodItem? = null,
): SearchGroup? {
    // 打分：一组里取「最像锚点的那条」代表这组的匹配质量。
    val scored = groups.mapNotNull { group ->
        val score = group.items.maxOf { anchorMatchScore(anchor, it) }
        if (score <= 0) null else group to score
    }
    if (scored.isEmpty()) return null
    // preferred 命中时原样保留，避免补源把正在播的源顶掉。
    if (preferred != null) {
        val matched = scored.firstOrNull { (group, _) ->
            group.items.any { it.sourceId == preferred.sourceId && it.vodId == preferred.vodId }
        }
        if (matched != null) return matched.first
    }
    // 先比匹配质量，再比源数量（同分时选源多的，换源空间大）。
    return scored.maxWith(
        compareBy<Pair<SearchGroup, Int>> { it.second }.thenBy { it.first.items.size },
    ).first
}

/** 某组里是否有条目构成强命中。 */
private fun SearchGroup.hasStrong(anchor: MatchAnchor): Boolean =
    items.any { isStrongAnchorMatch(anchor, it) }

/**
 * 全局择优：订阅 progressive 的累积快照，强命中立即返回；否则等满 [RESOLVE_WINDOW_MS]，
 * 在窗口内出现过的所有组里按锚点分数取最优。
 *
 * 不取「第一个命中」是因为第一个返回的源往往是模糊结果，容易把沾边条目当成目标；
 * 给所有源一个短暂窗口参与竞争，能显著降低「点 A 进 B」的概率，代价最多是一小段等待。
 */
private suspend fun resolveBestGroup(
    container: AppContainer,
    enabled: List<com.starpick.app.core.model.SourceSite>,
    keyword: String,
    anchor: MatchAnchor,
): SearchGroup? = coroutineScope {
    var strongest: SearchGroup? = null
    var bestScored: SearchGroup? = null
    var bestScore = 0
    // 收集完成信号：强命中或所有源都返回时 complete。
    val done = kotlinx.coroutines.CompletableDeferred<Unit>()

    val collectJob = launch {
        container.aggregator.progressive(enabled, keyword).collect { outcome ->
            val group = pickGroup(outcome.groups.orEmpty(), anchor) ?: return@collect
            val score = group.items.maxOf { anchorMatchScore(anchor, it) }
            if (score > bestScore) {
                bestScore = score
                bestScored = group
            }
            if (group.hasStrong(anchor)) {
                strongest = group
                done.complete(Unit)
                return@collect
            }
        }
        // 所有源返回（没强命中）：通知外层收尾。
        done.complete(Unit)
    }
    // 强命中或源全返回 → 立即结束；否则最多等 RESOLVE_WINDOW_MS，超时按当前最佳定夺。
    withTimeoutOrNull(RESOLVE_WINDOW_MS) { done.await() }
    collectJob.cancel()
    strongest ?: bestScored
}

private const val RESOLVE_WINDOW_MS = 3_000L

private sealed interface ResolveState {
    data object Loading : ResolveState
    data object NeedSource : ResolveState
    data object NotFound : ResolveState
    data class Done(val group: SearchGroup) : ResolveState
}
