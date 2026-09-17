plugins {
    kotlin("jvm")
}

group = "com.github.jake56667788"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
    maven("https://jitpack.io")

    maven("https://maven.pkg.github.com/MorpheApp/registry") {
        credentials {
            username = System.getenv("GITHUB_ACTOR") ?: "token"
            password = System.getenv("GITHUB_TOKEN") ?: ""
        }
    }
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Name" to project.name
        )
    }
}
