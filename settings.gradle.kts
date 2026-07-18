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
        google()
        mavenCentral()
    }
}

rootProject.name = "social"

// 应用主模块
include(":app")

// 4 个核心模块
include(":core-designsystem")
include(":core-data")
include(":core-llm")
include(":core-scheduler")
// #146：用户行为建模核心模块
include(":core-profiling")

// 5 个功能模块
include(":feature-feed")
include(":feature-timeline")
include(":feature-profile")
include(":feature-publish")
include(":feature-onboarding")
