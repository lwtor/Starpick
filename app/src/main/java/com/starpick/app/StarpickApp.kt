package com.starpick.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.starpick.app.core.api.CmsV10Api
import com.starpick.app.core.api.DoubanApi
import com.starpick.app.core.api.StreamResolver
import com.starpick.app.core.search.SearchAggregator
import com.starpick.app.platform.createHttpClient
import com.starpick.app.platform.createOkHttpClient
import com.starpick.app.platform.storage.StarpickStore
import dev.veneranative.updater.InstallResult
import dev.veneranative.updater.ReleaseAsset
import dev.veneranative.updater.ReleaseVersionResolver
import dev.veneranative.updater.UpdateCheck
import dev.veneranative.updater.Updater
import dev.veneranative.updater.UpdaterConfig
import dev.veneranative.updater.UpdaterStrings
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * 应用内更新的界面状态。自己维护而不是直接用库的 [dev.veneranative.updater.UpdateStatus]：
 * 后者只有「有更新 / 已下载」两档，没有下载进度，也没法表达「检查中 / 失败原因」。
 */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val tag: String) : UpdateUiState
    data class Available(
        val versionCode: Long,
        val tag: String,
        val asset: ReleaseAsset,
    ) : UpdateUiState
    data class Downloading(
        val fraction: Float?,
        val bytes: Long,
        val totalBytes: Long?,
    ) : UpdateUiState
    data class Ready(val versionCode: Long, val file: File) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

class StarpickApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        configureImageLoader()
        container = AppContainer(this)
        container.checkUpdate()
        // 预热磁盘缓存：首次创建 SimpleCache 要打开 SQLite，别让它落在第一帧播放上。
        container.appScope.launch { runCatching { container.mediaCache } }
    }

    /**
     * 配置 Coil 全局 ImageLoader：豆瓣图片服务器（doubanio.com）有防盗链，
     * 需要带 Referer 才能加载封面。这里用带拦截器的 OkHttpClient 统一注入。
     */
    private fun configureImageLoader() {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request()
                val newRequest = if (request.url.host.contains("doubanio.com")) {
                    request.newBuilder()
                        .header("Referer", "https://movie.douban.com/")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) Starpick/0.4")
                        .build()
                } else {
                    request
                }
                chain.proceed(newRequest)
            }
            .build()

        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                }
                .crossfade(true)
                .build()
        }
    }
}

/**
 * 手写的极简依赖容器。没有引入 Hilt/Koin —— 基础形态不需要，
 * 也避免把 Android 专属的注解处理器带进将来可能共享的代码里。
 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = createHttpClient()

    /** 启动外部 Activity（浏览器下载）需要 Context，非 Activity 场景必须带 NEW_TASK。 */
    private val appContext = context.applicationContext

    val store = StarpickStore(context.applicationContext)
    val api = CmsV10Api(httpClient)
    val douban = DoubanApi(httpClient)
    val aggregator = SearchAggregator(api)

    /** 播放地址探测：把分享页 / 藏了扩展名的 HLS 地址转成播放器能直接吃的形式。 */
    val streams = StreamResolver(httpClient)

    // ── 播放缓存（边看边缓存）──────────────────────────────────────

    /**
     * 磁盘缓存上限：取设备可用空间的 1/12，夹在 512 MB ~ 2 GB。
     *
     * 固定值要么浪费（大存储只给 512 MB），要么撑爆（小机器给 2 GB）；
     * 1/12 大约等于「用掉 8%」，留足空间给系统和其它 App。
     */
    val mediaCacheLimitBytes: Long = run {
        val available = runCatching { StatFs(appContext.cacheDir.path).availableBytes }
            .getOrDefault(0L)
        (available / 12)
            .coerceIn(256L * MB, 2048L * MB)
            // 存储特别紧张时最多只占三分之一，别把设备写满。
            .coerceAtMost((available / 3).coerceAtLeast(128L * MB))
    }

    /**
     * 边看边缓存。用的是 Media3 官方 `CacheDataSource` + `SimpleCache`，
     * 不是 AndroidVideoCache 那类本地代理库 —— 后者已停止维护、且不支持 HLS，
     * 而星匣的采集源大部分是 m3u8。
     *
     * 两点约束：
     *  - 同一目录只能有一个 SimpleCache 实例，所以挂在容器上随进程存活；
     *  - 放 external-files 而不是 cacheDir：系统空间不足时会把 cacheDir 整片删掉，
     *    索引与分片对不上会让缓存读取直接报错。
     */
    val mediaCache: SimpleCache by lazy {
        val root = appContext.getExternalFilesDir("media") ?: appContext.filesDir
        SimpleCache(
            File(root, "video-cache"),
            LeastRecentlyUsedCacheEvictor(mediaCacheLimitBytes),
            StandaloneDatabaseProvider(appContext),
        )
    }

    /** 已占用的缓存字节数（读到的是内存里的索引，很快）。 */
    val mediaCacheBytes: Long
        get() = mediaCache.cacheSpace

    /**
     * 带磁盘缓存的网络源。上游仍是原来那个「浏览器 UA + 允许跨协议重定向」的
     * HttpDataSource，行为不变，只是前面多了一层磁盘缓存：
     * 同一地址重复播放、拖动回看都不再重新下载。
     *
     * `FLAG_IGNORE_CACHE_ON_ERROR`：缓存文件损坏 / 被清掉时直接回源，而不是报错。
     */
    val cachedDataSourceFactory: CacheDataSource.Factory by lazy {
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(HTTP_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        CacheDataSource.Factory()
            .setCache(mediaCache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory(
                object : CacheKeyFactory {
                    override fun buildCacheKey(dataSpec: DataSpec): String =
                        cacheKeyOf(dataSpec.uri.toString())
                },
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** 清空播放缓存，返回释放的字节数。内部已切 IO，调用方直接 await 即可。 */
    suspend fun clearMediaCache(): Long = withContext(Dispatchers.IO) {
        val before = mediaCacheBytes
        mediaCache.keys.toList().forEach { mediaCache.removeResource(it) }
        (before - mediaCacheBytes).coerceAtLeast(0L)
    }

    /**
     * GitHub Release 应用内自升级。owner/repository 指向发布 APK 的公开仓库。
     *
     * versionCode 一律由 tag `vX.Y.Z` 折算（`major*1e6 + minor*1e3 + patch`），资产的固定名
     * `Starpick.apk` 不参与版本判断。这样 api.github.com 被限流时，可以退回
     * `releases/latest/download/Starpick.apk` 这条不需要配额的通道，两条路径算出的版本号一致。
     */
    val updater: Updater = Updater.create(
        context = context.applicationContext,
        config = UpdaterConfig(
            owner = "lwtor",
            repository = "Starpick",
            fileProviderAuthority = "${context.applicationContext.packageName}.updater",
            scope = "self_update",
            versionResolver = ReleaseVersionResolver.TagScheme(),
            fallbackAssetName = "Starpick.apk",
            strings = UpdaterStrings(
                channelName = "应用更新",
                channelDescription = "显示新版本 APK 的下载进度",
                downloadTitle = "正在下载星匣新版本",
                progressFormat = "已下载 %d%%",
            ),
        ),
        httpClient = createOkHttpClient(),
    )

    /** 进程内记住已下载的包，避免每次进设置页都重新下一遍。 */
    private var downloadedApk: Pair<Long, File>? = null

    /**
     * 最近一次检查命中的 APK 资产。
     *
     * 应用内下载走 GitHub 的 302 重定向 + API 配额，遇到限流、被拦的运营商网络就会卡住；
     * 浏览器有完整的重定向与 Cookie 能力，是更稳的兜底，所以这里把资产留一份给外部下载用。
     */
    private var latestAsset: ReleaseAsset? = null

    /**
     * 可交给浏览器的 APK 直链。
     *
     * 有检查结果时用 Release 资产的真实地址；检查失败（限流 / 无网络）也退回
     * `releases/latest/download/<固定名>` —— 这条路径不查 API、永远指向最新发布，
     * 所以「浏览器下载」在任何状态下都能给出一个能用的链接。
     */
    val browserDownloadUrl: String
        get() = latestAsset?.downloadUrl ?: LATEST_APK_URL

    private val _update = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)

    /** 更新流程的唯一状态源，设置页直接渲染它。 */
    val update: StateFlow<UpdateUiState> = _update.asStateFlow()

    /**
     * 检查更新。只查不下载 —— 24 MB 的包不该在用户没同意时静默吃掉。
     * 任何结果（含失败原因）都写进 [update]，UI 只负责渲染。
     */
    fun checkUpdate() {
        val busy = _update.value is UpdateUiState.Checking ||
            _update.value is UpdateUiState.Downloading
        if (busy) return
        appScope.launch {
            _update.value = UpdateUiState.Checking
            try {
                when (val check = updater.check(BuildConfig.VERSION_CODE.toLong())) {
                    is UpdateCheck.Available -> {
                        latestAsset = check.asset
                        val cached = downloadedApk
                            ?.takeIf { it.first == check.versionCode }
                            ?.second
                            ?.takeIf { it.isFile }
                            ?: updater.cachedApk(check.versionCode)?.takeIf { it.isFile }
                        _update.value = if (cached != null) {
                            UpdateUiState.Ready(check.versionCode, cached)
                        } else {
                            UpdateUiState.Available(
                                versionCode = check.versionCode,
                                tag = check.release.tagName,
                                asset = check.asset,
                            )
                        }
                    }

                    is UpdateCheck.UpToDate ->
                        _update.value = UpdateUiState.UpToDate(check.release.tagName)

                    UpdateCheck.NoInstallableAsset ->
                        _update.value = UpdateUiState.Failed("最新发布里没有可安装的 APK")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _update.value = UpdateUiState.Failed(error.message ?: "检查失败")
            }
        }
    }

    /** 下载更新包。走库的流式下载，每 256 KB 把进度回填到 [update]。 */
    fun downloadUpdate(versionCode: Long, asset: ReleaseAsset) {
        if (_update.value is UpdateUiState.Downloading) return
        appScope.launch {
            _update.value = UpdateUiState.Downloading(
                fraction = null,
                bytes = 0L,
                totalBytes = asset.sizeBytes.takeIf { it > 0 },
            )
            try {
                val file = updater.download(asset) { progress ->
                    _update.value = UpdateUiState.Downloading(
                        fraction = progress.fraction,
                        bytes = progress.bytesDownloaded,
                        totalBytes = progress.totalBytes,
                    )
                }
                downloadedApk = versionCode to file
                _update.value = UpdateUiState.Ready(versionCode, file)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _update.value = UpdateUiState.Failed(error.message ?: "下载失败")
            }
        }
    }

    /** 交给系统安装器，用户在系统弹窗里确认。 */
    fun installUpdate(file: File): InstallResult = updater.install(file)

    /**
     * 把 APK 直链交给系统浏览器 / 下载器。
     *
     * 应用内下载要从 GitHub 302 到 objects.githubusercontent.com，还会受 API 配额影响，
     * 某些网络下会卡在 0%（之前就出过）；浏览器自带重定向、断点续传与「下载完成」通知，
     * 所以这条通道作为兜底，任何更新状态下都能用。
     *
     * @return 是否成功唤起（设备没有浏览器时为 false）
     */
    fun openDownloadInBrowser(): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(browserDownloadUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { appContext.startActivity(intent) }.isSuccess
    }

    private companion object {
        /** 与 [UpdaterConfig] 里的 owner / repository / fallbackAssetName 保持一致。 */
        const val LATEST_APK_URL =
            "https://github.com/lwtor/Starpick/releases/latest/download/Starpick.apk"

        const val MB = 1024L * 1024

        /**
         * HLS / DASH 播放列表按「小时桶」做缓存 key。
         *
         * 列表本身只有几 KB，但它决定分片地址：如果永久缓存，源站换了 CDN 或重新切片后
         * 会一直用旧地址。按小时分桶既能让一集内反复拖动命中缓存，又保证跨小时自动失效。
         * 分片（.ts/.m4s/.mp4）仍按真实 URL 缓存 —— 体积的 99% 在这里，重看才算真省钱。
         */
        const val PLAYLIST_BUCKET_MS = 60L * 60L * 1000L

        const val HTTP_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0 Mobile Safari/537.36"

        fun cacheKeyOf(url: String): String {
            val isPlaylist = url.contains(".m3u8", ignoreCase = true) ||
                url.contains(".mpd", ignoreCase = true)
            return if (isPlaylist) {
                "$url#${System.currentTimeMillis() / PLAYLIST_BUCKET_MS}"
            } else {
                url
            }
        }
    }
}
