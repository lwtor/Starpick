package com.starpick.app.core.search

import com.starpick.app.core.model.searchNameOf

/**
 * 搜索词变体。
 *
 * 苹果 CMS V10 的搜索是对 `vod_name` 做子串匹配，写法的细微差异就会 0 结果。
 * 实测最常见的三种落空：
 *  - 间隔号：用户搜「哈利波特」，采集站片名写作「哈利·波特」，ffzyapi / dyttzyapi 直接返回空，
 *    而 lziapi 库里两种写法都有才碰巧能搜到。
 *  - 季号：豆瓣条目叫「某某 第一季」，采集站片名只写「某某」。
 *  - 译名 / 原名：豆瓣用译名，采集站用原名。
 *
 * 这里只生成**低噪音**变体，刻意不做「截短关键词」那类模糊化——
 * 截短会灌进大量无关片（搜「哈利」出来「哈利法克斯最后的探戈」），
 * 反而把真正想找的片子淹掉。变体只在原词所有源都零结果时才启用。
 */
object SearchTerms {

    /** 间隔号、句点、破折号、冒号、斜杠、空白——采集站片名里常见的分词符。 */
    private val SEPARATORS = Regex("[\\s·•・‧.．\\-—_:：/\\\\]+")

    /** 补搜词上限：长片名逐字插点会让请求数膨胀到几十个，而全都落在失败路径上。 */
    private const val MAX_VARIANTS = 6

    /** 间隔号变体只取前几个插入点。片名通常很短，先试靠前的位置命中率最高。 */
    private const val MAX_INTERLEAVE = 4

    private fun isCjk(c: Char) = c in '\u3400'..'\u9fff'

    /**
     * 按优先级返回候选词，第一个永远是原词。
     *
     * 1. 原词：「某某 第一季」
     * 2. 剥掉季号 / 年份 / 方括号：「某某」
     * 3. 原名 / 别名：[aliases] 逐条剥同样的后缀
     * 4. 去分隔符：「哈利·波特」→「哈利波特」
     * 5. 汉字间插间隔号：「哈利波特」→「哈利·波特」（取靠前的几个插入点）
     *
     * 第 5 类保留多个插入点而不是猜某一个：间隔号位置没有可靠规则，
     * 但片名通常很短，枚举成本可控，且全部并发、谁命中谁知道。
     */
    fun variants(keyword: String, aliases: List<String> = emptyList()): List<String> {
        val query = keyword.trim()
        if (query.isEmpty()) return emptyList()

        val out = LinkedHashSet<String>()
        out.add(query)

        // 采集站是子串匹配，剥掉后缀后的宽词对「带后缀的片名」仍然命中（短串是长串的子串），
        // 所以这一步只会扩大命中面。
        val bare = searchNameOf(query).ifBlank { query }
        if (bare != query) out.add(bare)

        aliases.forEach { alias ->
            val normalized = searchNameOf(alias.trim()).ifBlank { alias.trim() }
            if (normalized.isNotEmpty() && normalized != query) out.add(normalized)
        }

        val compact = SEPARATORS.replace(bare, "")
        if (compact.isNotEmpty() && compact != query && compact != bare) out.add(compact)

        // 只在「紧凑词」上插间隔号，且只插在相邻两个汉字之间：
        // 给英文单词 / 数字内部插点是纯噪音（"transform·ers" 之类永远搜不到）。
        if (compact.length in 2..16) {
            var added = 0
            for (i in 1 until compact.length) {
                if (added >= MAX_INTERLEAVE || out.size >= MAX_VARIANTS) break
                if (isCjk(compact[i - 1]) && isCjk(compact[i])) {
                    out.add(compact.substring(0, i) + "·" + compact.substring(i))
                    added++
                }
            }
        }
        return out.toList()
    }
}
