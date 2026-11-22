package com.starpick.app.core.search.match

import com.starpick.app.core.model.MatchAnchor
import com.starpick.app.core.model.MediaTag
import com.starpick.app.core.model.VodItem
import com.starpick.app.core.model.coreNameOf
import com.starpick.app.core.model.seasonNumberOf

/**
 * 一条规则对某个候选的裁决。
 *
 * 四态而不是布尔，是因为匹配需要表达三种不同强度的判断：
 *  - [Reject] 硬否决，管线立即短路，候选直接被剔出源列表；
 *  - [Bonus] 软加分，不终止，多个候选都通过时靠它排序；
 *  - [Tag] 放行并附带展示信息（「粤语」「高清」），源列表据此区分同一部片的多个版本。
 *
 * 布尔装不下 [Bonus] 和 [Tag]，这正是它比规约（Specification）更适合这里的原因：
 * 光靠「通过 / 不通过」既排不出优先级，也说不出「这条是粤语」。
 */
sealed interface Verdict {
    data object Pass : Verdict

    /** 通过并加分。[reason] 会进诊断日志，说明这条为什么排前面。 */
    data class Bonus(val points: Int, val reason: String) : Verdict

    /** 硬否决。[reason] 会进诊断日志，说明这条为什么被剔除。 */
    data class Reject(val reason: String) : Verdict

    /** 通过并打一个展示标签，不加分。 */
    data class Tag(val label: String) : Verdict
}

/**
 * 进入源列表所需的最低分。
 *
 * 定在 3 分是因为它正好卡在「有结构化佐证」与「纯片名沾边」之间：
 *  - 片名完全一致 6 ✓
 *  - 长片名包含 2 + 年份一致 1 = 3 ✓（「灌篮高手」vs「灌篮高手 THE FIRST」这类要靠它）
 *  - 纯包含 2、短名包含 1、以及「电影锚点撞上同名剧」6-4=2 ✗
 *
 * 前端那个「点 A 进 B」的投诉，绝大多数是 2 分以下的沾边条目被当成了可用源。
 * 门槛宁可漏掉几条，也不把噪音铺进详情页——漏了用户看得出「没找到」，
 * 噪音混进去用户只会以为匹配坏了。
 */
const val MIN_ACCEPT_SCORE = 3

/**
 * 一次完整判定的结果。
 *
 * [rejectedBy] 与 [bonuses] 是给诊断用的：界面上只说「没找到源」，
 * 但排查时必须知道是年份被否掉、季号不符，还是根本没搜到。
 */
data class MatchResult(
    val score: Int,
    val rejectedBy: String? = null,
    val bonuses: List<String> = emptyList(),
    val tag: String? = null,
) {
    val accepted: Boolean get() = score >= MIN_ACCEPT_SCORE

    /** 通过了所有否决规则，但分数不够进门槛——日志里要说清是"太弱"而不是"被规则拒"。 */
    val weak: Boolean get() = rejectedBy == null && score > 0 && !accepted
}

/**
 * 规则所需的输入，构造时预计算一次。
 *
 * 旧实现里 `anchorMatchScore` 每次调用要跑 2~3 次 `coreNameOf`，而 `pickGroup`
 * 又对每组每条各调一次，同一串片名被反复归一。这里算一次、所有规则共用。
 */
class MatchContext(val anchor: MatchAnchor, val item: VodItem) {
    val anchorTitle: String = anchor.title
    val itemTitle: String = item.title
    val anchorCore: String = coreNameOf(anchor.title)
    val itemCore: String = coreNameOf(item.title)
    val anchorYear: Int? = anchor.year.firstYear()
    val itemYear: Int? = item.year.firstYear()
    val anchorSeason: Int? = anchor.season
    val itemSeason: Int? = seasonNumberOf(item.title)
    val originalCore: String = coreNameOf(anchor.originalTitle)
    val itemTag: String? = MediaTag.of(item.title)

    /**
     * 原名是否命中候选片名。
     *
     * 预计算是因为它不只用于加分：片名核心对不上时（豆瓣译名 vs 采集站原名），
     * 这一条是唯一的救回路径，[CoreNameRule] 要靠它决定「否决」还是「放行」。
     */
    val originalMatched: Boolean = originalCore.length >= 2 &&
        (originalCore == itemCore || originalCore in itemCore || itemCore in originalCore)
}

/** 取字符串里第一个 4 位年份（19xx / 20xx）。采集站常给「2024」或「2024-01-01」。 */
internal fun String.firstYear(): Int? =
    Regex("(?:19|20)[0-9]{2}").find(this)?.value?.toIntOrNull()
