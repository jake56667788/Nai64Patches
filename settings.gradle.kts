pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()

        maven("https://maven.pkg.github.com/MorpheApp/registry") {
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: "token"
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

plugins {
    id("app.morphe.patches") version "1.3.4"
}

rootProject.name = "Nai64Patches"

include(":patches")
