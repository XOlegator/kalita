import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // gradle/libs.versions.toml is auto-detected as the "libs" catalog; declaring it again here
    // via versionCatalogs { from(files(...)) } makes Gradle 9.8.1 load it twice and fail with
    // "Multiple 'from' invocations" (confirmed by bisecting against a minimal repro).
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kalita"

// Domain and infrastructure (dev_plan.md §9.2) — filled in across stages 1, 2, 7a and 9.
include(
    ":modules:core",
    ":modules:storage-api",
    ":modules:storage-mdb",
    ":modules:storage-local",
    ":modules:sync",
    ":modules:presentation",
    ":modules:plugin-api",
    ":modules:platform",
    ":modules:app-desktop",
    ":modules:app-android",
    ":modules:agent-api",
    ":modules:app-cli",
    ":modules:app-server",
    ":modules:cli",
)
