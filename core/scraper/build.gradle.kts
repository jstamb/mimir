plugins {
    alias(libs.plugins.kotlin.jvm)
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
dependencies {
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
