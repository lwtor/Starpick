package com.starpick.app.core.search.match

import kotlin.math.abs

/**
 * 一条匹配规则。
 *
 * 新增规则只需要实现这个接口并挂到 [MatchPipeline.default] 列表里，
 * 不用改任何既有规则——包括它们的先后顺序，那是列表的事。
 */
interface MatchRule {
    /** 稳定标识，进日志、将来可接设置页开关。 */
    val id: String

    /** 中文名，直接用于「为什么这条没匹配上」的提示。 */
    val name: String

    fun evaluate(ctx: MatchContext): Verdict
}

/** 解说 / 预告这类衍生内容：与正片同源同名，但不是同一部片。 */
private val DERIVATIVE_TAG = Regex(
    "解说|预告|花絮|速看|片段|彩蛋|特辑|混剪|影评|解析|盘点|reaction|trailer",
    RegexOption.IGNORE_CASE,
)

/**
 * 衍生内容排除。
 *
 * 这是核心名归一的盲区：`coreNameOf` 会把方括号连内容整体删掉，
 * 「越狱兔[电影解说]」的核心名因此等于「越狱兔」，和正片拿同一个 6 分，
 * 再被「同分取源多者」选中——解说类往往比正片铺得更广，于是稳定抢位。
 *
 * 「合集」刻意不算：某某合集通常就是正片全集，仍然可播。
 * 锚点本身带衍生标注时不排除，那是用户主动找解说。
 */
object DerivativeRule : MatchRule {
    override val id = "derivative"
    override val name = "衍生内容排除"

    override fun evaluate(ctx: MatchContext): Verdict {
        val hit = DERIVATIVE_TAG.containsMatchIn(ctx.itemTitle)
        if (!hit) return Verdict.Pass
        if (DERIVATIVE_TAG.containsMatchIn(ctx.anchorTitle)) return Verdict.Pass
        return Verdict.Reject("候选是解说/预告等衍生内容")
    }
}

/**
 * 年份冲突：两边都拿到年份且相差超过 1 年 → 不同作品（翻拍 / 同名）。
 *
 * 容差 1 年是有意留的：采集站常把首播年写成上线年，收紧到 0 会误杀大量正常源。
 */
object YearConflictRule : MatchRule {
    override val id = "year-conflict"
    override val name = "年份冲突"

    override fun evaluate(ctx: MatchContext): Verdict {
        val a = ctx.anchorYear ?: return Verdict.Pass
        val b = ctx.itemYear ?: return Verdict.Pass
        val gap = abs(a - b)
        return if (gap > 1) Verdict.Reject("年份差 $gap 年（$a vs $b）") else Verdict.Pass
    }
}

/**
 * 季号冲突：两边都明确解析出季号且不等 → 不同季。
 *
 * 只在一方缺季号时放行，因为大量采集源不分季，硬拦会让这些源直接找不到片。
 */
object SeasonConflictRule : MatchRule {
    override val id = "season-conflict"
    override val name = "季号冲突"

    override fun evaluate(ctx: MatchContext): Verdict {
        val a = ctx.anchorSeason ?: return Verdict.Pass
        val b = ctx.itemSeason ?: return Verdict.Pass
        return if (a != b) Verdict.Reject("季号不符（第${a}季 vs 第${b}季）") else Verdict.Pass
    }
}

/**
 * 片名核心比对：完全相等 6 分，一方包含另一方按可辨识度分三档，其余否决。
 *
 * 包含关系给多少分，取决于**短的那个片名有多长**——这是它携带多少信息的直接度量：
 *  - 4 字以上（「灌篮高手」⊂「灌篮高手 THE FIRST」）3 分，够格进源列表；
 *  - 3 字（「越狱兔」⊂「越狱二人组」）2 分，能进候选但过不了门槛；
 *  - 2 字以内（「功夫」⊂「功夫熊猫」/「石家庄功夫」/「废土世界，这里的僵尸会功夫」）1 分。
 *
 * 短名那一档是这次串片的主因：采集站里带「功夫」两个字的条目一口气几十条，
 * 全是足球赛事、古装剧、解说，一律 2 分放行的话详情页就成了噪音列表。
 */
object CoreNameRule : MatchRule {
    override val id = "core-name"
    override val name = "片名核心比对"

    override fun evaluate(ctx: MatchContext): Verdict {
        val a = ctx.anchorCore
        val b = ctx.itemCore
        if (a.isEmpty() || b.isEmpty()) return Verdict.Reject("片名为空")
        if (a == b) return Verdict.Bonus(6, "片名完全一致")
        if (a in b || b in a) {
            return when (minOf(a.length, b.length)) {
                in 4..Int.MAX_VALUE -> Verdict.Bonus(3, "片名包含（长名，可信）")
                3 -> Verdict.Bonus(2, "片名包含（3 字，偏弱）")
                else -> Verdict.Bonus(1, "片名包含（2 字以内，几乎必是噪音）")
            }
        }
        // 译名对不上时用原名兜底。
        // 之前这条路径是断的：核心名不符直接否决，原名加分永远轮不到执行，
        // 于是「豆瓣用译名、采集站用原名」这类片一律匹配失败。
        return if (ctx.originalMatched) {
            Verdict.Bonus(2, "原名兜底命中")
        } else {
            Verdict.Reject("片名不符")
        }
    }
}

/**
 * 锚点没标季号、候选却标了 → 大幅降分。
 *
 * 豆瓣把电影叫《功夫》，采集站里那部剧叫《功夫第一季》。两者核心名都归一成「功夫」，
 * 原本同拿 6 分，然后同分按"源数多者优先"，剧的条目顺序靠前就赢了电影——
 * 这正是「点电影进去变成一部剧」的直接原因。电影不可能有季号，所以这个方向可靠。
 *
 * 只罚单向。锚点标季、候选不标是常态（大量采集源根本不区分季号），
 * 罚它会让这些源直接消失，那是拿"找不到"换"不串片"，得不偿失。
 */
object SeasonUntaggedRule : MatchRule {
    override val id = "season-untagged"
    override val name = "候选多标了季号"

    override fun evaluate(ctx: MatchContext): Verdict {
        if (ctx.anchorSeason != null) return Verdict.Pass
        val season = ctx.itemSeason ?: return Verdict.Pass
        return Verdict.Bonus(-4, "候选标了第${season}季，锚点无季号")
    }
}

/**
 * 原名 / 别名命中 +2。
 *
 * 豆瓣用译名、采集站用原名是常态；有这条才能在译名对不上时把原名条目捞回来。
 */
object OriginalTitleRule : MatchRule {
    override val id = "original-title"
    override val name = "原名命中"

    override fun evaluate(ctx: MatchContext): Verdict {
        val original = ctx.originalCore
        if (original.length < 2) return Verdict.Pass
        val b = ctx.itemCore
        val hit = original == b || original in b || b in original
        return if (hit) Verdict.Bonus(2, "原名命中") else Verdict.Pass
    }
}

/** 年份一致 +1：在没被年份冲突否决的前提下，同年的候选更可信。 */
object YearMatchRule : MatchRule {
    override val id = "year-match"
    override val name = "年份一致"

    override fun evaluate(ctx: MatchContext): Verdict {
        val a = ctx.anchorYear ?: return Verdict.Pass
        val b = ctx.itemYear ?: return Verdict.Pass
        return if (a == b) Verdict.Bonus(1, "年份一致") else Verdict.Pass
    }
}

/** 季号一致 +1：同系列多季并存时，把对应那一季排到前面。 */
object SeasonMatchRule : MatchRule {
    override val id = "season-match"
    override val name = "季号一致"

    override fun evaluate(ctx: MatchContext): Verdict {
        val a = ctx.anchorSeason ?: return Verdict.Pass
        val b = ctx.itemSeason ?: return Verdict.Pass
        return if (a == b) Verdict.Bonus(1, "季号一致") else Verdict.Pass
    }
}

/**
 * 语言 / 画质版本标签。
 *
 * 只打标签不改分数：同一个源下的「粤语」「国语」「英文」是并列可选项，
 * 没有哪个更「正确」，所以它们必须同分同组、各占一条源，由用户自己挑。
 */
object LanguageVariantRule : MatchRule {
    override val id = "language-variant"
    override val name = "语言/版本标签"

    override fun evaluate(ctx: MatchContext): Verdict {
        val tag = ctx.itemTag ?: return Verdict.Pass
        return Verdict.Tag(tag)
    }
}
