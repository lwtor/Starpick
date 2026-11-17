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

    /** 历史用的更宽松的内容键，见 [historyKeyOf]。 */
    val historyKey: String get() = historyKeyOf(title)
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

/**
 * 观看历史的内容键。
 *
 * 比 [contentKeyOf] 更宽松：搜索分组必须把「第1集 / 第2集」当成不同条目，
 * 才能逐集点播；但观看历史恰恰相反 —— 同一部剧无论来自哪个源、哪条线路、
 * 片名后面挂的是「第03集」还是「更新至24集」，都只能占一行。
 * 所以这里再去掉集数尾巴，并把「第一季 / 第1季」统一，
 * 从写入侧保证一条内容一条记录，不靠 UI 二次合并。
 */
private val EPISODE_TAIL = Regex("(第?[0-9]{1,4}集|ep[0-9]{1,4}|更新至[0-9]{1,4}集?)$")
private val CN_SEASON = Regex("第([零〇一二两三四五六七八九十]{1,4})季")

private val CN_DIGITS = mapOf(
    '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
    '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
)

private fun cnSeasonNumber(value: String): Int {
    if (value == "十") return 10
    if (!value.contains("十")) return value.singleOrNull()?.let { CN_DIGITS[it] } ?: 0
    val (tens, ones) = value.split("十", limit = 2)
    val high = if (tens.isEmpty()) 1 else CN_DIGITS[tens.singleOrNull()] ?: 1
    val low = if (ones.isEmpty()) 0 else CN_DIGITS[ones.singleOrNull()] ?: 0
    return high * 10 + low
}

fun historyKeyOf(title: String): String {
    val base = contentKeyOf(title)
    var key = base
    // 「某某（第03集）」「某某 更新至24集」去掉尾巴；最多两层（如「某某 第一季 第1集」）。
    repeat(2) { key = key.replace(EPISODE_TAIL, "") }
    key = key.replace(CN_SEASON) { match ->
        val number = cnSeasonNumber(match.groupValues[1])
        if (number > 0) "第${number}季" else match.value
    }
    return key.ifBlank { base }
}

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
