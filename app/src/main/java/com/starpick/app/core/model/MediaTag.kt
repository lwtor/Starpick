package com.starpick.app.core.model

/**
 * 发行版本标注：语言 / 配音 / 字幕 / 画质 / 剪辑版本。
 *
 * 这些标注挂在片名括号里，**不构成不同的片**，而是同一部片的不同发行版本。
 * 一个采集源经常同时铺「沙丘[粤语]」「沙丘[国语]」「沙丘[英文]」三条，
 * 用户希望这三条各自作为可选源出现，而不是被打散成三个互不相干的条目。
 *
 * 所以这里提供两件事：
 *  - [of]：抽出展示用标签（「粤语」「国语」「高清」），给源列表区分用。
 *  - [strip]：把这些纯标注括号整段删掉，让同一部片的不同版本落进同一个分组。
 *
 * 关键约束：只认**整段括号内容全部由标注词构成**的情况。
 * 「[电影解说]」「[预告片]」不属于发行版本，必须原样保留在片名里——
 * 它们由衍生内容规则去否决，不能在这里被当成版本标注抹掉，否则解说和正片会被归成一组。
 */
object MediaTag {

    /** 语言 / 配音 / 字幕。 */
    private val LANGUAGES = setOf(
        "粤语", "国语", "普通话", "英文", "英语", "日语", "韩语", "泰语", "法语", "德语",
        "俄语", "西语", "西班牙语", "意大利语", "印地语", "台配", "港配", "原声", "双语", "多语",
        "中字", "中英字幕", "中英双字", "双语字幕", "简中", "繁中", "字幕", "中文",
    )

    /** 画质 / 剪辑版本。 */
    private val VERSIONS = setOf(
        "1080p", "720p", "2160p", "4k", "8k", "hdr", "杜比视界", "蓝光", "超清", "高清",
        "bd", "hd", "完整版", "未删减", "加长版", "导演剪辑版", "修复版", "版",
    )

    /** 贪心切分用，长词必须排在前面，否则「粤」会先吃掉「粤语」。 */
    private val VOCAB: List<String> = (LANGUAGES + VERSIONS).sortedByDescending { it.length }

    private val BRACKET = Regex("[\\[【(（]([^\\]】)）]*)[\\]】)）]")
    private val NOISE = Regex("[\\s·•・/、,，\\-—_]+")

    /**
     * 抽取展示标签：优先语言，其次画质 / 版本。没有可识别的标注返回 null。
     * 「沙丘[国语中字]」→「国语」；「沙丘[4K]」→「4k」；「沙丘[电影解说]」→ null。
     */
    fun of(title: String): String? {
        BRACKET.findAll(title).forEach { match ->
            val tokens = tokensOf(match.groupValues[1])
            if (tokens.isEmpty()) return@forEach
            tokens.firstOrNull { it in LANGUAGES }?.let { return it }
            tokens.firstOrNull { it in VERSIONS }?.let { return it }
            return tokens.first()
        }
        return suffixOf(title)
    }

    /**
     * 不带括号的尾部标注：「功夫粤语」→「粤语」、「功夫[4K]国语」→「国语」。
     *
     * 采集源有一半不写括号，直接把语言缀在片名后面。只认尾部：中段出现的「粤语」
     * 多半是片名的一部分（「功夫热粤语」的片名是「功夫热」）。
     */
    private fun suffixOf(title: String): String? {
        var rest = title.trim().lowercase()
        var found: String? = null
        while (true) {
            val hit = VOCAB.firstOrNull { rest.endsWith(it) && rest.length > it.length } ?: break
            // 语言优先于画质，和括号内的取值口径保持一致。
            if (found == null || (hit in LANGUAGES && found !in LANGUAGES)) found = hit
            rest = rest.dropLast(hit.length)
        }
        return found
    }

    /**
     * 把片名尾部不带括号的版本标注剥掉：「沙丘粤语」→「沙丘」、「功夫热粤语」→「功夫热」。
     *
     * 这一步是给 [com.starpick.app.core.model.coreNameOf] 用的。括号里的标注那里已经
     * 整段删了，但「功夫粤语」这种写法会让核心名变成「功夫粤语」，和锚点「功夫」
     * 只能走"包含"判低分——而它其实就是《功夫》的粤语版，理应完全一致。
     */
    fun stripSuffix(title: String): String {
        var s = title.trim()
        while (true) {
            val hit = VOCAB.firstOrNull { s.lowercase().endsWith(it) && s.length > it.length } ?: break
            s = s.dropLast(hit.length).trim()
        }
        return s
    }

    /**
     * 把纯标注括号整段删掉，用于分组键。非标注括号原样保留。
     * 「沙丘[粤语]」→「沙丘」；「沙丘[电影解说]」→「沙丘[电影解说]」（保留，交给规则否决）。
     */
    fun strip(title: String): String {
        if (!title.contains('[') && !title.contains('(') &&
            !title.contains('【') && !title.contains('（')
        ) {
            return title
        }
        return BRACKET.replace(title) { match ->
            if (tokensOf(match.groupValues[1]).isEmpty()) match.value else ""
        }
    }

    /** 括号内容能否被标注词表完整切分；切不完说明混进了片名信息，返回空。 */
    private fun tokensOf(inner: String): List<String> {
        var rest = inner.lowercase().replace(NOISE, "")
        if (rest.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        while (rest.isNotEmpty()) {
            val hit = VOCAB.firstOrNull { rest.startsWith(it) } ?: return emptyList()
            out += hit
            rest = rest.removePrefix(hit)
        }
        return out
    }
}
