// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

// Queue + rebase-onto-latest protocol, merge, conflicts (dev_plan.md §10), Этап 9.
//
// Deviation from §9.2's own sketch, worth stating plainly: that sketch puts the Nextcloud-specific
// WebDAV code in `plugins/nextcloud/` and keeps this module server-agnostic. Neither `plugins/` nor
// a working plugin loader (`plugin-api` is still a placeholder) exist in the repository yet, and
// building that whole mechanism just to host one WebDAV client would be its own multi-week slice,
// not something "sessions and UI" needs. `NextcloudClient`/`LoginFlowV2` live directly in `sync`
// instead — still the only Nextcloud-specific pieces (plain WebDAV, no server-side app required:
// verified live against a real instance), everything else here (the queue, rebase, conflicts,
// `NextcloudStorageSession`) stays protocol-level and doesn't care which WebDAV server it's talking
// to. If/when `plugins/nextcloud` is built for real, moving these two files there is a clean,
// mechanical follow-up — nothing else in `sync` depends on their package.
//
// `NextcloudDatabase`/`NextcloudStorage` reuse `storage-mdb`'s `AtomicCommit`/`MdbEngine` against a
// private local cache file (never the file `storage-local` manages) for the rebase step (§10.4):
// apply the pending queue one change set at a time, stopping at the first conflict, then one `PUT`
// of whatever landed. `PendingQueue` is a whole-file JSON rewrite on every change (the same
// accepted pattern as `AgentConnectionStore`/`InboxStore` elsewhere in this project) rather than
// `ChangeHistory`'s append-only JSONL, because entries also have to be *removed* once confirmed —
// append-only has no way to do that without its own compaction step.
package kalita.sync
