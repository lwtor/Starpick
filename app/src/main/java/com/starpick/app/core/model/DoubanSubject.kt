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
