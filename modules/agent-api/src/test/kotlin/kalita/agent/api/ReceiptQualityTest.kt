// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kalita.core.Dataset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate

class ReceiptQualityTest {
    private val date = LocalDate.of(2026, 1, 5)
    private fun line(text: String, amount: String) = QualityLine(text, BigDecimal(amount))

    @Test
    fun `metrics count missing fields, duplicate lines and extra lines`() {
        val milk = line("Mleko", "100.00")
        val expected = QualityReceipt(date, BigDecimal("200.00"), listOf(milk, milk))
        val exact = QualityReceipt(date, BigDecimal("200"), listOf(line("Mleko", "100"), milk))
        val imperfect = QualityReceipt(null, BigDecimal("201"), listOf(milk, line("Hleb", "100")))
        receiptQuality(listOf(expected to exact, expected to imperfect)) shouldBe QualityMetrics(0.5, 0.5, 0.75, 0.5)
    }

    @Test
    fun `empty line sets and invalid ground truth are handled explicitly`() {
        val receipt = QualityReceipt(date, BigDecimal.ONE, emptyList())
        receiptQuality(listOf(receipt to receipt)) shouldBe QualityMetrics(1.0, 1.0, 1.0, 1.0)
        shouldThrow<IllegalArgumentException> { receiptQuality(emptyList()) }
        shouldThrow<IllegalArgumentException> { receiptQuality(listOf(receipt.copy(date = null) to receipt)) }
        shouldThrow<IllegalArgumentException> { receiptQuality(listOf(receipt.copy(total = null) to receipt)) }
    }

    @Test
    fun `synthetic model replies are scored against independently specified receipts`(@TempDir dir: Path) {
        val samples = listOf(
            QualityReceipt(date, BigDecimal("200"), listOf(line("Mleko", "100"), line("Hleb", "100"))) to
                """{"date":"2026-01-05","total":"200,00","lines":[{"text":"Mleko","amount":"100"},{"text":"Hleb","amount":"100"}]}""",
            QualityReceipt(date, BigDecimal("50"), listOf(line("Kafa", "50"))) to
                """{"date":"2026-01-06","total":"50","lines":[{"text":"Kafa","amount":"45"}]}""",
        )
        val pairs = samples.map { (expected, reply) ->
            val proposal = ReceiptRecognizer().importReply(reply, Dataset.EMPTY, dir.toFile(), "quality-test")
            expected to QualityReceipt(proposal.date, proposal.total, proposal.lines.map { QualityLine(it.text, it.amount) })
        }
        receiptQuality(pairs) shouldBe QualityMetrics(0.5, 1.0, 2.0 / 3.0, 0.5)
    }
}