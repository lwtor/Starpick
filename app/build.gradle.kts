import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 每次构建求值一次，跟着 BuildConfig 进 APK：装到手机上也能看出这个包是哪天打的。
// 注意别开 configuration cache，否则这个时间会被缓存固定住。
val buildTimeStamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())

android {
    namespace = "com.starpick.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.starpick.app"
        minSdk = 26
        targetSdk = 36
        // versionCode 必须与 updater 的 TagScheme（major*1e6 + minor*1e3 + patch）一致，
        // 否则 GitHub tag 折算值永远小于已装版本，更新检查会误判为「已是最新」。
        // 从这里自动推导，避免手写错位数（0.8.1 曾误写成 8100，应为 8001）。
        val appVersion = "0.9.31"
        val seg = appVersion.split(".").map { it.toInt() }
        versionCode = seg[0] * 1_000_000 + seg[1] * 1_000 + seg[2]
        versionName = appVersion
        buildConfigField("String", "BUILD_TIME", "\"$buildTimeStamp\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource)
    implementation(libs.media3.database)
    implementation(libs.media3.ui)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // GitHub Release 应用内自升级（vendored 模块，见 updater/NOTICE）
    implementation(project(":updater"))
}
