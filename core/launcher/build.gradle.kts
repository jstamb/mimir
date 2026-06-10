plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
dependencies {
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
