// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api

/**
 * Declarative plugin output (dev_plan.md §9.11, §12.9): a plugin never returns screens, only a
 * model the shell renders with its own widgets (Qt Widgets + Qt Charts on the desktop,
 * Jetpack Compose + Vico on Android) — the same [ReportCard] list looks right on both.
 */
sealed interface ReportCard {
    /** Shown above the card; also what "Показать операции"-style actions scroll back to. */
    val title: String

    data class Kpi(
        override val title: String,
        val value: String,
        /** e.g. "+12% vs last month" — purely informational, the shell does not parse it. */
        val trend: String? = null,
    ) : ReportCard

    data class Table(
        override val title: String,
        val columns: List<String>,
        val rows: List<List<String>>,
    ) : ReportCard

    enum class ChartKind { Line, Bar, Pie, Treemap }

    data class Chart(
        override val title: String,
        val kind: ChartKind,
        /** One series per label (pie/treemap: one point per label, the point value is its single entry). */
        val series: Map<String, List<Double>>,
        val xLabels: List<String> = emptyList(),
    ) : ReportCard

    data class Text(override val title: String, val body: String) : ReportCard

    /**
     * A button the user can press; [actionId] is opaque to the shell and re-submitted to the
     * plugin verbatim (e.g. via [InsightProvider.runAction]) so the plugin decides what it means
     * ("Создать бюджет по предложению", "Показать операции" — §12.9).
     */
    data class Action(override val title: String, val actionId: String, val label: String) : ReportCard
}
