// Vendored from github-updater-android (Apache-2.0), see NOTICE.
// The maven-publish plugin and unit-test dependencies were dropped: this module is consumed
// in-tree as a plain Android library, not published.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.veneranative.updater"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // okhttp and coroutines leak into the public API (OkHttpClient in `Updater.create`,
    // Flow/StateFlow on `UpdateGateway` and `Updater.status`), so consumers must see them.
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)

    // FileProvider exposes the downloaded APK to the system installer.
    implementation(libs.androidx.core.ktx)
    // The download runs as a WorkManager worker so it survives the process and the device's own
    // constraints.
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation("junit:junit:4.13.2")
}
