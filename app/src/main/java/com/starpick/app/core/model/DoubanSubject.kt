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
)

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
