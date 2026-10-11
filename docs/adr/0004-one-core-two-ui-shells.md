<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

# ADR-0004: One core, two UI shells — QtJambi (desktop) and Jetpack Compose (Android)

- Status: Accepted
- Date: 2026-10-10
- See also: dev_plan.md §8.4, ADR-0006

## Context

X-Cash's desktop UI relies on dockable panels the user rearranges and saves (`QDockWidget`,
`QMainWindow.saveState()`), mature grids and trees for a journal that can hold 100,000+ rows, and a
look consistent with Windows and KDE — a hard requirement, not a preference. Android needs a native
Material 3 experience distributed through F-Droid, which requires an Apache-2.0-or-similar UI stack
it can build reproducibly from source. No single UI framework satisfies both: Compose Multiplatform
has no ready docking (it would have to be built), and QtJambi's Android support ships prebuilt
native Qt libraries from Maven — incompatible with F-Droid's build-from-source requirement.

## Decision

**One core, two thin shells.** All data, business rules, screen state, and plugin logic live in
platform-independent modules (`core`, `storage-*`, `presentation`, `plugin-api` — ADR-0006); each
shell only binds that state to its platform's widgets:

- Desktop (Windows, Linux): **Qt 6 Widgets via QtJambi**, 6.12 generation.
- Android: **Jetpack Compose + Material 3**, no QtJambi, no bundled native Qt.

## Consequences

- `core` and `presentation` have zero dependency on `io.qt.*` or `android.*`/`androidx.*`
  (ADR-0006, enforced by Konsist in CI) — this is what makes the fallback in the next bullet
  actually cheap rather than theoretical.
- If QtJambi stops being maintained (R2), the desktop shell can be replaced with Compose Desktop in
  an estimated 4–6 weeks without touching the rest of the codebase — this is the whole reason the
  shell/core split exists.
- QtJambi itself carries its own packaging risk (ADR-0008): it only ships Qt 6.8+ on Maven Central,
  and links the host's system Qt libraries on Linux rather than bundling them.
- Desktop and Android screens are built and tested independently (`ui-desktop` vs. Compose UI tests,
  dev_plan.md §13.3) but share every behavioral test in `presentation`, since that layer has no UI
  dependency to fake.
