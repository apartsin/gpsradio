// Lets `core` build on its own (no Android SDK needed): run `../gradlew -p core test`.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.1.0"
        kotlin("plugin.serialization") version "2.1.0"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "core"
