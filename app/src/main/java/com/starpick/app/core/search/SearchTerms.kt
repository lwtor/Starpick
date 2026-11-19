package com.starpick.app.core.search

/**
 * 搜索词变体。
 *
 * 苹果 CMS V10 的搜索是对 `vod_name` 做子串匹配，写法的细微差异就会 0 结果。
 * 实测最典型的是间隔号：用户搜「哈利波特」，采集站片名写作「哈利·波特」，
 * ffzyapi / dyttzyapi 直接返回空；而 lziapi 库里两种写法都有才碰巧能搜到。
 *
 * 这里只生成几种**低噪音**变体，刻意不做「截短关键词」那类模糊化——
 * 截短会灌进大量无关片（搜「哈利」出来「哈利法克斯最后的探戈」），
 * 反而把真正想找的片子淹掉。变体只在原词所有源都零结果时才启用。
 */
object SearchTerms {

    /** 间隔号、句点、破折号、冒号、斜杠、空白——采集站片名里常见的分词符。 */
    private val SEPARATORS = Regex("[\\s·•・‧.．\\-—_:：/\\\\]+")

    private fun isCjk(c: Char) = c in '\u3400'..'\u9fff'

    /**
     * 按优先级返回候选词，第一个永远是原词。
     *
     * 1. 原词：「哈利波特」
     * 2. 去分隔符：「哈利·波特」→「哈利波特」
     * 3. 汉字之间插单个间隔号：「哈利波特」→「哈利·波特」等（每个插入点一个）
     *
     * 第 3 类保留了所有插入点而不是猜某一个：间隔号位置没有可靠规则，
     * 但片名通常很短，枚举的成本可控，且全部并发、谁命中谁知道。
     */
    fun variants(keyword: String): List<String> {
        val query = keyword.trim()
        if (query.isEmpty()) return emptyList()

        val out = LinkedHashSet<String>()
        out.add(query)

        val compact = SEPARATORS.replace(query, "")
        if (compact.isNotEmpty() && compact != query) out.add(compact)

        // 只在「紧凑词」上插间隔号，且只插在相邻两个汉字之间：
        // 给英文单词 / 数字内部插点是纯噪音（"transform·ers" 之类永远搜不到）。
        if (compact.length in 2..16) {
            for (i in 1 until compact.length) {
                if (isCjk(compact[i - 1]) && isCjk(compact[i])) {
                    out.add(compact.substring(0, i) + "·" + compact.substring(i))
                }
            }
        }
        return out.toList()
    }
}
