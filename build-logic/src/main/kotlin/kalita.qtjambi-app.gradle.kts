// Convention for the desktop shell (app-desktop — dev_plan.md §9.2, §9.9). Desktop-only, so unlike
// kalita.kotlin-core it is not constrained to an Android-compatible bytecode target.
// QtJambi/Qt dependencies and jpackage wiring are added in stage 0.2/0.3 — this is the module
// skeleton stage 0.1 asks for.

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(25)
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

@Suppress("UnstableApiUsage")
testing {
    suites {
        getByName<JvmTestSuite>("test") {
            useJUnitJupiter()
        }
    }
}

dependencies {
    val libs = versionCatalogs.named("libs")
    add("testImplementation", platform(libs.findLibrary("junit-bom").get()))
    add("testImplementation", libs.findLibrary("junit-jupiter").get())
    add("testImplementation", libs.findLibrary("kotest-assertions-core").get())
    add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
}
