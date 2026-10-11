<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

# Architecture Decision Records

Durable architectural decisions for Kalita, in [MADR](https://adr.github.io/madr/)-lite form:
Context / Decision / Consequences. Full rationale, numbers, and alternatives considered usually
live in `dev_plan.md` (outside this repository); each ADR links to the section it distills.

An ADR is immutable once accepted — a changed decision gets a new ADR that supersedes the old one,
rather than an edit.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-gpl-3.0-only-license.md) | GPL-3.0-only project license | Accepted |
| [0002](0002-atomic-mdb-commit.md) | Atomic commit for `Data.mdb` writes | Accepted |
| [0003](0003-kotlin-and-jackcess-for-jet4.md) | Kotlin + Jackcess (spannm fork) for Jet4 | Accepted |
| [0004](0004-one-core-two-ui-shells.md) | One core, two UI shells: QtJambi + Jetpack Compose | Accepted |
| [0005](0005-changesets-as-storage-foundation.md) | ChangeSets as the storage-layer foundation | Accepted |
| [0006](0006-module-boundaries.md) | Module boundaries enforced by Konsist | Accepted |
| [0007](0007-nextcloud-sync-model.md) | Nextcloud sync: local copy + changeset queue + rebase | Accepted |
| [0008](0008-bundled-qt-not-system-qt.md) | Desktop ships its own Qt + QtJambi, not the system's | Accepted |
