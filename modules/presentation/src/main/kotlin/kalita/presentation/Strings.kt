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
    const val CALCULATOR = "Калькулятор"
    const val CALCULATOR_OK = "Ok"
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
    const val BUDGET_ITEM_CLEAR = "Без статьи"
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
    const val DATE_MODE_WEEK = "Текущая неделя"
    const val DATE_MODE_MONTH = "Текущий месяц"
    const val DATE_MODE_QUARTER = "Текущий квартал"
    const val DATE_MODE_YEAR = "Текущий год"
    const val DATE_MODE_ALL = "Все"
    const val DATE_MODE_INTERVAL = "Интервал"
    const val DATE_MODE_MULTI_INTERVAL = "Несколько интервалов"

    // Panels and layout (§6.1/§6.2, FR-40…FR-43)
    const val PANEL_VISIBILITY = "Видимость панелей…"
    const val PANEL_VISIBILITY_TITLE = "Видимость панелей"
    const val FILTER_PANEL_VISIBLE = "Показывать панель фильтра"
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

    // Agent connections (§12.14/§12.15, FR-AG5)
    const val AGENTS_MENU = "&Агенты"
    const val AGENT_CONNECTIONS = "Подключения…"
    const val AGENT_CONNECTIONS_TITLE = "Подключения агентов"
    const val NO_AGENT_CONNECTIONS = "Нет подключений"

    // Receipt review window (§12.19)
    const val RECEIPTS_MENU_ITEM = "Чеки на проверку…"
    const val RECEIPT_TITLE = "Чек от агента — проверка"
    const val RECEIPT_NO_PENDING = "Нет чеков на проверку."
    const val RECEIPT_MERCHANT = "Магазин"
    const val RECEIPT_DATE = "Дата"
    const val RECEIPT_ACCOUNT = "Оплачено со счёта"
    const val RECEIPT_CURRENCY = "Валюта"
    const val RECEIPT_MERGE = "Объединять строки с одной статьёй в одну операцию"
    const val RECEIPT_COL_TEXT = "Позиция"
    const val RECEIPT_COL_AMOUNT = "Сумма"
    const val RECEIPT_COL_ITEM = "Статья"
    const val RECEIPT_COL_AGENT = "Предложил агент"
    const val RECEIPT_ADD_LINE = "Добавить строку"
    const val RECEIPT_REMOVE_LINE = "Удалить строку"
    const val RECEIPT_SHOW_IMAGE = "Показать снимок…"
    const val RECEIPT_SAVE = "Сохранить в базу"
    const val RECEIPT_REJECT = "Отклонить"
    const val RECEIPT_LATER = "Позже"
    const val RECEIPT_REJECT_REASON_TITLE = "Отклонить чек"
    const val RECEIPT_REJECT_REASON = "Причина (агент её увидит; можно оставить пустой):"
    const val RECEIPT_AGENT_ADDED = "— добавлена вами —"
    const val RECEIPT_NO_ITEM = "— выберите статью —"
    const val AGENT_CONNECTION_NAME = "Название"
    const val AGENT_CONNECTION_MODE = "Режим"
    const val AGENT_CONNECTION_MAX_OPERATION = "Максимум за операцию"
    const val AGENT_CONNECTION_MAX_DAILY = "Максимум за сутки"
    const val AGENT_MODE_READ_ONLY = "Только чтение"
    const val AGENT_MODE_DRAFT = "Черновик (подтверждение в программе)"
    const val AGENT_MODE_WRITE = "Запись"
    const val EDIT_AGENT_CONNECTION_TITLE = "Подключение агента"
    const val AGENT_CONNECTION_NAME_REQUIRED = "Укажите название подключения"

    // Plugins (§9.11, FR-P1) and bank statement import (§11, FR-B1…FR-B6)
    const val PLUGINS_MENU = "&Плагины"
    const val MANAGE_PLUGINS = "Управление плагинами…"
    const val PLUGINS_TITLE = "Плагины"
    const val PLUGIN_SETTINGS = "Настройки…"
    const val PLUGIN_ENABLED = "Включён"
    const val PLUGIN_VERSION = "Версия"
    const val PLUGIN_STATE = "Состояние"
    const val PLUGIN_STATE_ACTIVE = "работает"
    const val PLUGIN_STATE_DISABLED = "выключен"
    const val PLUGIN_EXTERNAL = "внешний jar"
    const val PLUGIN_NO_SETTINGS = "У этого плагина нет настроек"
    const val PLUGIN_SECRET_STORED = "сохранено — введите, чтобы заменить"
    const val PLUGIN_NONE = "Плагины не найдены"
    const val BROWSE = "Обзор…"
    const val IMPORT_STATEMENT = "Импортировать выписку…"
    const val BANK_MAPPINGS = "Соответствие счетов и валют…"
    const val BANK_MAPPINGS_TITLE = "Соответствие банка и Kalita"
    const val BANK_MAPPING_ACCOUNTS = "Клиент / счёт в банке → счёт Kalita"
    const val BANK_MAPPING_CURRENCIES = "Валюта выписки → валюта Kalita"
    const val BANK_MAPPING_REQUIRED = "Для импорта нужно сопоставить каждый счёт и валюту выписки"
    const val NO_MAPPINGS = "Соответствий пока нет — они появятся при первом импорте"
    const val IMPORT_TITLE = "Импорт выписки"
    const val IMPORT_OPEN_TITLE = "Выберите файл выписки"
    const val IMPORT_EXACT = "Точные совпадения"
    const val IMPORT_SUMS = "Совпадения по сумме"
    const val IMPORT_NEW = "Новые"
    const val IMPORT_NOT_IN_BANK = "Нет в банке"
    const val IMPORT_BANK_DATE = "Дата банка"
    const val IMPORT_DESCRIPTION = "Описание"
    const val IMPORT_JOURNAL_ROW = "Запись журнала"
    const val IMPORT_JOURNAL_ROWS = "Записи журнала"
    const val IMPORT_ACTION = "Действие"
    const val IMPORT_ACTION_CREATE = "Создать"
    const val IMPORT_ACTION_SKIP = "Пропустить"
    const val IMPORT_ACTION_IGNORE = "Игнорировать навсегда"
    const val IMPORT_SIMILAR = "Похожая запись на другом счёте"
    const val IMPORT_NOTHING = "В выписке нет новых проведённых операций"
    const val IMPORT_NOT_IN_BANK_HINT = "Записи журнала по этому счёту за период выписки, которых банк не показал: наличные, ошибки ввода, неверный счёт."

    fun importActionMove(date: String, account: String): String = "Перенести запись от $date со счёта «$account»"

    fun importSummary(period: String?, posted: Int, linked: Int, pending: Int): String = buildString {
        if (period != null) append("Период: $period. ")
        append("Проведённых операций: $posted")
        if (linked > 0) append(", уже сопоставлено ранее: $linked")
        if (pending > 0) append(", не проведено (пропущено): $pending")
        append('.')
    }

    fun importApplied(created: Int, moved: Int, linked: Int, ignored: Int): String =
        "Готово. Создано операций: $created, перенесено на счёт: $moved, связано с журналом: $linked, игнорировано: $ignored."
}
