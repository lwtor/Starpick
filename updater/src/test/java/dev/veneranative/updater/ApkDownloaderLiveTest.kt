package dev.veneranative.updater

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live smoke test for the segmented transfer. Not part of CI: it needs the network and a published
 * asset, and it is only here to prove that parallel ranges stitch back into a byte-identical APK
 * and that a half-finished set of ranges resumes instead of restarting.
 */
class ApkDownloaderLiveTest {

    private val url = "https://github.com/lwtor/Starpick/releases/download/v0.9.11/Starpick.apk"
    private val expectedSize = 25_490_839L

    @Test
    fun segmentedDownloadIsIntactAndResumable() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "starpick-dl-test")
        dir.deleteRecursively()
        dir.mkdirs()
        val target = File(dir, "Starpick.apk")
        val downloader = ApkDownloader(OkHttpClient())

        val startedAt = System.currentTimeMillis()
        var lastSpeed = 0L
        val first = downloader.download(url, target) { lastSpeed = it.bytesPerSecond }
        val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
        println("full: ${first.length()} bytes in %.1fs (%.0f KB/s, last=$lastSpeed B/s)".format(seconds, first.length() / seconds / 1024.0))
        assertEquals(expectedSize, first.length())
        val reference = sha256(first)
        assertTrue("range files must be cleaned up", dir.listFiles()!!.none { it.name.contains(".part") })

        // Rebuild the exact state of an interrupted run: two ranges already have half their bytes.
        val chunk = (expectedSize + 3) / 4
        listOf(0, 1).forEach { index ->
            val full = first.readBytes()
            val offset = (index * chunk).toInt()
            val half = chunk.toInt() / 2
            File(dir, "Starpick.apk.part.$index").writeBytes(full.copyOfRange(offset, offset + half))
        }
        first.delete()

        var resumedFrom = -1L
        val second = downloader.download(url, target) { progress ->
            if (resumedFrom < 0) resumedFrom = progress.bytesDownloaded
        }
        println("resumed: started reporting at ${resumedFrom / 1024} KB, total ${second.length()}")
        assertEquals("resume must not restart from zero", true, resumedFrom > 0)
        assertEquals("resumed file must be identical", reference, sha256(second))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
