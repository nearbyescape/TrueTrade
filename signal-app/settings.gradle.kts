pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "signal-app"

// :engine = the tested strategy engine (plain Kotlin). :app = the Android app that runs it.
// :desktop = the desktop app (Windows installer), her break-and-retest strategy only.
include(":engine", ":app", ":desktop")
