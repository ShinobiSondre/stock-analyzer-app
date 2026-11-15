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
        maven { url = uri("https://jitpack.io") } // NewPipeExtractor (YouTube streams, same as FaithScroll)
    }
}
rootProject.name = "StockAnalyzerApp"
include(":engine", ":trainer")
if (file("app/build.gradle.kts").exists()) include(":app")
