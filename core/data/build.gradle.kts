plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}
android {
    namespace = "dev.mimir.data"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    // MigrationTestHelper loads exported schema JSONs from test assets.
    sourceSets {
        getByName("test") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}
dependencies {
    api(project(":core:scanner"))
    api(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    // kotlin("test") alone resolves the frameworkless variant under AGP's built-in Kotlin
    // (no KGP capability inference), leaving kotlin.test.Test unresolved — pin the JUnit variant.
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.runner)
}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
