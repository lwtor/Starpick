package com.starpick.app

import android.app.Application
import android.content.Context
import com.starpick.app.core.api.CmsV10Api
import com.starpick.app.core.search.SearchAggregator
import com.starpick.app.platform.createHttpClient
import com.starpick.app.platform.createOkHttpClient
import com.starpick.app.platform.storage.StarpickStore
import dev.veneranative.updater.ReleaseVersionResolver
import dev.veneranative.updater.Updater
import dev.veneranative.updater.UpdaterConfig
import dev.veneranative.updater.UpdaterStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StarpickApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.startUpdateCheck()
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
    val aggregator = SearchAggregator(api)

    /**
     * GitHub Release 应用内自升级。owner/repository 指向发布 APK 的公开仓库；
     * versionCode 从资产名尾部 `-<数字>` 读取，回退到 tag `vX.Y.Z` 折算。
     */
    val updater: Updater = Updater.create(
        context = context.applicationContext,
        config = UpdaterConfig(
            owner = "lwtor",
            repository = "Starpick",
            fileProviderAuthority = "${context.applicationContext.packageName}.updater",
            scope = "self_update",
            versionResolver = ReleaseVersionResolver.Or(
                ReleaseVersionResolver.AssetNameSuffix(),
                ReleaseVersionResolver.TagScheme(),
            ),
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
