<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

# ADR-0005: ChangeSets as the storage-layer foundation

- Status: Accepted
- Date: 2026-10-10
- See also: dev_plan.md §9.5, §10.2–§10.4, AGENTS.md

## Context

Two needs point at the same data structure. First, multi-device sync (ADR-0007) needs a unit of
work that can be queued offline, replayed against a newer base version of the database, and have
its row references ("the category I just created") resolved once real IDs exist on the server —
`.mdb` can't be opened by two writers at once, so sync works by rebasing queued local work onto
whatever is on the server, like `git pull --rebase`. Second, bank reconciliation must never silently
overwrite a user's edits to an operation (AGENTS.md's standing rule), which requires knowing each
field's value *before* a change, not just its current value.

## Decision

Every user action becomes one `ChangeSet`: an ordered list of `Op`s (`Insert`, `Update`,
`SetDeleted`, `HardDelete`), each carrying the **base value** of what it touches alongside the new
one, and new rows are addressed by a `tempKey` until `MdbEngine.apply()` assigns a real `id`. This
is the data layer's foundational structure from the MVP onward (AGENTS.md), not something bolted on
when sync ships: `storage-local` (single device) applies the same ChangeSets as the eventual
Nextcloud path, just against a local file instead of a downloaded server copy.

## Consequences

- Field-level base values make three things possible for free: conflict detection during rebase
  (base ≠ current server value ⇒ conflict, §10.5), a real edit history, and a future undo —
  explicitly deferred past MVP (§1.3) but cheap specifically because this structure already exists.
- `tempKey` references let a single user action spanning multiple new rows (e.g. "create a category
  and immediately log an expense against it") stay one atomic ChangeSet instead of two coupled ones.
- Bank reconciliation reads and writes through ChangeSets like any other actor, so it can never
  clobber a field a user already edited without that showing up as a conflict.
- Every storage backend must speak ChangeSets through the `storage-api` contracts
  (`StorageProvider`/`StorageSession`, §9.5) — a backend that can't represent "apply this ChangeSet,
  tell me the tempKey→id mapping and any conflicts" doesn't fit this architecture without a new ADR.
