package com.starpick.app.core.api

import com.starpick.app.core.model.SourceSite
import com.starpick.app.core.model.VodItem
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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

    suspend fun search(site: SourceSite, keyword: String, page: Int = 1): List<VodItem> = fetch(
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
    ).firstOrNull()

    /** 源设置页的探活：只看 HTTP 状态和是否含 list 字段，不解析内容。 */
    suspend fun ping(site: SourceSite): Boolean = runCatching {
        withTimeout(PING_TIMEOUT_MS) {
            val response = client.get(endpoint(site.baseUrl)) {
                url {
                    parameters.append("ac", "videolist")
                    parameters.append("pg", "1")
                }
                headers.append("User-Agent", USER_AGENT)
            }
            response.status.value in 200..299 && response.bodyAsText().contains("\"list\"")
        }
    }.getOrDefault(false)

    private suspend fun fetch(site: SourceSite, params: Map<String, String>): List<VodItem> {
        val response = client.get(endpoint(site.baseUrl)) {
            url {
                params.forEach { (key, value) -> parameters.append(key, value) }
            }
            // 防盗链的 Referer / UA 后续统一放到平台层的 engine 拦截器里，这里先给一个通用 UA。
            headers.append("User-Agent", USER_AGENT)
        }
        if (response.status.value !in 200..299) return emptyList()

        val body = response.bodyAsText()
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()
        val array = root["list"] as? JsonArray
            ?: root["data"] as? JsonArray
            ?: return emptyList()

        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            parseItem(obj, site)
        }
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
        private const val PING_TIMEOUT_MS = 6_000L
    }
}
