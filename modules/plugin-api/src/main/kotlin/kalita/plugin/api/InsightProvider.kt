// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api

import kalita.core.Dataset
import java.time.LocalDate

/** The period an [InsightProvider] is asked to analyze (dev_plan.md §12.8). */
data class InsightPeriod(val from: LocalDate, val to: LocalDate)

/**
 * Journal data → analysis cards (dev_plan.md §12.7–§12.9): overview, category breakdown,
 * trends, anomalies, recurring payments, forecasts, budgets and goals — none of it requires an
 * AI model (that is [AiProvider]'s job, built on top of the same read-only data). An
 * [InsightProvider] never writes to the database directly; [runAction] on a card's
 * [ReportCard.Action] still only returns more cards or hands back to the shell, which turns a
 * user-confirmed suggestion into an ordinary change set (dev_plan.md §9.5).
 */
interface InsightProvider {
    suspend fun analyze(dataset: Dataset, period: InsightPeriod): List<ReportCard>

    suspend fun runAction(actionId: String, dataset: Dataset, period: InsightPeriod): List<ReportCard>
}
