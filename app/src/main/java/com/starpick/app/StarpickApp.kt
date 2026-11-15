package com.starpick.app

import android.app.Application
import android.content.Context
import com.starpick.app.core.api.CmsV10Api
import com.starpick.app.core.search.SearchAggregator
import com.starpick.app.platform.createHttpClient
import com.starpick.app.platform.storage.StarpickStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class StarpickApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
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
}
