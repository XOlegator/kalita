// Placeholder for the jlink/jpackage wiring that stage 0.3 adds for app-desktop (MSI on Windows,
// AppImage/deb/rpm on Linux — dev_plan.md §15, §9.2 `packaging/`). Declared now, empty until 0.3,
// so the convention-plugin set matches the architecture decision in §9.2 from the start.

tasks.register("packagingPlaceholder") {
    description = "No-op until stage 0.3 wires jlink/jpackage (see dev_plan.md, stage 0.3)."
}
