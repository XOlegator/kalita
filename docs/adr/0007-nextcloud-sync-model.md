<!--
SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
SPDX-License-Identifier: GPL-3.0-only
-->

# ADR-0007: Nextcloud sync — local copy + ChangeSet queue + rebase

- Status: Accepted
- Date: 2026-10-10
- See also: dev_plan.md §10.1–§10.4, ADR-0005

## Context

Two or more devices (e.g. a desktop and a phone) need to add and edit operations against the same
`Data.mdb` without losing or duplicating anything, while the only shared storage available is a
Nextcloud WebDAV share. `.mdb` can't be opened in place by multiple writers — WebDAV gives no byte
range locks, and Jackcess doesn't support concurrent writers (dev_plan.md §3.2 item 7) — so there is
no "open the shared file directly" option. What Nextcloud does offer: the file is always downloaded
and uploaded whole (cheap at the 1–10MB sizes in scope), and SabreDAV honors conditional requests —
an upload with `If-Match: <ETag>` is rejected with 412 if someone else changed the file first.

## Decision

Each device keeps a local base copy of the database plus a disk-backed queue of unsent ChangeSets
(ADR-0005) — the same shape `git pull --rebase` uses:

1. User input always lands in the local queue first and renders immediately; the device never
   blocks on the network.
2. To sync: `PROPFIND` for the current ETag; if it moved, `GET` the new version; apply the queued
   ChangeSets to that copy (resolving `tempKey`s to real IDs, checking for base-value conflicts —
   §10.5) and verify (ADR-0002); `PUT` with `If-Match: <ETag>`.
3. A 412 means someone else wrote in the meantime — restart from the `GET` with the newer ETag.
4. Success clears the local queue and advances the local base to the new version.
5. Polling (`PROPFIND` every 15–30s while active, on open, instant via `notify_push` where
   installed) detects remote changes even when this device isn't the one writing.

## Consequences

- Non-conflicting concurrent edits never conflict by construction: new-row IDs are assigned at
  apply time against whichever base turned out to be current, so two devices adding different rows
  simply both succeed, one after the other, even if they typed at the same instant (§10.3 walks a
  concrete two-device timeline).
- A real conflict (both sides changed the same field) surfaces explicitly rather than silently
  picking a winner, because ChangeSets carry the base value the edit assumed (ADR-0005).
- The whole protocol assumes Nextcloud's `If-Match`/412 behavior actually holds on the user's
  server and version — unverified until stage 9 (R11). If it doesn't, the fallback is a
  single-writer mode guarded by a WebDAV `LOCK`, not a redesign of this ADR.
- `storage-local` is this same protocol with "the server" replaced by a local file and file-change
  detection replacing ETag polling (§9.5) — one sync algorithm, two transports.
