package com.starpick.app.core.search.match

import com.starpick.app.core.model.MatchAnchor
import com.starpick.app.core.model.VodItem

/**
 * 匹配规则的执行管线。
 *
 * 按 [default] 的**列表顺序**依次求值：任意一条 [Verdict.Reject] 立即短路返回 0，
 * [Verdict.Bonus] 累加，[Verdict.Tag] 收集展示标签。
 *
 * 顺序有语义，不要随意调整：[DerivativeRule] 必须在 [CoreNameRule] 之前，
 * 否则「越狱兔[电影解说]」会先靠核心名拿到 6 分，日志里看起来像正常命中被莫名其妙否决。
 */
object MatchPipeline {

    val default: List<MatchRule> = listOf(
        DerivativeRule,
        YearConflictRule,
        SeasonConflictRule,
        // 放在片名规则之前：减分不依赖顺序（不会被短路），位置只影响日志可读性——
        // 先报「候选多标了季号」这个事实，再看片名给了几分，一眼能看出分是被谁拉低的。
        SeasonUntaggedRule,
        CoreNameRule,
        OriginalTitleRule,
        YearMatchRule,
        SeasonMatchRule,
        LanguageVariantRule,
    )

    /**
     * 判定单个候选。
     *
     * [rules] 可替换，用于后续「某条规则出问题先临时关掉」这类排查，不用改代码结构。
     */
    fun judge(
        anchor: MatchAnchor,
        item: VodItem,
        rules: List<MatchRule> = default,
    ): MatchResult {
        val ctx = MatchContext(anchor, item)
        var score = 0
        var tag: String? = null
        val bonuses = mutableListOf<String>()

        for (rule in rules) {
            when (val verdict = rule.evaluate(ctx)) {
                Verdict.Pass -> Unit
                is Verdict.Bonus -> {
                    score += verdict.points
                    bonuses += "${verdict.reason}+${verdict.points}"
                }
                is Verdict.Reject -> return MatchResult(
                    score = 0,
                    rejectedBy = "${rule.name}：${verdict.reason}",
                    bonuses = bonuses,
                    tag = tag,
                )
                is Verdict.Tag -> tag = verdict.label
            }
        }
        return MatchResult(score = score, bonuses = bonuses, tag = tag)
    }
}
