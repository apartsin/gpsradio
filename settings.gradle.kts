pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        kotlin("android") version "2.1.0"
        kotlin("jvm") version "2.1.0"
        kotlin("plugin.serialization") version "2.1.0"
        kotlin("plugin.compose") version "2.1.0"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "gpsradio"
include(":core")
include(":app")
