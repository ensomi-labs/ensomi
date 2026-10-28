plugins {
    alias(libs.plugins.android.application) apply false
    // AGP compiles Kotlin itself; this line only puts the pinned Kotlin Gradle plugin on the build classpath.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
