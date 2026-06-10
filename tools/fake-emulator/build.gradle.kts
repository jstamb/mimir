plugins {
    alias(libs.plugins.android.application)
}
android {
    namespace = "dev.mimir.fakeemulator"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.mimir.fakeemulator"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
}
