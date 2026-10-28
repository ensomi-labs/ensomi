plugins {
    // AGP 9 compiles Kotlin itself, so `org.jetbrains.kotlin.android` must not be applied.
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "io.ensomi.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.ensomi.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        // JNA opens the packaged libraries by path, so they have to be extracted from the APK.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")

    // Compose 1.11.4 is the newest that compiles against SDK 36; 1.12 requires platform 37,
    // which this SDK installation does not have.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // These are the newest versions whose AndroidX artifacts compile against SDK 36; the next
    // release of each requires platform 37, which this SDK installation does not have.
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.documentfile:documentfile:1.1.0")

    // The runtime core's UniFFI bindings load the packaged libensomi_runtime_ffi.so through JNA.
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // The correlation rules are plain Kotlin, so they run as JVM unit tests on the build machine.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.21")
    testImplementation("junit:junit:4.13.2")
}
