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

    /** 聚合搜索时的标题归一：去掉空白、标点与常见分隔符。 */
    /** 搜索结果的分组键，与观看历史的归并键保持一致。 */
    fun normalizeTitle(title: String): String = contentKeyOf(title)
}
