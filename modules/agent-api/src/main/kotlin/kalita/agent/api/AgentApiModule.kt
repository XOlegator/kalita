// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

// Tool layer for AI agents (dev_plan.md §12.12-§12.14, stage 7a slice 1): one channel-agnostic
// implementation of the read/write tools of §12.13, shared by the `kalita` CLI (`modules:app-cli`)
// today and, later, by `kalita mcp`/`kalita-server`/the built-in advisor (§12.10) without change.
//
// Deferred to a later slice, not stubbed here (§12.13's remaining rows):
// - `get_spending_summary` — needs the level-1.5 analytics machinery (§12.8), not built yet.
// - `recognize_receipt` — stage 14 (receipt recognition).
//
// `undo_changes` (UndoChangesTool.kt) reverses one change set found in `ChangeHistory`
// (`kalita.storage.local`, already a dependency) by submitting a new change set of the opposite
// `Op`s — never by erasing the original from history. Scoped to the caller's own `device`: only a
// change set created by that exact device string can be undone this way.
//
// `update_operation`/`delete_operation`/`restore_operation` (OperationWriteTools.kt) and
// `add_dictionary_item` (AddDictionaryItemTool.kt) are write-mode-only — §12.14 says editing,
// deleting, restoring and adding a dictionary item are "only if enabled by the user", unlike
// `add_operations`'s `draft` fallback.
package kalita.agent.api
