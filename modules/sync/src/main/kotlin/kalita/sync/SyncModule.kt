// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

// Queue + rebase-onto-latest protocol, merge, conflicts (dev_plan.md §10), Этап 9. `NextcloudClient`
// is the only Nextcloud-specific piece — plain WebDAV (`PROPFIND`/`GET`/`PUT` with `If-Match`), no
// server-side app required (verified live against a real Nextcloud instance: `If-Match` on a stale
// ETag returns 412 with the current ETag already in the response header, `GET`/`PROPFIND` both
// carry an `OC-Checksum`, `If-None-Match: *` safely refuses to clobber an existing file). The queue
// itself, `rebase` and conflict handling are protocol-level (§10.2/§10.4/§10.5), not tied to any
// one server — a future WebDAV provider only has to implement the same `NextcloudClient`-shaped
// contract, which is why it isn't named after Nextcloud.
//
// Deferred to a later slice, not stubbed here:
// - The pending-change queue and the `StorageSession` implementation that ties this client to
//   `AtomicCommit`/`MdbEngine` for the actual rebase (§10.4) — this slice is the WebDAV primitive
//   and Login Flow v2 only, proven against the real server and a fake one, not yet wired to a
//   session a controller can open.
// - Credential storage (`SecretStore`, `platform` module) — this slice's `NextcloudCredentials` is
//   a plain in-memory value; where the app password is persisted between launches is the next
//   slice's concern, once there is a UI that obtains one.
package kalita.sync
