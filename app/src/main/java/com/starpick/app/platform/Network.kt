package com.starpick.app.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

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
