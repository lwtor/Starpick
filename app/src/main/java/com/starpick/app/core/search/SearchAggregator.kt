package com.starpick.app.core.search

import com.starpick.app.core.api.CmsV10Api
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.SourceSite
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.parser.PlaylistParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * N 个源并行搜索：单源超时或失败不影响其他源，最后按归一标题合并展示。
 * 这是整个客户端最值得放进 KMP 共享层的一段逻辑。
 */
class SearchAggregator(private val api: CmsV10Api) {

    suspend fun search(sites: List<SourceSite>, keyword: String): SearchOutcome = supervisorScope {
        val jobs = sites.filter { it.enabled }.map { site ->
            async {
                val result = runCatching {
                    withTimeout(PER_SOURCE_TIMEOUT_MS) { api.search(site, keyword) }
                }
                site to result
            }
        }
        val finished = jobs.awaitAll()
        SearchOutcome(
            items = finished.flatMap { (_, result) -> result.getOrDefault(emptyList()) },
            failedSources = finished.mapNotNull { (site, result) ->
                site.name.takeIf { result.isFailure }
            },
        )
    }

    /** UI 直接消费的形态：同名结果聚成一组，组内保留各源条目供换源。 */
    suspend fun grouped(sites: List<SourceSite>, keyword: String): SearchOutcome =
        toOutcome(search(sites, keyword))

    /**
     * 逐源推送的版本：每个源返回就 emit 一次「当前已收集到的全部结果」。
     *
     * 详情页要等所有源（单源最长 8 秒）才显示太慢，用这个可以先拿第一个命中的源
     * 就把页面开出来，剩下的源在后面对比补齐。
     */
    fun progressive(sites: List<SourceSite>, keyword: String): Flow<SearchOutcome> = channelFlow {
        val enabled = sites.filter { it.enabled }
        if (enabled.isEmpty()) {
            send(SearchOutcome())
            return@channelFlow
        }
        val lock = Mutex()
        val items = mutableListOf<VodItem>()
        val failed = mutableListOf<String>()
        supervisorScope {
            enabled.forEach { site ->
                launch {
                    val result = runCatching {
                        withTimeout(PER_SOURCE_TIMEOUT_MS) { api.search(site, keyword) }
                    }
                    // 只在锁内更新/取快照，emit 放到锁外，避免挂起时占着锁。
                    val snapshot = lock.withLock {
                        result.onSuccess { items += it }.onFailure { failed += site.name }
                        items.toList() to failed.toList()
                    }
                    send(toOutcome(SearchOutcome(snapshot.first, failedSources = snapshot.second)))
                }
            }
        }
    }

    private fun toOutcome(outcome: SearchOutcome): SearchOutcome {
        val groups = outcome.items
            .groupBy { PlaylistParser.normalizeTitle(it.title) }
            .map { (_, items) -> SearchGroup(items.first().title, items) }
            .sortedByDescending { it.items.size }
        return outcome.copy(groups = groups)
    }

    companion object {
        private const val PER_SOURCE_TIMEOUT_MS = 8_000L
    }
}

data class SearchOutcome(
    val items: List<VodItem> = emptyList(),
    val groups: List<SearchGroup> = emptyList(),
    val failedSources: List<String> = emptyList(),
)
