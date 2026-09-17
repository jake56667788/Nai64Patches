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

rootProject.name = "Nai64Patches"
include(":patches")
