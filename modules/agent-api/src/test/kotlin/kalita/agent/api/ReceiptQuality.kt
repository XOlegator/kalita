// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import java.math.BigDecimal
import java.time.LocalDate

internal data class QualityLine(val text: String, val amount: BigDecimal)
internal data class QualityReceipt(val date: LocalDate?, val total: BigDecimal?, val lines: List<QualityLine>)
internal data class QualityMetrics(val dateAccuracy: Double, val totalAccuracy: Double, val lineF1: Double, val exactReceiptRate: Double)

/** Exact matching with multiplicity; amounts compare numerically rather than by decimal scale. */
internal fun receiptQuality(pairs: List<Pair<QualityReceipt, QualityReceipt>>): QualityMetrics {
    require(pairs.isNotEmpty()) { "At least one receipt is required" }
    var dates = 0
    var totals = 0
    var exact = 0
    var matched = 0
    var expectedCount = 0
    var actualCount = 0
    fun sameAmount(a: BigDecimal?, b: BigDecimal?) = a != null && b != null && a.compareTo(b) == 0
    pairs.forEach { (expected, actual) ->
        require(expected.date != null && expected.total != null) { "Ground truth must include date and total" }
        val dateMatches = expected.date == actual.date
        val totalMatches = sameAmount(expected.total, actual.total)
        if (dateMatches) dates++
        if (totalMatches) totals++
        val remaining = actual.lines.toMutableList()
        var matches = 0
        expected.lines.forEach { line ->
            val index = remaining.indexOfFirst { it.text == line.text && sameAmount(it.amount, line.amount) }
            if (index >= 0) { remaining.removeAt(index); matches++ }
        }
        matched += matches
        expectedCount += expected.lines.size
        actualCount += actual.lines.size
        if (dateMatches && totalMatches && matches == expected.lines.size && remaining.isEmpty()) exact++
    }
    val count = pairs.size.toDouble()
    val lineCount = expectedCount + actualCount
    return QualityMetrics(dates / count, totals / count, if (lineCount == 0) 1.0 else 2.0 * matched / lineCount, exact / count)
}