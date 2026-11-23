package com.starpick.app.core.model

/**
 * 豆瓣榜单条目（热门电影/电视剧）。数据来自豆瓣公开的 `j/search_subjects` 接口，
 * 只做「发现」用途：首页点开之后用片名到用户自己的 CMS 源里聚合搜索，再进入详情。
 * 纯 Kotlin，零 Android 依赖。
 */
data class DoubanSubject(
    val id: String,
    val title: String,
    val cover: String = "",
    val rate: String = "",
    val url: String = "",
    val playable: Boolean = false,
    val isNew: Boolean = false,
    val episodesInfo: String = "",
    /** 首播年份（suggest 接口的 year / abstract 的 release_year），跨源匹配的硬约束之一。 */
    val year: String = "",
    /** 原名 / 外文名（suggest 的 sub_title），用于「 mock 与华语名并存」时补强匹配。 */
    val originalTitle: String = "",
)

/**
 * 跨源匹配锚点：把豆瓣条目的结构化信息带到采集源结果比对，判断「源里的这条是不是豆瓣这部」。
 *
 * 豆瓣与采集站之间没有可 join 的唯一 ID，只能做实体消歧。仅靠片名不够——采集站搜索是
 * 模糊匹配，季号、年份、版本都可能对不上。这里把年份、季号、原名一并带上做多因子判定：
 * 片名核心名决定相似基线，年份/季号作为硬否决，原名作为加分项。
 *
 * 纯 Kotlin，零 Android 依赖，将来可整体搬进 KMP 的 commonMain。
 */
data class MatchAnchor(
    val title: String,
    val year: String = "",
    val originalTitle: String = "",
    /** 豆瓣条目 id。有它就按 id 取详情，页面资料不会被同名条目的模糊匹配换掉。 */
    val doubanId: String = "",
    /** 豆瓣海报。subject_abstract 不返回封面，带过来给详情页用。 */
    val cover: String = "",
) {
    /** 季号从片名解析；解析不出表示「不知第几季」，比对时不据此否决。 */
    val season: Int? = seasonNumberOf(title)

    /**
     * 拿去采集源搜索的词：剥掉季号 / 年份 / 方括号标注，正片名里的符号原样保留。
     * 搜索用宽词、筛选靠匹配管线的季号与年份否决，见 [searchNameOf]。
     */
    val searchKeyword: String get() = searchNameOf(title)

    companion object {
        fun from(subject: DoubanSubject) = MatchAnchor(
            title = subject.title,
            year = subject.year,
            originalTitle = subject.originalTitle,
            doubanId = subject.id,
            cover = subject.cover,
        )
    }
}

/**
 * 豆瓣影片详情。来自公开的 `j/subject_abstract`（评分 / 导演 / 演员 / 类型 / 地区 / 时长 / 短评）
 * 与 `j/subject_suggest`（海报 / 年份）两条公开 JSON，无需鉴权。纯 Kotlin，零 Android 依赖。
 * 所有字段都可为空 —— 任何一条失败都不影响详情页其余内容照常显示。
 */
data class DoubanDetail(
    val id: String = "",
    val title: String = "",
    val cover: String = "",
    val rate: String = "",
    val year: String = "",
    val subtype: String = "",
    val directors: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val duration: String = "",
    val region: String = "",
    val types: List<String> = emptyList(),
    val comment: String = "",
    val url: String = "",
)
