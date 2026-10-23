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

    // Dictionary browser strings (FR-10…FR-14)
    const val ACCOUNTS_TITLE = "Счета"
    const val COUNTERPARTIES_TITLE = "Кто/Кому"
    const val CURRENCIES_TITLE = "Валюты"
    const val BUDGET_ITEMS_TITLE = "Статьи бюджета"
    const val RATES_TITLE = "Курсы валют"
    const val INFORMATION_TITLE = "Информация"

    const val ADD = "Добавить"
    const val ADD_SUBLEVEL = "Подуровень"
    const val EDIT = "Изменить"
    const val RESTORE = "Восстановить"
    const val SEARCH = "Поиск…"
    const val SHOW_DELETED = "Показать удалённые"
    const val MOVE_UP = "Вверх"
    const val MOVE_DOWN = "Вниз"
    const val CUT = "Вырезать"
    const val PASTE = "Вставить"
    const val PASTE_TO_ROOT = "Вставить в корень"
    const val REFRESH = "Обновить"

    const val NAME = "Наименование"
    const val CB_ID = "Код ЦБ"
    const val DATE = "Дата"
    const val RATE = "Курс"
    const val CURRENCY = "Валюта"
    const val ALL_CURRENCIES = "Все валюты"
    const val FROM_DATE = "С"
    const val TO_DATE = "По"

    const val CONFIRM_DELETE_TITLE = "Подтверждение удаления"

    fun confirmDeleteWithRefs(name: String, count: Int): String =
        "«$name» используется в $count операциях.\nВсё равно удалить?"

    fun confirmDelete(name: String): String =
        "Удалить «$name»?"

    fun confirmDeleteRate(currency: String, date: String): String =
        "Удалить курс $currency на $date?"

    const val NAME_REQUIRED = "Наименование обязательно"

    fun duplicateWarning(name: String): String =
        "Элемент «$name» уже существует."

    const val RATE_REQUIRED = "Укажите курс"
    const val DATE_REQUIRED = "Укажите дату"
    const val CURRENCY_REQUIRED = "Укажите валюту"
    const val RATE_POSITIVE = "Курс должен быть больше нуля"
}
