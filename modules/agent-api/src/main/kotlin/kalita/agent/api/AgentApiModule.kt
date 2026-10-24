// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

// Tool layer for AI agents (dev_plan.md §12.12-§12.14, stage 7a slice 1): one channel-agnostic
// implementation of the read/write tools of §12.13, shared by the `kalita` CLI (`modules:app-cli`)
// today and, later, by `kalita mcp`/`kalita-server`/the built-in advisor (§12.10) without change.
//
// Deferred to a later slice, not stubbed here (§12.13's remaining rows):
// - `get_spending_summary` — needs the level-1.5 analytics machinery (§12.8), not built yet.
// - `update_operation` / `delete_operation` / `restore_operation` — this slice is additive-only,
//   matching the plan's own worked example (§12.17); editing/undoing existing rows needs more
//   thought about agent-authored changes to data a human may already be looking at.
// - `add_dictionary_item` — "only by explicit call, never 'along the way'" (§12.14); needs its own
//   careful tool, not a side effect of `add_operations`.
// - `recognize_receipt` — stage 14 (receipt recognition).
// - `undo_changes` — needs a reverse-change-set mechanism that does not exist yet.
package kalita.agent.api
