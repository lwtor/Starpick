package com.starpick.app.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * 平台层注入 Ktor 的 engine。core 层只持有 HttpClient 抽象，
 * 将来桌面端换别的 engine 时不需要动任何业务代码。
 */
fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            connectTimeout(15, TimeUnit.SECONDS)
            readTimeout(20, TimeUnit.SECONDS)
            writeTimeout(20, TimeUnit.SECONDS)
            followRedirects(true)
            followSslRedirects(true)
            retryOnConnectionFailure(true)
        }
    }
}

/**
 * 独立的 OkHttpClient，供 GitHub Release 自升级库使用。
 * 库的 `Updater.create` 需要 OkHttpClient 来复用连接池/代理/DNS 配置；
 * Ktor 的 OkHttp engine 内部 client 无法取出，这里单独建一个等价的。
 */
fun createOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .retryOnConnectionFailure(true)
    .build()
