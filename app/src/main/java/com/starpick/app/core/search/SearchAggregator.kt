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

    /**
     * N 个源并行搜索第 [page] 页：单源超时或失败不影响其他源。
     * 苹果 CMS 的 `pg` 每页条数由站点决定（常见 20 条），所以搜索页要「加载更多」时
     * 用这个逐页取，而不是一次把整站拉下来。
     */
    suspend fun search(
        sites: List<SourceSite>,
        keyword: String,
        page: Int = 1,
    ): SearchOutcome = supervisorScope {
        val jobs = sites.filter { it.enabled }.map { site ->
            async {
                val result = runCatching {
                    withTimeout(PER_SOURCE_TIMEOUT_MS) { api.search(site, keyword, page) }
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
    suspend fun grouped(
        sites: List<SourceSite>,
        keyword: String,
        page: Int = 1,
    ): SearchOutcome = toOutcome(search(sites, keyword, page))

    /**
     * 带写法规避的搜索：先用原词搜，**仅当所有源都零结果**时，才并发补搜词形变体。
     *
     * 苹果 CMS 用标题子串匹配，用户「哈利波特」搜不到写成「哈利·波特」的片，
     * 是实测量子源 / dytt 源上真实发生的高频落空原因。原词命中时这条路径零额外开销。
     * 翻页（[page] > 1）不补偿：否则不同词的第 2 页会混进同一份结果、重复且无序。
     */
    suspend fun groupedWithVariants(
        sites: List<SourceSite>,
        keyword: String,
        page: Int = 1,
    ): SearchOutcome = supervisorScope {
        val first = search(sites, keyword, page)
        if (first.items.isNotEmpty() || page > 1) return@supervisorScope toOutcome(first)

        val variants = SearchTerms.variants(keyword).drop(1) // 第 1 个是原词，已搜过
        if (variants.isEmpty()) return@supervisorScope toOutcome(first)

        // 每个变体都对全部源搜一轮；变体之间并发，单源超时由 search() 内部兜住。
        val rounds = variants.map { variant ->
            async {
                runCatching { withTimeout(PER_SOURCE_TIMEOUT_MS) { search(sites, variant, page) } }
                    .getOrDefault(SearchOutcome())
            }
        }.awaitAll()

        val items = mutableListOf<VodItem>()
        val seen = HashSet<String>()
        val failed = LinkedHashSet(first.failedSources)
        (listOf(first) + rounds).forEach { outcome ->
            outcome.items.forEach { if (seen.add(it.key)) items += it }
            failed.addAll(outcome.failedSources)
        }
        toOutcome(SearchOutcome(items, failedSources = failed.toList()))
    }

    /**
     * 把多页结果合成一份：按 `sourceId:vodId` 去掉跨页重复（不同站调整分页时会重复），
     * 再按标题重新分组、按源数量排序。翻页时调用方只保留合并后的这一份。
     */
    fun merge(outcomes: List<SearchOutcome>): SearchOutcome {
        val items = outcomes.flatMap { it.items }.distinctBy { it.key }
        return toOutcome(
            SearchOutcome(
                items = items,
                failedSources = outcomes.flatMap { it.failedSources }.distinct(),
            ),
        )
    }

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
