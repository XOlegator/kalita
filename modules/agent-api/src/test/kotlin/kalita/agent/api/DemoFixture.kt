// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kalita.storage.api.ChangeSet
import kalita.storage.api.Op
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import kalita.storage.mdb.AtomicCommit
import kalita.storage.mdb.CommitResult
import kalita.storage.mdb.createXCashSchemaFixture
import java.io.File
import java.time.Instant
import java.util.UUID

/** Dictionary rows a demo fixture sets up, with the real ids Jackcess assigned them. */
internal data class DemoIds(
    val accountCard: Long,
    val accountCash: Long,
    val currencyRub: Long,
    val budgetFood: Long,
    val budgetCafe: Long,
    val budgetTransport: Long,
    val typeExpense: Long,
    val typeIncome: Long,
    val typeTransfer: Long,
    val counterpartyIvan: Long,
)

/**
 * An empty schema (dev_plan.md appendix A) plus one of everything a test needs: two accounts, one
 * (base) currency, a two-level budget tree, three operation types (one per kind, unrestricted —
 * `budgetRootId = null`) and one counterparty. No operations — tests add their own via
 * [AgentSession.submit] so each test's data stays close to its assertions.
 *
 * Built through [AtomicCommit] (the same path [kalita.devcli.CliTest] uses for test rows), not raw
 * Jackcess — agent-api has no (and should have no) direct dependency on Jackcess's internal API.
 */
internal fun buildDemoFixture(file: File): DemoIds {
    createXCashSchemaFixture(file)
    val ops = listOf(
        Op.Insert(Table.Accounts, TempKey("card"), mapOf("Name" to "Карта", "del" to false)),
        Op.Insert(Table.Accounts, TempKey("cash"), mapOf("Name" to "Наличные", "del" to false)),
        Op.Insert(Table.Currencies, TempKey("rub"), mapOf("Name" to "RUB", "del" to false)),
        Op.Insert(Table.BudgetItems, TempKey("food"), mapOf("Name" to "Еда", "Parent_ID" to null, "del" to false)),
        Op.Insert(Table.BudgetItems, TempKey("cafe"), mapOf("Name" to "Кафе", "Parent_ID" to TempKey("food"), "del" to false)),
        Op.Insert(Table.BudgetItems, TempKey("transport"), mapOf("Name" to "Транспорт", "Parent_ID" to null, "del" to false)),
        Op.Insert(Table.OperationTypes, TempKey("expense"), mapOf("Name" to "Расход", "PlusMinus" to -1L, "del" to false)),
        Op.Insert(Table.OperationTypes, TempKey("income"), mapOf("Name" to "Доход", "PlusMinus" to 1L, "del" to false)),
        Op.Insert(Table.OperationTypes, TempKey("transfer"), mapOf("Name" to "Перевод", "PlusMinus" to 0L, "del" to false)),
        Op.Insert(Table.People, TempKey("ivan"), mapOf("Name" to "Иван", "del" to false)),
    )
    val cs = ChangeSet(UUID.randomUUID(), "fixture", null, Instant.EPOCH, "demo fixture", ops)
    val result = AtomicCommit.commit(file, listOf(cs))
    check(result is CommitResult.Committed) { "fixture setup failed: $result" }
    fun id(name: String) = result.tempKeys.getValue(TempKey(name))
    return DemoIds(
        accountCard = id("card"),
        accountCash = id("cash"),
        currencyRub = id("rub"),
        budgetFood = id("food"),
        budgetCafe = id("cafe"),
        budgetTransport = id("transport"),
        typeExpense = id("expense"),
        typeIncome = id("income"),
        typeTransfer = id("transfer"),
        counterpartyIvan = id("ivan"),
    )
}
