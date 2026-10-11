<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

# ADR-0002: Atomic commit for `Data.mdb` writes

- Status: Accepted
- Date: 2026-10-10
- See also: dev_plan.md §9.6, §9.5, R1

## Context

`Data.mdb` must stay readable and writable by the original X-Cash at all times (the hard constraint
behind the whole rewrite — AGENTS.md), and corrupting a user's real financial data is the project's
single worst failure mode (R1, "Критическое"). Neither Jet nor Jackcess keeps a write-ahead log, so
a crash or a killed process mid-write can leave the file in a half-written, unreadable state. Bases
are small (units to tens of MB), which makes a copy-then-replace strategy cheap enough to always use.

## Decision

Every commit to `Data.mdb` goes through the same five-step sequence, never a direct in-place write:

1. Copy the working file to a temp file (`<db>.kalita-tmp`) in the same directory (reflink on
   btrfs/ReFS, else a plain copy — ~10ms for 5MB).
2. Jackcess opens the temp file, applies the pending ChangeSets (ADR-0005), then `flush()` + `close()`.
3. Reopen the temp file fresh and verify: schema fingerprint, row counts, presence of the rows that
   changed, a full index walk on every touched table.
4. `fsync` the temp file, atomically replace the working file (`Files.move(ATOMIC_MOVE,
   REPLACE_EXISTING)` / `ReplaceFileW`), `fsync` the containing directory.
5. Any failure at any step deletes the temp file and leaves the working file untouched; the
   ChangeSet stays queued and the user sees an error, never a partial write.

Android follows the same five steps against the app's internal storage; a file picked via SAF can't
be replaced atomically, so it's imported into internal storage first and "Export" writes a verified
copy back out.

## Consequences

- The working file is only ever touched by a single atomic rename, so a crash at any point before
  step 4 leaves it exactly as it was.
- Every commit costs one extra copy and one extra open-and-verify pass; acceptable at the sizes in
  scope (NFR-3 budgets 300ms for a commit on a 50MB base).
- The verification pass (step 3) is the project's main defense against a Jackcess bug silently
  corrupting data — it's also where `oracle-jet`'s Microsoft Jet cross-checks plug in (dev_plan.md
  §14.1 item 6).
- This is the one place every storage backend (`storage-local`, and `storage-mdb` read through
  `sync`) must agree on; a future backend that can't offer copy+atomic-rename semantics needs its
  own ADR before shipping.
