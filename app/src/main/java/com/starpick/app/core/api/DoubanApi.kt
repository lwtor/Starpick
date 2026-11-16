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

    /**
     * 关键词搜索豆瓣条目（独立搜索页用，只查豆瓣，不碰用户的采集源）。
     *
     * 走 `j/subject_suggest` 公开接口：无需鉴权，返回里已带海报、年份、类型，
     * 用户点一条之后才由导航层拿片名去自己的 CMS 源里找资源。
     */
    suspend fun search(keyword: String): List<DoubanSubject> {
        val query = keyword.trim()
        if (query.isEmpty()) return emptyList()
        return runCatching {
            withTimeout(TIMEOUT_MS) {
                val response = client.get("https://movie.douban.com/j/subject_suggest") {
                    url { parameters.append("q", query) }
                    headers.append("User-Agent", USER_AGENT)
                    headers.append("Referer", "https://movie.douban.com/")
                }
                if (response.status.value !in 200..299) return@withTimeout emptyList()
                parseSuggest(response.bodyAsText())
            }
        }.getOrDefault(emptyList())
    }

    /** suggest 接口返回 `img` / `sub_title` / `year`，字段名和榜单接口不一样，单独解析。 */
    private fun parseSuggest(body: String): List<DoubanSubject> {
        val array = runCatching { Json.parseToJsonElement(body) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = obj.str("title")
            val type = obj.str("type")
            // 同一个接口也回书、音乐、游戏，这里只要影视。
            if (title.isBlank() || type !in VIDEO_TYPES) return@mapNotNull null
            DoubanSubject(
                id = obj.str("id"),
                title = title,
                cover = obj.str("img"),
                rate = "",
                url = obj.str("url"),
                playable = false,
                isNew = false,
                episodesInfo = listOfNotNull(
                    obj.str("year").takeIf { it.isNotBlank() },
                    obj.str("sub_title").takeIf { it.isNotBlank() && it != title },
                    if (type == "tv") "剧集" else null,
                ).joinToString(" · "),
            )
        }
    }

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
                episodesInfo = obj.str("episodes_info"),
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

        /** suggest 接口的影视类型；book / music / game 一律丢掉。 */
        private val VIDEO_TYPES = setOf("movie", "tv")
    }
}
