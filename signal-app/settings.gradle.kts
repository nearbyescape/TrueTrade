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
include(":engine", ":app")
