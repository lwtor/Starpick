package com.starpick.app.core.parser

import com.starpick.app.core.model.Episode
import com.starpick.app.core.model.PlayGroup
import com.starpick.app.core.model.contentKeyOf

/**
 * 苹果 CMS V10 的播放串解析：
 *   线路之间用 $$$ 分隔（不同源也会用 $$ 或 ###）
 *   集之间用 # 分隔
 *   集名与地址之间用 $ 分隔
 *   playFrom 与 playUrl 的线路块按顺序一一对应
 */
object PlaylistParser {

    private val BLOCK_SEPARATORS = listOf("\$\$\$", "\$\$", "###")

    fun parse(playFrom: String, playUrl: String): List<PlayGroup> {
        val urlBlocks = splitBlocks(playUrl)
        if (urlBlocks.isEmpty()) return emptyList()
        val nameBlocks = splitBlocks(playFrom)

        return urlBlocks.mapIndexed { index, block ->
            val from = nameBlocks.getOrNull(index)?.takeIf { it.isNotBlank() }
                ?: "线路 ${index + 1}"
            val episodes = block.split("#")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapIndexed { epIndex, segment -> splitEpisode(segment, epIndex) }
                .filter { it.url.isNotBlank() }
            PlayGroup(from, episodes)
        }.filter { it.episodes.isNotEmpty() }
    }

    private fun splitBlocks(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        for (separator in BLOCK_SEPARATORS) {
            if (text.contains(separator)) {
                return text.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        return listOf(text)
    }

    private fun splitEpisode(segment: String, index: Int): Episode {
        val mark = segment.indexOf('$')
        if (mark < 0) {
            return Episode("第 ${index + 1} 集", segment.trim())
        }
        val name = segment.substring(0, mark).trim().ifBlank { "第 ${index + 1} 集" }
        val url = segment.substring(mark + 1).trim()
        return Episode(name, url)
    }

    /**
     * 首选线路：采集站常把「分享页」线路排在第一条（`/share/<hash>`，返回网页或藏了扩展名的流），
     * 真正的 `.../index.m3u8` 往往在后面。默认播第 0 条就会一直转圈/解析失败，
     * 所以优先挑一条「地址像视频流」的线路；都不像时退回第 0 条。
     */
    fun preferredGroupIndex(groups: List<PlayGroup>): Int {
        val direct = groups.indexOfFirst { group ->
            group.episodes.any { StreamUrl.looksDirect(it.url) }
        }
        return if (direct >= 0) direct else 0
    }

    /** 聚合搜索时的标题归一：去掉空白、标点与常见分隔符。 */
    /** 搜索结果的分组键，与观看历史的归并键保持一致。 */
    fun normalizeTitle(title: String): String = contentKeyOf(title)
}

/**
 * 判断一个播放地址是不是「一眼能看出是视频」的直链。
 *
 * 采集站给的地址有两种：带扩展名的直链，以及扩展名被藏起来的 `/share/<hash>`。
 * 后者要么是网页、要么是伪装成普通路径的 HLS，播放器按扩展名判断都会出错。
 */
object StreamUrl {

    private val DIRECT_EXTENSION = Regex(
        "\\.(m3u8|mp4|flv|mkv|ts|mov|m4v)([?#]|$)",
        RegexOption.IGNORE_CASE,
    )

    fun looksDirect(url: String): Boolean = DIRECT_EXTENSION.containsMatchIn(url)
}

/** 这条线路里有没有「一眼像视频流」的地址。 */
fun PlayGroup.isDirect(): Boolean = episodes.any { StreamUrl.looksDirect(it.url) }
