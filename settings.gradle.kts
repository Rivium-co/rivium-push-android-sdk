pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Local development - SDK modules included via include() below
        // For published builds: maven { url = uri("https://pub-69e86fbad8904e4a8bd3a1b2d051df1f.r2.dev/maven") }
        // Local libs folder for fallback
        flatDir {
            dirs("libs")
        }
    }
}

rootProject.name = "rivium-push-android-sdk"

// PN Protocol module
include(":pn-protocol")
project(":pn-protocol").projectDir = file("../protocol/pn-protocol-android/pn-protocol")

// SDK modules
include(":rivium-push")
include(":rivium-push-voip")
include(":example")
