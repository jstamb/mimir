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
        versionCode = 5
        versionName = "0.5.0-m2c"
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
    implementation(project(":core:data"))
    implementation(project(":core:scraper"))
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    // kotlin("test") alone resolves the frameworkless variant under AGP's built-in Kotlin
    // (no KGP capability inference), leaving kotlin.test.Test unresolved — pin the JUnit variant.
    testImplementation(kotlin("test-junit"))
}
