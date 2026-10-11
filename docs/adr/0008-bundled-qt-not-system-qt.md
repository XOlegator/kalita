<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

# ADR-0008: Desktop ships its own Qt + QtJambi, not the system's

- Status: Accepted
- Date: 2026-10-11
- See also: dev_plan.md NFR-6, R21, stage 0.2, ADR-0004

## Context

On Linux, QtJambi links the host's actual `libQt6*.so` rather than bundling Qt itself, and requires
an exact minor-version match between QtJambi and that Qt (confirmed first-hand building stage 0.2:
QtJambi 6.12.0 against a system Qt 6.10.2 fails with `UnsatisfiedLinkError: Mismatching versions`).
That match can't be delegated to "whatever the target distro ships": Ubuntu 24.04 "noble" ships Qt
6.4.2, Ubuntu 26.04 "resolute" ships Qt 6.10.2 — and QtJambi has no Maven Central release below
6.8.10 at all, so 24.04's system Qt has no matching QtJambi build to even try. The two target LTS
releases (NFR-2) are incompatible with each other on this axis, and the older one is incompatible
with QtJambi outright.

## Decision

The packaged desktop application bundles its own Qt and QtJambi (jlink + jpackage, §13.4) and does
not declare or rely on a system Qt dependency, on either target Ubuntu version. NFR-6's "≤200MB:
JRE + Qt + QtJambi" already specified this; this ADR makes it a hard requirement rather than a size
optimization, and the Linux `.deb` built for the eventual PPA follows the same rule — one build
artifact works unmodified on both `noble` and `resolute`, with no per-series Qt split (unlike, say,
native code that must link each distro's own library ABI).

`gradle/libs.versions.toml`'s `qtjambi` version and `kalita.qtjambi-app.gradle.kts`'s
`-PqtjambiVersion=` override exist specifically so this pin can be validated against more than one
QtJambi/Qt pair without editing build files — see that file's comments.

## Consequences

- End users never need a particular system Qt installed; the packaged app works identically on
  24.04 and 26.04 (and in principle any Linux new enough for the bundled Qt's own baseline).
- Local development via `./gradlew :modules:app-desktop:run` is the one place this ADR doesn't
  (yet) apply — it still resolves QtJambi's native jar and links the *system* Qt, so it only works
  out of the box on a machine whose system Qt happens to match the pinned QtJambi version (today:
  26.04-class systems with Qt 6.10.2; not 24.04). Giving `./gradlew run` a private, bundled Qt too
  — so dev and packaged runs use identical binaries — is open work, not yet done.
- The pinned QtJambi/Qt version can only move together with a Qt release QtJambi actually publishes
  (≥6.8.10); it cannot be driven by "whatever a target distro ships" (see R21).
