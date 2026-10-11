// Convention for the desktop shell (app-desktop — dev_plan.md §9.2, §9.9). Desktop-only, so unlike
// kalita.kotlin-core it is not constrained to an Android-compatible bytecode target.
// QtJambi's native library is split per OS/arch on Maven Central; only linux-x64 is wired here
// (dev development platform and CI both run Ubuntu x64). Windows packaging in stage 0.3 adds
// qtjambi-native-windows-x64 as an extra runtime dependency, not a replacement for this one.

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
    add("implementation", libs.findLibrary("qtjambi").get())
    add("runtimeOnly", libs.findLibrary("qtjambi-native-linux-x64").get())
    add("testImplementation", platform(libs.findLibrary("junit-bom").get()))
    add("testImplementation", libs.findLibrary("junit-jupiter").get())
    add("testImplementation", libs.findLibrary("kotest-assertions-core").get())
    add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
}
