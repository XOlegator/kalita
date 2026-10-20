// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import io.kotest.matchers.shouldBe
import kalita.core.Aggregate
import kalita.core.JournalColumns
import kalita.core.RateService
import kalita.core.SaldoService
import kalita.core.aggregate
import kalita.storage.api.ChangeSet
import kalita.storage.api.Op
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import kalita.storage.api.toDataset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

// The domain's reference numbers (dev_plan.md §5.2–5.3) through a real Jet file: the demo journal is
// written by AtomicCommit, read back by MdbSnapshotReader and computed by core — so Jet's Currency and
// Double columns, NULL references and soft deletion all reach the domain intact.
class DemoJournalRoundTripTest {
    private val ops = ArrayList<Op>()
    private var seq = 0

    private fun insert(table: Table, values: Map<String, Any?>): TempKey =
        TempKey("${table.name}-${seq++}").also { ops += Op.Insert(table, it, values + ("del" to (values["del"] ?: false))) }

    private fun day(d: Int, m: Int = 1) = LocalDateTime.of(2006, m, d, 0, 0)

    @Test
    fun `demo journal written to Jet gives the reference totals`(@TempDir dir: Path) {
        val usd = insert(Table.Currencies, mapOf("Name" to "Доллар", "CB_ID" to "R01235"))
        val rub = insert(Table.Currencies, mapOf("Name" to "Рубль"))
        insert(Table.Rates, mapOf("Currency_ID" to usd, "Date" to day(1), "Course" to 28.7825))
        insert(Table.Rates, mapOf("Currency_ID" to usd, "Date" to day(26), "Course" to 27.9805))
        insert(Table.Rates, mapOf("Currency_ID" to usd, "Date" to day(1, 3), "Course" to 26.9351))
        val income = insert(Table.OperationTypes, mapOf("Name" to "Приход", "PlusMinus" to 1L))
        val expense = insert(Table.OperationTypes, mapOf("Name" to "Расход", "PlusMinus" to -1L))
        val transfer = insert(Table.OperationTypes, mapOf("Name" to "Перемещение", "PlusMinus" to 0L))
        val item = insert(Table.BudgetItems, mapOf("Name" to "Статья", "Ord" to 1L))
        val (main, husband, wife, child, bank) =
            listOf("Основной", "Муж", "Жена", "Ребенок", "Счет в банке").map { insert(Table.Accounts, mapOf("Name" to it)) }

        fun op(date: LocalDateTime, sum: String, cur: TempKey, type: TempKey, from: TempKey? = null, to: TempKey? = null, del: Boolean = false) =
            insert(
                Table.Operations,
                mapOf(
                    "Date" to date, "Summa" to BigDecimal(sum), "Currency_ID" to cur, "TypeOper_ID" to type,
                    "GroupBudget_ID" to item, "AccountFrom" to from, "AccountTo" to to, "del" to del,
                ),
            )
        op(day(26), "100", usd, transfer, from = main, to = husband)
        op(day(26), "100", usd, expense, from = husband)
        op(day(26), "2860", rub, income, to = husband)
        op(day(26), "2860", rub, transfer, from = husband, to = main)
        op(day(6), "200", rub, transfer, from = husband, to = child)
        op(day(1), "1000", usd, income, to = main)
        op(day(1), "5000", rub, income, to = main)
        op(day(1), "1000", rub, income, to = husband)
        op(day(1), "2000", rub, income, to = wife)
        op(day(1), "50000", rub, income, to = bank)
        op(day(6), "999", rub, expense, from = wife, del = true)

        val file = createXCashSchemaFixture(dir.resolve("Data.mdb").toFile())
        val committed = AtomicCommit.commit(file, listOf(ChangeSet(UUID.randomUUID(), "test", null, Instant.EPOCH, "demo", ops)))
        val ids = (committed as CommitResult.Committed).tempKeys

        val data = MdbSnapshotReader.read(file).toDataset()
        data.detectBaseCurrency() shouldBe ids.getValue(rub)
        val rates = RateService.of(data)
        val columns = JournalColumns(data, rates, ids.getValue(rub))
        val visible = data.operations.filter { !it.deleted }.map(columns::values)

        visible.size shouldBe 10
        aggregate(visible.map { it.signedAmount }, Aggregate.Sum).cents() shouldBe BigDecimal("64920.00")
        aggregate(visible.map { it.convertedAmount }, Aggregate.Sum).cents() shouldBe BigDecimal("92702.50")

        val total = SaldoService.compute(data, rates, ids.getValue(rub)).total
        total.received.cents() shouldBe BigDecimal("93548.61")
        total.spent.cents() shouldBe BigDecimal("8447.02")
        total.balance.cents() shouldBe BigDecimal("85101.59")
    }

    private fun BigDecimal?.cents() = this?.setScale(2, RoundingMode.HALF_UP)
}
