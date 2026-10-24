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

    // Journal (FR-20…FR-24)
    const val JOURNAL_MENU = "Доходы/Расходы"
    const val REDUCTION_CURRENCY = "Валюта приведения"
    const val COPY = "Копировать"
    const val SAVE_AND_CLOSE = "Сохранить и выйти"
    const val ROW_COUNT_PREFIX = "Строк: "
    const val SELECTED_SUM_PREFIX = "Выделено: "
    const val EDIT_OPERATION = "Изменение операции"
    const val COPY_OPERATION = "Копирование операции"

    const val AMOUNT = "Сумма"
    const val OPERATION_TYPE = "Тип операции"
    const val BUDGET_ITEM = "Статья бюджета"
    const val COUNTERPARTY = "Кто/Кому"
    const val ACCOUNT_FROM = "Счёт-Откуда"
    const val ACCOUNT_TO = "Счёт-Куда"
    const val COMMENT = "Комментарий"

    fun confirmDeleteOperations(count: Int): String =
        if (count == 1) "Удалить выбранную операцию?"
        else "Удалить выбранные операции ($count)?"

    // History (FR-7)
    const val HISTORY_TITLE = "История изменений"
    const val HISTORY_TIME = "Время"
    const val HISTORY_DESCRIPTION = "Описание"
    const val HISTORY_DEVICE = "Источник"
    const val HISTORY_EMPTY = "История изменений пуста"

    // Aggregate footer
    const val AGG_SUM = "Сумма"
    const val AGG_MIN = "Мин"
    const val AGG_MAX = "Макс"
    const val AGG_AVG = "Среднее"
    const val AGG_COUNT = "Количество"

    // Filter panel (§6.3, FR-26…FR-28) and quick filter (§6.2)
    const val ACCOUNT = "Счёт"
    const val QUICK_FILTER_TITLE = "Быстрый фильтр"
    const val FLAG_EMPTY = "Пустые"
    const val FLAG_INVERTED = "Инверсия"
    const val SHOW_SUBLEVELS = "Показывать подуровни"
    const val APPLY = "Применить"
    const val CLEAR_FILTER = "Очистить"
    const val SAVE_FILTER = "Сохранить фильтр…"
    const val LOAD_FILTER = "Загрузить фильтр…"
    const val FAVORITES = "Избранное"
    const val TO_QUICK_FILTER = "В быстрый фильтр"
    const val ADD_INTERVAL = "Добавить интервал…"
    const val SAVE_FILTER_PROMPT = "Имя фильтра:"
    const val LOAD_FILTER_TITLE = "Загрузить фильтр"
    const val NO_SAVED_FILTERS = "Нет сохранённых фильтров"
    const val SUBSTRING_FILTER_PLACEHOLDER = "Фильтр по подстроке…"
    const val FIND = "Найти"
    const val FIND_NEXT = "Найти далее"
    const val FIND_PROMPT = "Текст для поиска:"
    const val FIND_NOT_FOUND = "Совпадений не найдено"

    const val DATE_MODE_TODAY = "Текущий день"
    const val DATE_MODE_MONTH = "Текущий месяц"
    const val DATE_MODE_QUARTER = "Текущий квартал"
    const val DATE_MODE_YEAR = "Текущий год"
    const val DATE_MODE_INTERVAL = "Интервал"
    const val DATE_MODE_MULTI_INTERVAL = "Несколько интервалов"

    // Panels and layout (§6.1/§6.2, FR-40…FR-43)
    const val PANEL_VISIBILITY = "Видимость панелей…"
    const val PANEL_VISIBILITY_TITLE = "Видимость панелей"
    const val COLUMN_VISIBILITY = "Видимость столбцов…"
    const val COLUMN_VISIBILITY_TITLE = "Видимость столбцов"
    const val HIDE_COLUMN = "Скрыть колонку"
    const val PIN_COLUMN = "Зафиксировать колонку"
    const val UNPIN_COLUMN = "Открепить колонку"
    const val SHOW_ALL_COLUMNS = "Показать все колонки"
    const val VIEW = "Вид"
    const val SAVE_VIEW = "Сохранить вид…"
    const val LOAD_VIEW = "Загрузить вид…"
    const val SAVE_VIEW_PROMPT = "Имя вида:"
    const val LOAD_VIEW_TITLE = "Загрузить вид"
    const val NO_SAVED_VIEWS = "Нет сохранённых видов"
}
