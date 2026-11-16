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
import dev.veneranative.updater.ReleaseVersionResolver
import dev.veneranative.updater.Updater
import dev.veneranative.updater.UpdaterConfig
import dev.veneranative.updater.UpdaterStrings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class StarpickApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        configureImageLoader()
        container = AppContainer(this)
        container.startUpdateCheck()
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

    /** 启动时静默检查 + 预下载，失败不打扰用户，结果经 [updater.status] 暴露。 */
    fun startUpdateCheck() {
        appScope.launch {
            updater.checkAndPrefetch(BuildConfig.VERSION_CODE.toLong())
        }
    }
}
