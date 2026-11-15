package com.starpick.app.core.search

import com.starpick.app.core.api.CmsV10Api
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.SourceSite
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.parser.PlaylistParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
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
    suspend fun grouped(sites: List<SourceSite>, keyword: String): SearchOutcome {
        val outcome = search(sites, keyword)
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
