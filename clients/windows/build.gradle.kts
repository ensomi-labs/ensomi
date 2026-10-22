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

    // The generated bindings in src/main/kotlin/uniffi load the Rust core through JNA.
    implementation("net.java.dev.jna:jna:5.17.0")

    testImplementation(kotlin("test"))
}

// The Rust core is built in a runtime checkout beside this repository, and the
// generated bindings find it by name in this directory. See README.md.
val nativeLibraryDirectory = layout.projectDirectory.dir("libs")

tasks.test {
    useJUnitPlatform()
    systemProperty("jna.library.path", nativeLibraryDirectory.asFile.absolutePath)
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
