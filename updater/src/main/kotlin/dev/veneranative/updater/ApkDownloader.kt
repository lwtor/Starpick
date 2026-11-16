package dev.veneranative.updater

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

/**
 * A failure establishing the connection; retrying through the other network channel may help.
 * Private to the downloader: [DownloadException] stays a single final public type, and this
 * marker exists only so the transfer can tell "the channel is unreachable" apart from "the
 * server answered with an error", which must never be replayed.
 */
private class DownloadConnectFailure(message: String) : Exception(message)

/**
 * Byte-level progress of a download; [fraction] is null while the total size is unknown.
 *
 * [bytesPerSecond] is a smoothed recent rate, meant for the "how fast is this going" label. It is
 * zero for the first sample of a transfer and is never authoritative — only presentational.
 */
data class UpdateProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Long = 0L,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (bytesDownloaded.toDouble() / it).toFloat() }
}

/**
 * Streams a release APK to a local file with byte-level progress, parallel range connections and
 * resumable downloads.
 *
 * ## Why the transfer is split into ranges
 *
 * A single TCP stream from GitHub's release CDN is throttled per connection on many consumer
 * networks: measured from a Chinese residential line, one connection oscillated between 14 KB/s
 * and 190 KB/s while four parallel connections held a combined 550–650 KB/s for the same file.
 * Since the CDN advertises `Accept-Ranges: bytes`, the file is therefore fetched as [segmentCount]
 * contiguous byte ranges at once and stitched together locally. When the server does not support
 * ranges (or the size is tiny), the transfer silently degrades to the classic single stream.
 *
 * ## Resuming
 *
 * Every range owns its own `*.part.N` sibling, so an interrupted download resumes each range from
 * its own length on the next attempt instead of starting over. A range that stalls (no byte for
 * [READ_TIMEOUT_SECONDS]) is retried a couple of times before the whole download is reported as
 * failed — a transient stall on one of four connections should not discard the other three.
 *
 * The download first tries the device's system proxy (which is how the user's VPN accelerates the
 * browser), then falls back to a direct connection when the proxy fails before any progress was
 * made.
 */
internal class ApkDownloader(
    baseClient: OkHttpClient,
    private val segmentCount: Int = DEFAULT_SEGMENT_COUNT,
) {
    private val channels: List<OkHttpClient> = listOf(
        baseClient.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // HTTP/2 multiplexes every concurrent request onto ONE TCP connection, which is exactly
            // what a parallel range download must avoid: the streams then share one congestion
            // window and one throttled connection, so "four connections" silently becomes one — the
            // slow ranges starve and time out. HTTP/1.1 gives each range its own socket.
            .protocols(listOf(Protocol.HTTP_1_1))
            .build(),
        baseClient.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build(),
    ).distinctBy { it.proxy }

    /** Downloads [url] into [target], resuming any partial ranges left by a previous attempt. */
    suspend fun download(url: String, target: File, onProgress: (UpdateProgress) -> Unit = {}): File {
        val parent = target.parentFile ?: throw DownloadException("target has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw DownloadException("cannot create ${parent.name}")
        }

        val plan = probe(url)
        if (plan != null && plan.totalBytes >= MIN_SEGMENTED_BYTES) {
            fetchSegmentally(plan, target, onProgress)
            return target
        }

        // Single-stream path: unknown size, tiny file, or a server that refuses range requests.
        val part = File(parent, target.name + ".part")
        val startBytes = if (part.isFile) part.length() else 0L
        try {
            downloadSingleStream(url, part, startBytes, onProgress)
            verifyApkHeader(part)
        } catch (error: DownloadException) {
            // A body that is not an APK at all is garbage (e.g. an HTML error page served as
            // 200); resuming into it would only append valid bytes to junk, so drop it.
            part.delete()
            throw error
        }
        moveIntoPlace(part, target)
        return target
    }

    // ── channel / size discovery ────────────────────────────────────────────

    private class Plan(
        val finalUrl: String,
        val totalBytes: Long,
        val channel: OkHttpClient,
    )

    private sealed interface Probe {
        class Usable(val plan: Plan) : Probe
        /** The host answered correctly but will not serve ranges; fall back to one stream. */
        data object NoRanges : Probe
        /** This channel could not reach the host at all; the next one may. */
        data object Unreachable : Probe
    }

    /**
     * Resolves the redirect chain once, so every range request can hit the final signed CDN URL
     * directly instead of paying for GitHub's 302 on each of the four connections.
     */
    private suspend fun probe(url: String): Plan? = withContext(Dispatchers.IO) {
        for (channel in channels) {
            when (val result = probeOnce(channel, url)) {
                is Probe.Usable -> return@withContext result.plan
                Probe.NoRanges -> return@withContext null
                Probe.Unreachable -> Unit
            }
        }
        null
    }

    private suspend fun probeOnce(channel: OkHttpClient, url: String): Probe = try {
        awaitResponse(channel, Request.Builder().url(url).head().build()).use { response ->
            if (!response.isSuccessful) {
                Probe.NoRanges
            } else {
                val supportsRanges =
                    response.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true
                val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
                if (supportsRanges && length > 0) {
                    Probe.Usable(Plan(response.request.url.toString(), length, channel))
                } else {
                    Probe.NoRanges
                }
            }
        }
    } catch (_: IOException) {
        Probe.Unreachable
    }

    // ── segmented transfer ──────────────────────────────────────────────────

    private suspend fun fetchSegmentally(
        plan: Plan,
        target: File,
        onProgress: (UpdateProgress) -> Unit,
    ) {
        val parent = target.parentFile ?: throw DownloadException("target has no parent directory")
        val segments = layout(plan.totalBytes, target)
        // The legacy single-file `.part` cannot be mapped onto ranges; keep the ranges clean.
        File(parent, target.name + ".part").delete()

        val done = AtomicLong(segments.sumOf { if (it.file.isFile) it.file.length() else 0L })
        val meter = SpeedMeter()

        coroutineScope {
            val pump = launch {
                while (isActive) {
                    val bytes = done.get().coerceAtMost(plan.totalBytes)
                    onProgress(UpdateProgress(bytes, plan.totalBytes, meter.sample(bytes)))
                    delay(PROGRESS_TICK_MS)
                }
            }
            try {
                val jobs = segments.map { segment ->
                    launch(Dispatchers.IO) {
                        fetchRange(plan, segment) { delta -> done.addAndGet(delta) }
                    }
                }
                // Await the ranges explicitly: the pump only stops once they are done, so waiting
                // on the scope itself would never return.
                jobs.forEach { it.join() }
            } finally {
                pump.cancel()
            }
        }

        val assembled = File(parent, target.name + ".assembling")
        try {
            val written = assemble(segments, assembled)
            if (written != plan.totalBytes) {
                throw DownloadException("truncated download: assembled $written of ${plan.totalBytes} bytes")
            }
            verifyApkHeader(assembled)
            verifyZipStructure(assembled)
            moveIntoPlace(assembled, target)
        } catch (error: DownloadException) {
            assembled.delete()
            throw error
        }
        segments.forEach { it.file.delete() }
        onProgress(UpdateProgress(plan.totalBytes, plan.totalBytes, 0L))
    }

    private fun layout(totalBytes: Long, target: File): List<Segment> {
        val parent = target.parentFile ?: throw DownloadException("target has no parent directory")
        val count = segmentCount.coerceAtLeast(1)
        val chunk = (totalBytes + count - 1) / count
        return (0 until count).mapNotNull { index ->
            val start = index * chunk
            if (start >= totalBytes) return@mapNotNull null
            val end = (start + chunk - 1).coerceAtMost(totalBytes - 1)
            Segment(index, start, end, File(parent, "${target.name}.part.$index"))
        }
    }

    private class Segment(
        val index: Int,
        val start: Long,
        val end: Long,
        val file: File,
    ) {
        val length: Long get() = end - start + 1
    }

    private suspend fun fetchRange(plan: Plan, segment: Segment, onDelta: (Long) -> Unit) {
        var attempt = 0
        while (true) {
            var have = if (segment.file.isFile) segment.file.length() else 0L
            // A range file longer than the range means the asset changed size (a newer release
            // reused the file name); start that range over rather than trusting stale bytes.
            if (have > segment.length) {
                segment.file.delete()
                have = 0L
            }
            if (have == segment.length) return

            try {
                streamRange(plan, segment, have, onDelta)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: DownloadException) {
                throw error
            } catch (error: IOException) {
                attempt++
                val progressed = (if (segment.file.isFile) segment.file.length() else 0L) > have
                if (!progressed || attempt >= MAX_RANGE_ATTEMPTS) {
                    throw DownloadException(
                        "range ${segment.index + 1} stalled: ${error.message ?: error::class.simpleName}",
                    )
                }
                // Back off a little: an immediate retry tends to land on the same throttled edge
                // node, while a fresh socket a moment later often does not.
                delay(RETRY_BACKOFF_MS * attempt)
            }
        }
    }

    private suspend fun streamRange(
        plan: Plan,
        segment: Segment,
        from: Long,
        onDelta: (Long) -> Unit,
    ) {
        val request = Request.Builder()
            .url(plan.finalUrl)
            .header("Range", "bytes=${segment.start + from}-${segment.end}")
            .get()
            .build()
        val response = awaitResponse(plan.channel, request)
        response.use { r ->
            // The signed CDN URL is picked after a HEAD that advertised range support; a 200 here
            // means the server ignored the range, and appending it would corrupt the file.
            if (r.code != 206) throw DownloadException("range request rejected with HTTP ${r.code}")

            RandomAccessFile(segment.file, "rw").use { out ->
                out.seek(from)
                val source = r.body.source()
                val buffer = ByteArray(IO_BUFFER_BYTES)
                while (true) {
                    // The read itself is blocking and says nothing about cancellation, so a cancel
                    // would otherwise wait for the whole 6 MB range to finish downloading.
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    onDelta(read.toLong())
                }
            }
        }
    }

    private fun assemble(segments: List<Segment>, output: File): Long {
        output.delete()
        var written = 0L
        FileOutputStream(output).channel.use { sink ->
            for (segment in segments) {
                FileInputStream(segment.file).channel.use { source ->
                    var position = 0L
                    val size = source.size()
                    while (position < size) {
                        val moved = source.transferTo(position, size - position, sink)
                        if (moved <= 0L) break
                        position += moved
                    }
                }
                written += segment.file.length()
            }
        }
        return written
    }

    // ── single-stream transfer ──────────────────────────────────────────────

    private suspend fun downloadSingleStream(
        url: String,
        part: File,
        startBytes: Long,
        onProgress: (UpdateProgress) -> Unit,
    ) {
        val request = Request.Builder().url(url).get().apply {
            if (startBytes > 0) header("Range", "bytes=$startBytes-")
        }.build()

        var lastFailure: String? = null
        for ((index, client) in channels.withIndex()) {
            try {
                streamAll(client, request, part, startBytes, onProgress)
                return
            } catch (failure: DownloadConnectFailure) {
                lastFailure = failure.message
                // Only a connect failure justifies switching channels, and only while no bytes
                // have been written. HTTP errors and mid-stream failures must not be replayed.
                val canFallback = index < channels.lastIndex && part.length() <= startBytes
                if (!canFallback) throw DownloadException(failure.message ?: "download failed")
            }
        }
        throw DownloadException(lastFailure ?: "download failed")
    }

    private suspend fun streamAll(
        client: OkHttpClient,
        request: Request,
        part: File,
        startBytes: Long,
        onProgress: (UpdateProgress) -> Unit,
    ) {
        val response = try {
            awaitResponse(client, request)
        } catch (error: IOException) {
            if (error is SocketTimeoutException) {
                throw DownloadConnectFailure("the download timed out while connecting: ${error.message}")
            }
            throw DownloadConnectFailure("could not reach the download server: ${error.message}")
        }
        response.use {
            val code = it.code
            if (code != 200 && code != 206) throw DownloadException("download failed with HTTP $code")

            val body = it.body
            val totalBytes = parseTotalBytes(it.header("Content-Range"), body.contentLength())
            // The server may ignore our range and send the whole file (200): restart from zero.
            val offset = if (code == 206) startBytes else 0L
            var copied = offset
            val meter = SpeedMeter()

            RandomAccessFile(part, "rw").use { file ->
                file.seek(offset)
                val source = body.source()
                val buffer = ByteArray(IO_BUFFER_BYTES)
                var lastReportedAt = System.nanoTime()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read == -1) break
                    file.write(buffer, 0, read)
                    copied += read
                    // Report on a time basis: a byte threshold turns into a frozen progress bar on
                    // a slow link (256 KB at 20 KB/s is a 13-second silence).
                    val now = System.nanoTime()
                    if (now - lastReportedAt >= PROGRESS_TICK_NANOS) {
                        lastReportedAt = now
                        onProgress(UpdateProgress(copied, totalBytes, meter.sample(copied)))
                    }
                }
            }
            onProgress(UpdateProgress(copied, totalBytes, 0L))
            if (totalBytes != null && totalBytes > 0 && copied != totalBytes) {
                throw DownloadException("truncated download: received $copied of $totalBytes bytes")
            }
        }
    }

    private fun parseTotalBytes(contentRange: String?, contentLength: Long): Long? {
        // "bytes 0-499/500" -> 500, falling back to Content-Length when absent.
        return contentRange?.substringAfterLast('/')?.toLongOrNull()?.takeIf { it > 0 }
            ?: contentLength.takeIf { it > 0 }
    }

    // ── local file helpers ──────────────────────────────────────────────────

    private fun verifyApkHeader(file: File) {
        val header = ByteArray(ZIP_LOCAL_FILE_HEADER.size)
        val read = FileInputStream(file).use { it.read(header) }
        if (read != header.size || !header.contentEquals(ZIP_LOCAL_FILE_HEADER)) {
            throw DownloadException("the downloaded file is not an Android package")
        }
    }

    /**
     * Cheap integrity check for the assembled file.
     *
     * A wrong range offset still produces a file that starts with `PK\x03\x04` and has the right
     * length, so the header check alone would let a corrupted APK through. Reading the ZIP central
     * directory (a few KB at the end) fails immediately when the ranges were stitched in the wrong
     * order or one of them was short.
     */
    private fun verifyZipStructure(file: File) {
        try {
            java.util.zip.ZipFile(file).use { zip ->
                if (zip.size() <= 0) throw DownloadException("the downloaded package has no entries")
            }
        } catch (error: IOException) {
            throw DownloadException("the downloaded package is corrupted: ${error.message}")
        }
    }

    private fun moveIntoPlace(part: File, target: File) {
        try {
            java.nio.file.Files.move(
                part.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                part.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    /** Exponentially smoothed throughput, so the label does not jitter with every read. */
    private class SpeedMeter {
        private var lastNanos = System.nanoTime()
        private var lastBytes = 0L
        private var smoothed = 0.0

        fun sample(bytes: Long): Long {
            val now = System.nanoTime()
            val elapsed = (now - lastNanos) / 1_000_000_000.0
            if (elapsed < 0.2) return smoothed.toLong().coerceAtLeast(0L)
            val instant = (bytes - lastBytes) / elapsed
            smoothed = if (smoothed <= 0.0) instant else smoothed * 0.6 + instant * 0.4
            lastNanos = now
            lastBytes = bytes
            return smoothed.toLong().coerceAtLeast(0L)
        }
    }

    companion object {
        private const val IO_BUFFER_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_SECONDS = 30L

        // 120 s used to be the read timeout: one half-dead connection could keep the bar at 2% for
        // two minutes before anything surfaced. Four parallel ranges tolerate a shorter leash.
        private const val READ_TIMEOUT_SECONDS = 20L
        private const val MAX_RANGE_ATTEMPTS = 4
        private const val RETRY_BACKOFF_MS = 600L
        private const val DEFAULT_SEGMENT_COUNT = 4
        private const val MIN_SEGMENTED_BYTES = 2L * 1024 * 1024
        private const val PROGRESS_TICK_MS = 300L
        private const val PROGRESS_TICK_NANOS = PROGRESS_TICK_MS * 1_000_000L

        // APK files are ZIP archives and always start with a local file header.
        private val ZIP_LOCAL_FILE_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    }
}
