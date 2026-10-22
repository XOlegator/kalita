// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import kalita.core.JournalColumn
import kalita.core.ValidationError

/** UI string resources (dev_plan.md §9.8). Default implementation is Russian — the only language. */
object Strings {

    fun columnName(column: JournalColumn): String = when (column) {
        JournalColumn.Date -> "Дата"
        JournalColumn.AccountFrom -> "Счёт-Откуда"
        JournalColumn.Amount -> "Сумма"
        JournalColumn.ConvertedAmount -> "Сумма в пересчёте"
        JournalColumn.OperationType -> "Тип операции"
        JournalColumn.BudgetItem -> "Статья бюджета"
        JournalColumn.Currency -> "Валюта"
        JournalColumn.Counterparty -> "Кто/Кому"
        JournalColumn.Comment -> "Комментарий"
        JournalColumn.AccountTo -> "Счёт-Куда"
        JournalColumn.Rate -> "Курс"
    }

    fun validationError(error: ValidationError): String = when (error) {
        ValidationError.MissingDate -> "Укажите дату"
        ValidationError.MissingAmount -> "Укажите сумму"
        ValidationError.NonPositiveAmount -> "Сумма должна быть больше нуля"
        ValidationError.MissingCurrency -> "Укажите валюту"
        ValidationError.MissingBudgetItem -> "Укажите статью бюджета"
        ValidationError.MissingOperationType -> "Укажите тип операции"
        ValidationError.MissingAccountFrom -> "Укажите счёт-откуда"
        ValidationError.MissingAccountTo -> "Укажите счёт-куда"
        ValidationError.BudgetItemOutsideTypeSubtree -> "Статья не относится к этому типу операции"
    }

    const val JOURNAL_TITLE = "Журнал операций"
    const val SALDO_TITLE = "Остатки"
    const val FILTER_TITLE = "Фильтр"
    const val OPERATION_TITLE = "Операция"

    const val RECEIVED = "Получено"
    const val SPENT = "Потрачено"
    const val BALANCE = "Остаток"
    const val TOTAL = "ИТОГО"

    const val SAVE = "Сохранить"
    const val CANCEL = "Отмена"
    const val DELETE = "Удалить"
    const val NEW_OPERATION = "Новая операция"
}
