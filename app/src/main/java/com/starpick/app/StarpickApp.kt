package com.starpick.app

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.starpick.app.core.api.CmsV10Api
import com.starpick.app.core.api.DoubanApi
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

    val store = StarpickStore(context.applicationContext)
    val api = CmsV10Api(httpClient)
    val douban = DoubanApi(httpClient)
    val aggregator = SearchAggregator(api)

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
}
