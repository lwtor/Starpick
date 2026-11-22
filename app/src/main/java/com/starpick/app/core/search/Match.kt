package com.starpick.app.core.search

import com.starpick.app.core.model.MatchAnchor
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.search.match.MatchPipeline
import com.starpick.app.core.search.match.MatchTrace

/**
 * 从候选分组里按锚点择优：哪一组最可能是豆瓣那部片。
 *
 * 组内**逐条**裁决，通过的全部保留、被否决的剔除，再取最高分代表这一组的匹配质量。
 * 必须是逐条而不是「组里有一条像就算整组通过」——分组现在会把同一部片的
 * 「粤语 / 国语 / 英文」合到一组，也会把「[电影解说]」一并带进来；
 * 用 `maxOf` 粗判的话，解说条目会跟着正片一起出现在详情页的源列表里。
 *
 * [trace] 关掉时只写组级决策、不逐条写候选：翻页试算会对合并后的组反复裁决，
 * 候选明细翻几页就写几遍，会把真正有用的决策行挤出日志上限。
 *
 * 纯 Kotlin，零 Android / Compose 依赖，属于可整体搬进 KMP commonMain 的范围。
 * 原先它躺在 ui/screen 里，是历史原因，不是设计。
 */
internal fun pickGroup(
    groups: List<SearchGroup>,
    anchor: MatchAnchor,
    preferred: VodItem? = null,
    trace: Boolean = true,
): SearchGroup? {
    val scored = groups.mapNotNull { group ->
        val judged = group.items.map { it to MatchPipeline.judge(anchor, it) }
        if (trace) judged.forEach { (item, result) -> MatchTrace.candidate(item, result) }
        val alive = judged.filter { (_, result) -> result.accepted }
        if (alive.isEmpty()) return@mapNotNull null
        val best = alive.maxOf { (_, result) -> result.score }
        group.copy(items = alive.map { (item, _) -> item }) to best
    }

    val chosen = scored.takeIf { it.isNotEmpty() }?.let { candidates ->
        // preferred 命中时原样保留，避免补源把正在播的源顶掉。
        val hit = preferred?.let { playing ->
            candidates.firstOrNull { (group, _) ->
                group.items.any { item ->
                    item.sourceId == playing.sourceId && item.vodId == playing.vodId
                }
            }
        }
        hit ?: candidates.maxWith(
            compareBy<Pair<SearchGroup, Int>> { (_, score) -> score }
                .thenBy { (group, _) -> group.items.size },
        )
    }

    MatchTrace.decision(scored, chosen?.first)
    return chosen?.first
}
