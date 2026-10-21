import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(22)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
}

compose.desktop {
    application {
        mainClass = "io.ensomi.windows.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Ensomi"
            packageVersion = "0.1.0"
        }
    }
}
