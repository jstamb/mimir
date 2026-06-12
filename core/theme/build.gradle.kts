plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "dev.mimir.theme"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    buildFeatures { compose = true }
}
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.serialization.json)
    implementation(libs.androidx.palette)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    // AGP built-in Kotlin: pin the JUnit variant (see core/data comment)
    testImplementation(kotlin("test-junit"))
}
