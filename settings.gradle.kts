pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ShareScreenKMP"

include(":shared")
include(":desktopApp")
include(":webApp")
include(":androidApp")
include(":server")

