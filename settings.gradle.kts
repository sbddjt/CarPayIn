pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Vehicle SDK (에뮬레이터 VHAL)
        maven {
            url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/")
        }
        // Eclipse Paho MQTT (org.eclipse.paho:org.eclipse.paho.client.mqttv3)
        maven {
            url = uri("https://repo.eclipse.org/content/repositories/paho-releases/")
        }
    }
}

rootProject.name = "Car PayIn"
include(":app")
