// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import java.math.BigDecimal

/** How far [AddOperationsTool]/[ApproveInboxTool] may go (dev_plan.md §12.14). */
enum class WriteMode {
    /** `add_operations` should not even have been called; rejected without touching anything. */
    ReadOnly,

    /** Every draft is appended to `inbox.jsonl` — a human confirms in the program (the default). */
    Draft,

    /** Drafts are written straight to the database as one change set. */
    Write,
}

/**
 * Per-operation and per-day amount limits (dev_plan.md §12.14), compared against the stored,
 * unconverted `Summa` — simplicity over cross-currency correctness, since there is no settings UI
 * yet to configure a reduction currency for this check. `null` means unlimited.
 *
 * Not persisted anywhere: there is no "connections" settings UI yet (that needs its own slice), so
 * these are plain parameters the caller (the CLI, today) passes in on every invocation from flags.
 */
data class WriteLimits(
    val maxOperationAmount: BigDecimal? = null,
    val maxDailyAmount: BigDecimal? = null,
)
