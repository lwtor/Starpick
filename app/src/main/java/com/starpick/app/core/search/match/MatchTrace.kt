package com.starpick.app.core.search.match

import com.starpick.app.core.model.MatchAnchor
import com.starpick.app.core.model.MediaTag
import com.starpick.app.core.model.SearchGroup
import com.starpick.app.core.model.VodItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 匹配诊断日志：把「一次匹配为什么是这个结果」完整记下来，供用户一键复制反馈。
 *
 * 界面上只会说「没在源里找到这部片」，但排查时需要知道到底哪一步断了：
 * 搜索词写错？某个源零结果？还是候选被年份/季号/衍生内容规则否掉了。
 * 所以这里记录四层信息：搜索词与变体 → 每个源返回几条 → 每个候选的裁决 → 最终选组。
 *
 * 实现取舍：
 *  - **环形缓冲，最近 [MAX_SESSIONS] 次会话**。写文件要处理权限、清理、并发，
 *    而反馈场景只需要「刚才那几次」，内存里存着就够，App 重启即清空。
 *  - **嵌套安全**：详情页 → 聚合搜索是嵌套调用，用 depth 计数合并成一次会话，
 *    否则外层锚点信息会被内层搜索覆盖。
 *  - 线程安全用对象锁：写入点来自多个并发源的协程，但每次只追加几行，开销可忽略。
 *  - 时间格式化用了 `java.text`，这层是纯 JVM 而非 Android 专有；将来搬 KMP 时
 *    换成 expect/actual 的实现即可，不影响其余逻辑。
 */
object MatchTrace {

    private const val MAX_SESSIONS = 8
    private const val MAX_LINES = 600
    private const val NAME_WIDTH = 34
    private const val SCORE_WIDTH = 7

    private val lock = Any()
    private val history = ArrayDeque<String>()
    private val buffer = StringBuilder()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var depth = 0
    private var lineCount = 0
    private var truncated = false

    /** 开一次会话。嵌套调用只写一条子标题，不覆盖外层。 */
    fun begin(header: String) {
        synchronized(lock) {
            if (depth == 0) {
                buffer.setLength(0)
                lineCount = 0
                truncated = false
                buffer.appendLine("━━━ $header  ${clock.format(Date())} ━━━")
            } else {
                append("· $header")
            }
            depth++
        }
    }

    fun end() {
        synchronized(lock) {
            if (depth <= 0) return
            depth--
            if (depth > 0) return
            if (truncated) buffer.appendLine("…（日志超过 $MAX_LINES 行，后续已截断）")
            history.addLast(buffer.toString())
            while (history.size > MAX_SESSIONS) history.removeFirst()
        }
    }

    /** 写任意一行上下文。 */
    fun note(text: String) {
        synchronized(lock) {
            if (depth == 0) return
            append(text)
        }
    }

    /** 记录锚点：查出片名、年份、原名、季号，是判断「匹配错了」的第一手依据。 */
    fun anchor(anchor: MatchAnchor) {
        note(
            "锚点: ${anchor.title}  年=${anchor.year.ifBlank { "-" }}" +
                "  原名=${anchor.originalTitle.ifBlank { "-" }}" +
                "  季=${anchor.season ?: "-"}",
        )
    }

    /** 记录实际使用的搜索词与补搜变体。 */
    fun keywords(primary: String, variants: List<String>) {
        note("搜索词: $primary")
        if (variants.isNotEmpty()) note("补搜变体: ${variants.joinToString(" | ")}")
    }

    /** 记录单个源的返回情况。 */
    fun source(siteName: String, word: String, count: Int, failed: Boolean) {
        synchronized(lock) {
            if (depth == 0) return
            val tail = when {
                failed -> "请求失败"
                count == 0 -> "0 条"
                else -> "$count 条"
            }
            append("[$siteName] 「$word」→ $tail")
        }
    }

    /** 记录单个候选的裁决结果。 */
    fun candidate(item: VodItem, result: MatchResult) {
        synchronized(lock) {
            if (depth == 0) return
            // ✓ 进源列表、✗ 被规则否决、△ 规则都过了但分数不够（沾边太弱）
            val mark = when {
                result.accepted -> "✓"
                result.weak -> "△"
                else -> "✗"
            }
            val name = pad("$mark ${item.title}", NAME_WIDTH)
            val score = pad("${result.score} 分", SCORE_WIDTH)
            val detail = when {
                result.accepted -> {
                    result.bonuses.joinToString(", ").ifEmpty { "通过" } +
                        (result.tag?.let { "  {$it}" } ?: "") +
                        "  @${item.sourceName}"
                }
                result.weak -> {
                    "分数不足（需 ≥ $MIN_ACCEPT_SCORE）：" +
                        result.bonuses.joinToString(", ").ifEmpty { "-" } +
                        "  @${item.sourceName}"
                }
                else -> result.rejectedBy ?: "未通过"
            }
            append("  $name$score$detail")
        }
    }

    /** 记录组级决策：哪些组参与竞争、各自最高分、最后选了谁。 */
    fun decision(scored: List<Pair<SearchGroup, Int>>, chosen: SearchGroup?) {
        synchronized(lock) {
            if (depth == 0) return
            if (scored.isEmpty()) {
                append("决策: 没有任何组通过，全部候选被否决")
                return
            }
            append("决策: ${scored.size} 组参与竞争")
            scored.forEachIndexed { index, (group, score) ->
                val picked = if (group === chosen || group.title == chosen?.title) " ← 选中" else ""
                // 组名去掉语言 / 画质标注，否则「沙丘[粤语]」会让人误以为只匹配到粤语版。
                val label = MediaTag.strip(group.title)
                append("  组${index + 1} 「$label」${group.items.size} 条  最高 $score 分$picked")
            }
        }
    }

    /** 会话收尾：最终进入详情页的源数量。 */
    fun result(sourceCount: Int) {
        note(if (sourceCount > 0) "结果: $sourceCount 个源进入详情页" else "结果: 0 个源，详情页只能显示豆瓣资料")
    }

    /** 最近一次会话。 */
    fun latest(): String = synchronized(lock) { history.lastOrNull() ?: buffer.toString() }

    /** 全部会话，最新在后。用户反馈时整段复制即可。 */
    fun all(): String = synchronized(lock) {
        if (history.isEmpty()) return ""
        history.joinToString("\n")
    }

    fun isEmpty(): Boolean = synchronized(lock) { history.isEmpty() && buffer.isEmpty() }

    fun clear() {
        synchronized(lock) {
            history.clear()
            buffer.setLength(0)
            depth = 0
            lineCount = 0
            truncated = false
        }
    }

    private fun append(text: String) {
        if (lineCount >= MAX_LINES) {
            truncated = true
            return
        }
        buffer.appendLine(text)
        lineCount++
    }

    /** 中文占两格，按显示宽度对齐，否则候选列表会歪得没法看。 */
    private fun pad(text: String, width: Int): String {
        val current = text.sumOf { if (it.code > 0x2E80) 2 else 1 }
        return if (current >= width) "$text " else text + " ".repeat(width - current)
    }
}
