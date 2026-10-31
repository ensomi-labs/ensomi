import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The runtime AAR this build links, from `ensomi.runtime.aar` in local.properties (see README.md).
val runtimeAar: File = run {
    val properties = Properties()
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use(properties::load)
    val path = properties.getProperty("ensomi.runtime.aar")?.trim()
    if (path.isNullOrEmpty()) {
        throw GradleException(
            "Set ensomi.runtime.aar in local.properties to the absolute path of ensomi-runtime.aar " +
                "(built by ensomi-runtime's scripts/build-android.sh). See README.md.",
        )
    }
    val aar = rootProject.file(path)
    if (!aar.isFile) {
        throw GradleException("ensomi.runtime.aar in local.properties points to $path, which is not a file.")
    }
    aar
}

android {
    namespace = "io.ensomi.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    // Only used to strip the runtime's native libraries; the app has no native code of its own.
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "io.ensomi.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        // The phone checks (app/src/androidTest, docs/phone-checks.md), run with `am instrument` by
        // scripts/phone-checks.sh.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // The checks build: not debuggable, as a release is not, so the phone checks measure frame pacing without ART's
        // debuggable mode. Signed with the debug key and without R8, so it installs over the debug build (keeping its
        // data, the model with it) and the debug build's instrumented checks can drive it. It keeps the scenarios and
        // frame logs (src/checks/res/values/bools.xml). Never distributed (docs/phone-checks.md).
        create("checks") {
            initWith(getByName("debug"))
            isDebuggable = false
            isProfileable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // The recognition session (docs/recognition.md) exists only in debug and checks builds: its code, text, share target
    // and permissions are in src/recognition. A release build gets src/release's empty RecognitionEntry instead.
    sourceSets {
        listOf("debug", "checks").forEach { buildType ->
            getByName(buildType) {
                kotlin.srcDir("src/recognition/kotlin")
                res.srcDir("src/recognition/res")
                manifest.srcFile("src/recognition/AndroidManifest.xml")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.android)

    // The runtime. A local AAR carries no dependency metadata, so its dependencies are declared here.
    // When a runtime release carries the AAR, a pinned catalog entry replaces files(runtimeAar).
    implementation(files(runtimeAar))
    implementation(libs.jna) { artifact { type = "aar"; extension = "aar" } }
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
