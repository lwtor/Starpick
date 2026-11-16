package com.starpick.app.core.model

/**
 * 用户自行配置的苹果 CMS V10 采集站。应用不内置、不预置任何源。
 */
data class SourceSite(
    val id: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
)

/**
 * 一条影视记录。字段刻意贴近 CMS 原始协议，方便后续逐源适配。
 * 这一层是纯 Kotlin，不含任何 Android 依赖，将来可直接搬进 KMP 的 commonMain。
 */
data class VodItem(
    val sourceId: String,
    val sourceName: String,
    val vodId: String,
    val title: String,
    val cover: String = "",
    val remark: String = "",
    val category: String = "",
    val area: String = "",
    val year: String = "",
    val description: String = "",
    val playFrom: String = "",
    val playUrl: String = "",
) {
    /** 收藏使用的稳定标识；同一部剧来自不同源时收藏各自独立。 */
    val key: String get() = "$sourceId:$vodId"

    /** 观看历史按内容归并：同一部剧不管来自哪个源、哪条线路，只算一条。 */
    val contentKey: String get() = contentKeyOf(title)
}

/**
 * 片名归一化：观看历史与搜索结果分组共用这一套规则，避免同一部片在不同入口算出两种键。
 *
 * 不同采集源对同一部片的写法经常差一个空格或半/全角标点（「生活大爆炸 第一季」
 * 与「生活大爆炸第一季」「生活大爆炸（第一季）」），这里把空白（含全角空格、
 * 不换行空格、宽空白）与常见分隔标点全部丢掉，只留下片名本体。
 */
private val TITLE_NOISE = Regex("[\\s\\p{Z}\\u200B-\\u200D\\uFEFF:：·・\\-—_（）()【】\\[\\]]")

fun contentKeyOf(title: String): String = title.lowercase().replace(TITLE_NOISE, "")

data class Episode(val name: String, val url: String)

data class PlayGroup(val from: String, val episodes: List<Episode>)

/** 同名归一后的一组结果：UI 按组展示，点进去再选具体源。 */
data class SearchGroup(val title: String, val items: List<VodItem>)

data class WatchProgress(
    val key: String,
    val title: String,
    val cover: String,
    /** 上次播放用的源，用于详情页自动切回该源；老记录为空。 */
    val sourceId: String = "",
    val vodId: String = "",
    val groupIndex: Int,
    val episodeIndex: Int,
    val episodeName: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)
