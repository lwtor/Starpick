package com.starpick.app.core.api

import com.starpick.app.core.model.SourceSite
import com.starpick.app.core.model.VodItem
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 苹果 CMS V10 Provide 接口的最小实现。
 *
 * 只依赖 Ktor 的通用 API，engine 由平台层注入，因此这一层不碰 Android SDK。
 * 采集站返回的字段类型很不稳定（vod_id 有时是数字、有时是字符串，list 有时包在 data 里），
 * 所以这里不做 @Serializable 映射，统一用 JsonElement 宽松读取，避免整页解析失败。
 */
class CmsV10Api(private val client: HttpClient) {

    suspend fun search(site: SourceSite, keyword: String, page: Int = 1): List<VodItem> =
        searchPage(site, keyword, page).items

    /**
     * 带分页信息的搜索。苹果 CMS 的响应里本来就有 `total` / `pagecount`，之前只取了 list，
     * 导致上层只能靠「返回空」猜到底——空页夹在中间会误判，刚好凑整页又要多请求一次才知道。
     */
    suspend fun searchPage(site: SourceSite, keyword: String, page: Int = 1): CmsPage = fetch(
        site,
        mapOf(
            "ac" to "videolist",
            "wd" to keyword,
            "pg" to page.toString(),
        ),
    )

    /** 部分源在搜索结果里不返回播放地址，需要按 ids 再取一次详情。 */
    suspend fun detail(site: SourceSite, vodId: String): VodItem? = fetch(
        site,
        mapOf("ac" to "detail", "ids" to vodId),
    ).items.firstOrNull()

    /**
     * 源设置页的探活。
     *
     * 用 `ac=list` 而不是 `ac=videolist`：后者一次返回 200~700KB（实测量子源要 16 秒），
     * 手机上经常还没下完就超时，于是「能用」的源被误判成「不可用」。
     * `ac=list` 只回一页目录（约 7~14KB，实测 0.5~2.6 秒）。
     *
     * 超时/网络异常不再等同于「不可用」：那是「测不准」，重试一次仍失败才报 UNKNOWN。
     */
    suspend fun probe(site: SourceSite): SourceHealth {
        var unknown = false
        repeat(2) { attempt ->
            val result = runCatching {
                withTimeout(PING_TIMEOUT_MS) { requestHealth(site) }
            }.getOrNull() ?: SourceHealth.UNKNOWN
            if (result != SourceHealth.UNKNOWN) return result
            unknown = true
            // 第一次就超时可能是冷启动/弱网，给一次重试；不额外退避，避免页面卡住。
            if (attempt == 0) delay(300)
        }
        return if (unknown) SourceHealth.UNKNOWN else SourceHealth.OFFLINE
    }

    private suspend fun requestHealth(site: SourceSite): SourceHealth {
        val response = client.get(endpoint(site.baseUrl)) {
            url {
                parameters.append("ac", "list")
                parameters.append("pg", "1")
            }
            headers.append("User-Agent", USER_AGENT)
        }
        if (response.status.value !in 200..299) return SourceHealth.OFFLINE
        val body = response.bodyAsText().trimStart()
        // 正常的苹果 CMS 会回 {"code":1,...,"list":[...]}；只要拿到 JSON 就算线路是通的，
        // 个别源把结果包在 data 里或临时把 list 置空，也不该判成不可用。
        return when {
            body.contains("\"list\"") || body.contains("\"data\"") -> SourceHealth.ONLINE
            body.startsWith("{") -> SourceHealth.ONLINE
            else -> SourceHealth.OFFLINE
        }
    }

    private suspend fun fetch(site: SourceSite, params: Map<String, String>): CmsPage {
        val response = try {
            client.get(endpoint(site.baseUrl)) {
                url {
                    params.forEach { (key, value) -> parameters.append(key, value) }
                }
                // 防盗链的 Referer / UA 后续统一放到平台层的 engine 拦截器里，这里先给一个通用 UA。
                headers.append("User-Agent", USER_AGENT)
            }
        } catch (error: Exception) {
            // 源站超时/断连/证书异常等一律当作无结果，绝不让网络异常把上层带崩。
            return CmsPage()
        }
        if (response.status.value !in 200..299) return CmsPage()

        val body = response.bodyAsText()
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return CmsPage()
        val array = root["list"] as? JsonArray
            ?: root["data"] as? JsonArray
            ?: return CmsPage()

        return CmsPage(
            items = array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                parseItem(obj, site)
            },
            total = root.int("total", "count"),
            pageCount = root.int("pagecount", "page_count", "pagecnt"),
        )
    }

    private fun JsonObject.int(vararg names: String): Int {
        for (name in names) {
            val raw = get(name).asText().trim()
            // 少数改版源站会回 2.0 这样的浮点数，先按整数读再退到浮点。
            val value = raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
            if (value != null && value >= 0) return value
        }
        return 0
    }

    private fun parseItem(obj: JsonObject, site: SourceSite): VodItem? {
        val title = obj.text("vod_name", "name")
        if (title.isBlank()) return null
        val id = obj.text("vod_id", "id").ifBlank { title }
        return VodItem(
            sourceId = site.id,
            sourceName = site.name,
            vodId = id,
            title = title,
            cover = absolutize(site.baseUrl, obj.text("vod_pic", "vod_pic_thumb", "pic")),
            remark = obj.text("vod_remarks", "vod_remark"),
            category = obj.text("vod_class", "type_name"),
            area = obj.text("vod_area"),
            year = obj.text("vod_year"),
            description = obj.text("vod_content", "vod_blurb"),
            playFrom = obj.text("vod_play_from"),
            playUrl = obj.text("vod_play_url"),
        )
    }

    /** 用户可能填站点根、api.php 或完整 provide 路径，三种都收。 */
    private fun endpoint(baseUrl: String): String {
        val raw = baseUrl.trim().substringBefore("?").trimEnd('/')
        return when {
            raw.contains("/provide/vod") -> raw
            raw.endsWith("/api.php") -> "$raw/provide/vod"
            else -> "$raw/api.php/provide/vod"
        }
    }

    /** 封面常给相对路径，按站点 origin 补全。 */
    private fun absolutize(baseUrl: String, value: String): String {
        val v = value.trim()
        if (v.isEmpty()) return ""
        if (v.startsWith("http://", true) || v.startsWith("https://", true)) return v
        val origin = baseUrl.trim().substringBefore("/api.php").trimEnd('/')
        return if (v.startsWith("/")) origin + v else "$origin/$v"
    }

    private fun JsonObject.text(vararg names: String): String {
        for (name in names) {
            val value = get(name).asText()
            if (value.isNotBlank()) return value.trim()
        }
        return ""
    }

    private fun JsonElement?.asText(): String = when (this) {
        null -> ""
        is JsonNull -> ""
        is JsonPrimitive -> content
        else -> ""
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) Starpick/0.1"

        /** 单次探活上限。弱网/海外线路首包可能好几秒，6 秒太紧会被误判成不可用。 */
        private const val PING_TIMEOUT_MS = 12_000L
    }
}

/** 一页采集站结果，附带源站自己声明的总条数 / 总页数（0 表示源站没给）。 */
data class CmsPage(
    val items: List<VodItem> = emptyList(),
    val total: Int = 0,
    val pageCount: Int = 0,
)

/**
 * 采集源的健康状态。
 *
 * 三态而不是布尔：超时/网络异常属于「测不准」，不能等同于「源站不可用」——
 * 线上好几个源就是在弱网下被误判成红色「不可用」的。
 */
enum class SourceHealth {
    /** 拿到了正常的苹果 CMS 响应。 */
    ONLINE,

    /** 明确失败：HTTP 4xx/5xx，或返回内容根本不是接口数据。 */
    OFFLINE,

    /** 超时/断网/DNS 抖动，无法下结论。 */
    UNKNOWN,
}
