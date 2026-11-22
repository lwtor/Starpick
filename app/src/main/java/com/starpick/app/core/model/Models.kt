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
 * 分组键：同一部片的不同发行版本（粤语 / 国语 / 英文 / 4K）归到一组。
 *
 * 和 [contentKeyOf] 分开是因为两者的粒度要求恰好相反：
 *  - **分组**要合并：「沙丘[粤语]」「沙丘[国语]」必须是同一张卡片、同一个源列表，
 *    否则用户在每个入口只看得见一个版本；
 *  - **观看历史**要分开：换语言重看，进度不能互相覆盖。
 *
 * 所以分组键剔除版本标注，历史键（[historyKeyOf]）保留。
 *
 * 两步都要走：`strip` 删括号里的（「沙丘[粤语]」），`stripSuffix` 删不带括号、
 * 直接缀在片名后的（「少林足球粤语」）。少了后一步，「少林足球粤语」会自成一组——
 * 核心名明明已经和「少林足球」一致（都是 7 分），却因为分组键不同而被 [pickGroup]
 * 当成两个竞争者，最后只有一个组能进详情页，另一个组的源就凭空消失了。
 */
fun groupKeyOf(title: String): String =
    contentKeyOf(MediaTag.stripSuffix(MediaTag.strip(title)))

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
    // 先剥不带括号的尾部版本标注（「功夫粤语」→「功夫」），再删括号里那些。
    // 不剥的话「功夫粤语」的核心名是「功夫粤语」，和锚点「功夫」只能判"包含"拿低分，
    // 而它其实就是《功夫》的粤语版——这类漏匹配在采集源上非常常见。
    val bare = MediaTag.stripSuffix(title)
    var s = bare.lowercase().replace(TITLE_BRACKET, "")
    s = s.replace(SEASON_WORD, "").replace(TITLE_YEAR, "").replace(TITLE_NOISE, "")
    repeat(2) { s = s.replace(EPISODE_TAIL, "") }
    // 整名片名就是年份的情况（《2046》《1917》《2012》）删完为空，回退到只去噪点的结果。
    // 不回退的话这些片的锚点核心名恒为空，跨源匹配永远判「片名为空」，一条源都命中不了。
    return s.ifBlank { bare.lowercase().replace(TITLE_NOISE, "") }
}

/**
 * 拿去采集源搜索的词：剥掉季号 / 年份 / 方括号标注，其余（含间隔号等符号）原样保留。
 *
 * 采集站是 `vod_name` 子串匹配，搜成「某某 第一季」时，片名写作「某某」的站必然 0 结果
 * ——这是从豆瓣带季号的条目进来最容易踩的坑。反过来搜「某某」，片名写作「某某 第一季」
 * 的站仍然命中，因为短串是长串的子串。所以搜索词取宽的那个，筛选交给
 * 匹配管线的季号 / 年份否决，是严格更优的：命中面只增不减。
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
