plugins {
    alias(libs.plugins.kotlin.jvm)
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
dependencies {
    api(project(":core:scanner"))
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
