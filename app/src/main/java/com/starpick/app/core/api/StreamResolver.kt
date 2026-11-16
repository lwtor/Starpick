package com.starpick.app.core.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import com.starpick.app.core.parser.StreamUrl
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 播放地址探测。
 *
 * 采集站给的 `vod_play_url` 有三种形态，播放器不能一视同仁：
 *  1. 带扩展名的直链（`.m3u8` / `.mp4`）：直接播。
 *  2. 扩展名被藏起来的地址（`https://cdn/share/<hash>`）：内容其实是 HLS 播放列表，
 *     但播放器按扩展名判断会当成普通文件，解析失败 —— 必须先探一次内容再给 MIME 提示。
 *  3. 分享网页（返回 HTML）：真正的地址藏在页面里，需要抠出来。
 *
 * 探测只对「不像直链」的地址做，常见 `.m3u8` 源不受影响；
 * 探测失败一律退回原地址，最差等于没有这一层。
 */
class StreamResolver(private val client: HttpClient) {

    suspend fun resolve(rawUrl: String): ResolvedStream {
        if (StreamUrl.looksDirect(rawUrl)) return ResolvedStream(rawUrl)
        return withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            probe(rawUrl)
        } ?: ResolvedStream(rawUrl)
    }

    private suspend fun probe(rawUrl: String): ResolvedStream {
        val body = runCatching {
            val response = client.get(rawUrl) {
                headers.append("User-Agent", USER_AGENT)
            }
            if (response.status.value !in 200..299) return@runCatching null
            // 只读文本响应：万一是 mp4 这类二进制，bodyAsText 会把整部片子拉进内存。
            val contentType = response.headers[HttpHeaders.ContentType].orEmpty().lowercase()
            val textual = contentType.startsWith("text/") ||
                contentType.contains("mpegurl") ||
                contentType.contains("json") ||
                contentType.contains("octet-stream")
            if (!textual) return@runCatching null
            response.bodyAsText()
        }.getOrNull() ?: return ResolvedStream(rawUrl)

        val head = body.trimStart()
        return when {
            // 播放列表：地址本身就能播，但要告诉播放器这是 HLS。
            head.startsWith("#EXTM3U") -> ResolvedStream(rawUrl, MIME_HLS)
            head.startsWith("<") -> extractFromHtml(body) ?: ResolvedStream(rawUrl)
            else -> ResolvedStream(rawUrl)
        }
    }

    /** 分享页里的直链：常见写法有 `"url":"..."`、`var main = "..."`、`source.src="..."`。 */
    private fun extractFromHtml(html: String): ResolvedStream? {
        val text = html.replace("\\/", "/").replace("&amp;", "&")
        M3U8_PATTERN.find(text)?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { return ResolvedStream(it, MIME_HLS) }
        MP4_PATTERN.find(text)?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { return ResolvedStream(it, null) }
        return null
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 6_000L
        private const val MIME_HLS = "application/x-mpegURL"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0 Mobile Safari/537.36"

        private val M3U8_PATTERN = Regex(
            "https?://[^\"'\\s<>\\\\]+\\.m3u8[^\"'\\s<>\\\\]*",
        )
        private val MP4_PATTERN = Regex(
            "https?://[^\"'\\s<>\\\\]+\\.mp4[^\"'\\s<>\\\\]*",
        )
    }
}

/** 探测结果：最终地址 + 可选 MIME 提示（HLS 需要显式告诉播放器）。 */
data class ResolvedStream(
    val url: String,
    /** null 表示交给播放器自己按扩展名判断。 */
    val mimeType: String? = null,
)
