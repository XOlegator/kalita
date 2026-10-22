// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import kalita.core.Account
import kalita.core.BudgetItem
import kalita.core.Counterparty
import kalita.core.Currency
import kalita.core.Dataset
import kalita.core.Operation
import kalita.core.OperationType
import kalita.core.Rate
import java.math.BigDecimal
import java.time.LocalDate

/** Minimal fixture for presentation tests, mirroring the demo journal of dev_plan.md §5.2. */
object DemoPresentationData {
    const val USD = 11L
    const val EUR = 12L
    const val RUB = 13L

    const val INCOME = 1L
    const val EXPENSE = 2L
    const val TRANSFER = 8L

    const val MAIN = 21L
    const val HUSBAND = 22L

    const val EXCHANGERS = 31L

    const val ROOT_INCOME = 41L
    const val ROOT_EXPENSE = 42L
    const val ROOT_TRANSFER = 43L
    const val OPENING_BALANCE = 44L
    const val EXCHANGE_IN = 45L
    const val EXCHANGE_OUT = 46L

    private val jan1: LocalDate = LocalDate.of(2006, 1, 1)
    private val jan26: LocalDate = LocalDate.of(2006, 1, 26)

    val dataset = Dataset(
        operations = listOf(
            Operation(100, jan26, BigDecimal("100"), USD, ROOT_TRANSFER, TRANSFER, accountFrom = MAIN, accountTo = HUSBAND),
            Operation(101, jan26, BigDecimal("100"), USD, EXCHANGE_OUT, EXPENSE, accountFrom = HUSBAND, counterpartyId = EXCHANGERS),
            Operation(102, jan26, BigDecimal("2860"), RUB, EXCHANGE_IN, INCOME, accountTo = HUSBAND, counterpartyId = EXCHANGERS),
            Operation(103, jan1, BigDecimal("1000"), USD, OPENING_BALANCE, INCOME, accountTo = MAIN),
            Operation(104, jan1, BigDecimal("5000"), RUB, OPENING_BALANCE, INCOME, accountTo = MAIN),
            Operation(199, jan1, BigDecimal("999"), RUB, ROOT_EXPENSE, EXPENSE, accountFrom = MAIN, deleted = true),
        ),
        operationTypes = listOf(
            OperationType(INCOME, "Приход", ROOT_INCOME, 1),
            OperationType(EXPENSE, "Расход", ROOT_EXPENSE, -1),
            OperationType(TRANSFER, "Перемещение", ROOT_TRANSFER, 0),
        ),
        budgetItems = listOf(
            BudgetItem(ROOT_INCOME, null, "Приход", 1),
            BudgetItem(ROOT_TRANSFER, null, "Перемещение", 2),
            BudgetItem(ROOT_EXPENSE, null, "Расход", 3),
            BudgetItem(OPENING_BALANCE, ROOT_INCOME, "Ввод остатков", 1),
            BudgetItem(EXCHANGE_IN, ROOT_INCOME, "Обмен валюты", 2),
            BudgetItem(EXCHANGE_OUT, ROOT_EXPENSE, "Обмен валюты", 1),
        ),
        accounts = listOf(Account(MAIN, "Основной"), Account(HUSBAND, "Муж")),
        counterparties = listOf(Counterparty(EXCHANGERS, "Обменники")),
        currencies = listOf(
            Currency(USD, "Доллар", "R01235"),
            Currency(EUR, "Евро", "R01239"),
            Currency(RUB, "Рубль", null),
        ),
        rates = listOf(
            Rate(201, USD, jan1, 28.7825),
            Rate(202, USD, jan26, 27.9805),
            Rate(203, EUR, jan1, 34.1850),
        ),
    )
}
