plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.mimir.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.mimir.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-m1"
    }
    buildFeatures { compose = true }
}
dependencies {
    implementation(project(":core:scanner"))
    implementation(project(":core:launcher"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.documentfile)
    implementation(libs.coroutines.android)
}
