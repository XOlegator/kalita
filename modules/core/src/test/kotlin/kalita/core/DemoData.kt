// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import java.math.BigDecimal
import java.time.LocalDate

/**
 * A synthetic database shaped like the demo journal in dev_plan.md §5.2–5.3: the same accounts,
 * operations and rates, so the reference numbers quoted there (64 920,00; 92 702,50; ИТОГО
 * 93 548,61 / 8 447,02 / 85 101,59) can be checked. Ids are deliberately not the original's
 * except for the operation types, which the plan names (and which must not matter anyway).
 */
object DemoData {
    const val USD = 11L
    const val EUR = 12L
    const val RUB = 13L
    const val UAH = 14L

    const val INCOME = 1L
    const val EXPENSE = 2L
    const val TRANSFER = 8L

    const val MAIN = 21L
    const val HUSBAND = 22L
    const val WIFE = 23L
    const val CHILD = 24L
    const val BANK = 25L

    const val EXCHANGERS = 31L

    const val ROOT_INCOME = 41L
    const val ROOT_EXPENSE = 42L
    const val ROOT_TRANSFER = 43L
    const val OPENING_BALANCE = 44L
    const val EXCHANGE_IN = 45L
    const val EXCHANGE_OUT = 46L

    val jan1: LocalDate = LocalDate.of(2006, 1, 1)
    val jan6: LocalDate = LocalDate.of(2006, 1, 6)
    val jan26: LocalDate = LocalDate.of(2006, 1, 26)

    private var nextId = 100L

    private fun op(
        date: LocalDate,
        amount: String,
        currency: Long,
        type: Long,
        item: Long,
        from: Long? = null,
        to: Long? = null,
        people: Long? = null,
        comment: String? = null,
        deleted: Boolean = false,
    ) = Operation(nextId++, date, BigDecimal(amount), currency, item, type, comment, people, from, to, deleted)

    val operations = listOf(
        op(jan26, "100", USD, TRANSFER, ROOT_TRANSFER, from = MAIN, to = HUSBAND),
        op(jan26, "100", USD, EXPENSE, EXCHANGE_OUT, from = HUSBAND, people = EXCHANGERS),
        op(jan26, "2860", RUB, INCOME, EXCHANGE_IN, to = HUSBAND, people = EXCHANGERS),
        op(jan26, "2860", RUB, TRANSFER, ROOT_TRANSFER, from = HUSBAND, to = MAIN),
        op(jan6, "200", RUB, TRANSFER, ROOT_TRANSFER, from = HUSBAND, to = CHILD, comment = "На мелкие расходы"),
        op(jan1, "1000", USD, INCOME, OPENING_BALANCE, to = MAIN),
        op(jan1, "5000", RUB, INCOME, OPENING_BALANCE, to = MAIN),
        op(jan1, "1000", RUB, INCOME, OPENING_BALANCE, to = HUSBAND),
        op(jan1, "2000", RUB, INCOME, OPENING_BALANCE, to = WIFE),
        op(jan1, "50000", RUB, INCOME, OPENING_BALANCE, to = BANK),
    )

    /** A soft-deleted expense that must not show in any number. */
    val deletedOperation = op(jan6, "999", RUB, EXPENSE, ROOT_EXPENSE, from = WIFE, deleted = true)

    val dataset = Dataset(
        operations = operations + deletedOperation,
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
        accounts = listOf(
            Account(MAIN, "Основной"),
            Account(HUSBAND, "Муж"),
            Account(WIFE, "Жена"),
            Account(CHILD, "Ребенок"),
            Account(BANK, "Счет в банке"),
        ),
        counterparties = listOf(Counterparty(EXCHANGERS, "Обменники")),
        currencies = listOf(
            Currency(USD, "Доллар", "R01235"),
            Currency(EUR, "Евро", "R01239"),
            Currency(RUB, "Рубль", null),
            Currency(UAH, "Украинская гривна", "R01720"),
        ),
        rates = listOf(
            Rate(201, USD, jan1, 28.7825),
            Rate(202, USD, jan26, 27.9805),
            Rate(203, USD, LocalDate.of(2006, 3, 1), 26.9351),
            Rate(204, EUR, jan1, 34.1850),
            Rate(205, EUR, jan26, 33.9050),
            Rate(206, UAH, jan1, 5.6979),
            // A deleted rate is ignored even though it is the latest.
            Rate(207, USD, LocalDate.of(2006, 4, 1), 99.0, deleted = true),
        ),
    )
}
