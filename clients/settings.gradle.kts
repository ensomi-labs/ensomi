pluginManagement {
    // The mirrors come first because this network reaches them and not
    // repo.maven.apache.org. The Android Gradle Plugin lives on Google's Maven, so
    // that repository has to be listed before the plugin portal: without it Gradle
    // falls through to plugins.gradle.org, which this network cannot reach.
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        mavenCentral()
        google()
    }
}

rootProject.name = "ensomi-clients"

include(":shared")
include(":windows")
include(":android")
