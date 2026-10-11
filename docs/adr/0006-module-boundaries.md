<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

# ADR-0006: Module boundaries enforced by Konsist

- Status: Accepted
- Date: 2026-10-11
- See also: dev_plan.md §9.2, §9.3

## Context

ADR-0004's whole "replace one shell without touching the rest" argument, and ADR-0003's "swap the
Jackcess engine without touching callers" argument, only hold if the dependency boundaries they
assume are real — a `core` class that quietly starts calling into `io.qt.*` would make both claims
false. Gradle module boundaries alone don't prevent this: nothing stops `core` from declaring a
dependency on `app-desktop` by mistake, or a developer adding one under time pressure "just this
once".

## Decision

Fix the dependency direction as:

```
app-desktop ─┐                 ┌─ plugins/* ──┐
app-android ─┼─► presentation ─┼─► plugin-api ─┼─► storage-api ─► core
app-cli ─────┤   agent-api ────┘               │
app-server ──┘
             │                 │               │
             └─► platform ◄────┴───────────────┘   storage-local/storage-mdb/sync ─► storage-api, core
```

with three hard rules: `core` is pure Kotlin with no IO and no third-party dependencies; Jackcess
(`io.github.spannm.jackcess.*`) is imported **only** in `storage-mdb`; `io.qt.*` is imported
**only** in `app-desktop`; `android.*`/`androidx.*` are imported **only** in `app-android` and
Android-specific `platform` source sets. These aren't just Gradle `implementation` edges — Konsist
checks the actual import graph in CI, so a forbidden import fails the build even though it would
compile.

## Consequences

- Gives ADR-0004 (shell swap) and ADR-0003 (engine swap) their teeth: the modules those ADRs assume
  are isolated are mechanically verified to stay isolated, not just isolated by convention.
- A genuinely new cross-cutting need (e.g. a platform API that several shells want) goes through
  `platform`'s per-platform implementations, not a new direct edge between unrelated modules.
- The Konsist check doesn't exist yet as of this ADR (dev_plan.md stage 0.5/2) — until it lands,
  this boundary is enforced only by code review discipline, which is exactly the gap the check is
  meant to close.
