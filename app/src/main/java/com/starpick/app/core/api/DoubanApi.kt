package com.starpick.app.core.api

import com.starpick.app.core.model.DoubanSubject
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 豆瓣热门榜单的只读客户端。只用于首页「发现」：
 * 拉取豆瓣公开榜单（电影 / 电视剧），拿到片名与海报，用户点开后再去自己的 CMS 源搜资源。
 *
 * 说明：豆瓣官方 V2 API（api.douban.com/v2/movie）已废弃、apikey 失效，
 * 这里走 movie.douban.com 的 `j/search_subjects` 公开 JSON，无需鉴权。
 * 纯 Kotlin，只依赖 Ktor 通用 API。
 */
class DoubanApi(private val client: HttpClient) {

    /** 拉取热门榜单。type=movie 电影 / tv 电视剧。 */
    suspend fun hot(type: String, pageLimit: Int = 50): List<DoubanSubject> = runCatching {
        withTimeout(TIMEOUT_MS) {
            val response = client.get("https://movie.douban.com/j/search_subjects") {
                url {
                    parameters.append("type", type)
                    parameters.append("tag", "热门")
                    parameters.append("page_limit", pageLimit.toString())
                    parameters.append("page_start", "0")
                    parameters.append("sort", "recommend")
                }
                headers.append("User-Agent", USER_AGENT)
                headers.append("Referer", "https://movie.douban.com/")
            }
            if (response.status.value !in 200..299) return@withTimeout emptyList()
            parse(response.bodyAsText())
        }
    }.getOrDefault(emptyList())

    private fun parse(body: String): List<DoubanSubject> {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()
        val array = root["subjects"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = obj.str("title")
            if (title.isBlank()) return@mapNotNull null
            DoubanSubject(
                id = obj.str("id"),
                title = title,
                cover = obj.str("cover"),
                rate = obj.str("rate"),
                url = obj.str("url"),
                playable = obj.bool("playable"),
                isNew = obj.bool("is_new"),
            )
        }
    }

    private fun JsonObject.str(key: String): String {
        val element = get(key) ?: return ""
        if (element === JsonNull) return ""
        return (element as? JsonPrimitive)?.content.orEmpty()
    }

    private fun JsonObject.bool(key: String): Boolean = str(key).toBooleanStrictOrNull() ?: false

    companion object {
        private const val TIMEOUT_MS = 10_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) Starpick/0.3"
    }
}
