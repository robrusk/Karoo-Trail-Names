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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = "robrusk"
                password = "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"
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "KarooTrailNames"
include(":app")