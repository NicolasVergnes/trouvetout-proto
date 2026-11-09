pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com[.]android.*")
                includeGroupByRegex("com[.]google.*")
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
        google {
            content {
                includeGroupByRegex("com[.]android.*")
                includeGroupByRegex("com[.]google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "trouvetout"

include(":shared")
include(":androidApp")
include(":tools")
include(":models")

// Lot 12 (ADR-027): the install-time asset pack of the models, for the App Bundle sent to Google Play only.
val modelsBundled = providers.gradleProperty("trouvetout.bundleModel").orNull != "false"
if (modelsBundled && providers.gradleProperty("trouvetout.modelDelivery").orNull == "pack") include(":modelPack")
