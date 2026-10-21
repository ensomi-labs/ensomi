pluginManagement {
    // The mirrors come first because this network reaches them and not
    // repo.maven.apache.org; the canonical repositories stay as fallbacks.
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
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

include(":windows")
