package com.starpick.app.core.search

import com.starpick.app.core.model.MatchAnchor
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.anchorMatchScore

/**
 * 从候选分组里按锚点择优：哪一组最可能是豆瓣那部片。
 *
 * 组内取「最像锚点的那条」代表整组的匹配质量 —— 一个源返回的同名条目里，
 * 只要有一条带着匹配的年份、季号，这一组就值得优先。
 *
 * 纯 Kotlin，零 Android / Compose 依赖，属于可整体搬进 KMP commonMain 的范围。
 * 原先它躺在 ui/screen 里，是历史原因，不是设计。
 */
internal fun pickGroup(
    groups: List<SearchGroup>,
    anchor: MatchAnchor,
    preferred: com.starpick.app.core.model.VodItem? = null,
): SearchGroup? {
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
