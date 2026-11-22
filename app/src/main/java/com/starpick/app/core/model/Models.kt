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

fun contentKeyOf(title: String): String {
    val base = title.lowercase().replace(TITLE_NOISE, "")
    // 中文数字季号归一成阿拉伯数字。否则「某某 第1季」与「某某 第一季」在搜索结果里
    // 会分成两组、是两个卡片，而 historyKeyOf 早已把两者当成同一条——
    // 同一部剧在两个入口算出两种键，分组和续播就会互相打架。
    return base.replace(CN_SEASON) { match ->
        val number = cnSeasonNumber(match.groupValues[1])
        if (number > 0) "第${number}季" else match.value
    }
}

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

/**
 * 跨源匹配相关工具：豆瓣条目 → 采集源结果时必须确认是同一部片。
 *
 * 采集站搜索是模糊匹配，搜「越狱兔」会连带返回「越狱二人组[电影解说]」这类
 * 只是名字沾边的条目。若只按「哪个组源最多」挑，就会张冠李戴。所以先归一到
 * 核心片名（去版本标注 / 季号 / 集数 / 年份），再做包含判断。
 */

/** 方括号里的版本标注不属于片名主体，如「[电影解说]」「（2024）」。 */
private val TITLE_BRACKET = Regex("[\\[【(（][^\\]】)）]*[\\]】)）]")
private val TITLE_YEAR = Regex("(?:19|20)[0-9]{2}")
private val SEASON_WORD = Regex(
    "第[0-9零〇一二两三四五六七八九十]{1,4}季|season[ ]*[0-9]{1,2}",
    RegexOption.IGNORE_CASE,
)

/** 取季号，中文「第一季」和英文「Season 2」都认；没有季号返回 null。 */
internal fun seasonNumberOf(title: String): Int? {
    val raw = SEASON_WORD.find(title)?.value ?: return null
    Regex("[0-9]+").find(raw)?.value?.toIntOrNull()?.let { return it }
    return cnSeasonNumber(raw.removePrefix("第").removeSuffix("季")).takeIf { it > 0 }
}

/** 核心片名：去版本标注、季号、集数、年份与标点，供跨源比对。 */
fun coreNameOf(title: String): String {
    var s = title.lowercase().replace(TITLE_BRACKET, "")
    s = s.replace(SEASON_WORD, "").replace(TITLE_YEAR, "").replace(TITLE_NOISE, "")
    repeat(2) { s = s.replace(EPISODE_TAIL, "") }
    return s
}

/**
 * 拿去采集源搜索的词：剥掉季号 / 年份 / 方括号标注，其余（含间隔号等符号）原样保留。
 *
 * 采集站是 `vod_name` 子串匹配，搜成「某某 第一季」时，片名写作「某某」的站必然 0 结果
 * ——这是从豆瓣带季号的条目进来最容易踩的坑。反过来搜「某某」，片名写作「某某 第一季」
 * 的站仍然命中，因为短串是长串的子串。所以搜索词取宽的那个，筛选交给
 * [anchorMatchScore] 的季号 / 年份否决，是严格更优的：命中面只增不减。
 *
 * 刻意不动「·」「:」这类符号：采集站正片名常带它们，去掉就搜不到了。
 * 整个片名都是年份的情况（《2046》《1917》）剥完为空，回退原标题。
 */
fun searchNameOf(title: String): String {
    val stripped = title
        .replace(TITLE_BRACKET, " ")
        .replace(SEASON_WORD, " ")
        .replace(TITLE_YEAR, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return stripped.ifBlank { title.trim() }
}

// ------------------------------------------------ 跨源锚点匹配（豆瓣 → 采集源）

/** 素材 / 衍生内容标注：与正片同源同名，但根本不是同一部片，不能当播放源。 */
private val DERIVATIVE_TAG = Regex(
    "解说|预告|花絮|速看|片段|彩蛋|特辑|混剪|影评|解析|盘点|reaction|trailer",
    RegexOption.IGNORE_CASE,
)

/**
 * 候选名带衍生标注、而锚点不带 → 这是解说 / 预告片，不是用户在找的那部片。
 *
 * 这是 [coreNameOf] 的盲区：它会连方括号带内容整体删掉，「越狱兔[电影解说]」的核心名
 * 因此等于「越狱兔」，和正片拿到同一个 6 分，再被「同分取源多者」的规则选中——
 * 解说类内容往往比正片铺得更广，于是稳定抢走正片的位置。
 *
 * 「合集」刻意不算：某某合集通常就是正片全集，仍然可播。
 */
private fun isDerivativeOf(expected: String, candidate: String): Boolean =
    DERIVATIVE_TAG.containsMatchIn(candidate) && !DERIVATIVE_TAG.containsMatchIn(expected)

/**
 * 锚点与采集源条目的匹配评分。越高越可能是同一部片，0 表示根本不是。
 *
 * 相比裸片名比对，多了年份与季号两个硬否决：
 *  - 衍生标注：候选是解说 / 预告时直接否决，见 [isDerivativeOf]。
 *  - 年份：两边都拿到且相差超过 1 年 → 判为不同作品（翻拍 / 同名），直接 0。
 *  - 季号：两边都明确且不等 → 判为不同季，直接 0（这是「第一季点进去第二季」的主因）。
 * 片名核心名相等才给高分；单纯包含（一方是另一方子串）只给低分，避免「越狱兔」被
 * 「越狱二人组」这类沾边条目抢走。原名命中作为额外加分。
 */
fun anchorMatchScore(anchor: MatchAnchor, item: VodItem): Int {
    val a = anchor.title.let { coreNameOf(it) }
        .ifBlank { return 0 }
    val b = coreNameOf(item.title)
    if (b.isEmpty()) return 0
    if (isDerivativeOf(anchor.title, item.title)) return 0

    // 年份硬否决：采集站 year 常是「2024」或「2024-01-01」，取前 4 位。
    val ay = anchor.year.takeFirst4Digit()
    val by = item.year.takeFirst4Digit()
    if (ay != null && by != null && kotlin.math.abs(ay - by) > 1) return 0

    // 季号硬否决：仅当两边都解析出季号且不等时否决。
    val anchorSeason = anchor.season
    val itemSeason = seasonNumberOf(item.title)
    if (anchorSeason != null && itemSeason != null && anchorSeason != itemSeason) return 0

    val nameScore = when {
        a == b -> 6
        a in b || b in a -> 2
        else -> return 0
    }
    // 原名（外文名 / 别名）命中：采集站常用原名，豆瓣用译名时靠这个救回。
    val original = coreNameOf(anchor.originalTitle)
    val originalBonus = if (original.length >= 2 && (original == b || original in b || b in original)) 2 else 0
    // 年份一致（非否决的情况下）小加分，年代表达模糊时不强求。
    val yearBonus = if (ay != null && ay == by) 1 else 0
    val seasonBonus = if (anchorSeason != null && anchorSeason == itemSeason) 1 else 0
    return nameScore + originalBonus + yearBonus + seasonBonus
}

/** 取字符串里第一个 4 位年份（19xx / 20xx），拿不到返回 null。 */
private fun String.takeFirst4Digit(): Int? =
    Regex("(?:19|20)[0-9]{2}").find(this)?.value?.toIntOrNull()

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
