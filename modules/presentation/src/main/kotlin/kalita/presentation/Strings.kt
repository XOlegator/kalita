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
    const val RECEIPT_CATEGORIES = "Правила и словарь чеков…"
    const val RECEIPT_CATEGORIZATION_TITLE = "Правила и словарь чеков"
    const val RECEIPT_RULES = "Правила"
    const val RECEIPT_DICTIONARY = "Мой словарь"
    const val RECEIPT_BUILTIN = "Встроенный словарь"
    const val RECEIPT_RULE_MERCHANT = "Продавец (точно)"
    const val RECEIPT_CONTAINS = "Текст содержит"
    const val RECEIPT_PHRASE = "Фраза"
    const val RECEIPT_MEANING = "Значение"
    const val RECEIPT_DEFAULT = "По умолчанию"
    const val RECEIPT_ENABLED = "Включено"
    const val RECEIPT_RESET = "Вернуть по умолчанию"
    const val RECEIPT_HINT = "Правила проверяются сверху вниз. Укажите продавца и/или текст и статью расхода."
    const val RECEIPT_DICTIONARY_HINT = "Пользовательские фразы сопоставляются с текстом позиции чека; они не меняют встроенный словарь."
    const val RECEIPT_BUILTIN_HINT = "Встроенные сербские слова можно переназначить на статью расходов, отключить или вернуть исходное значение."
    const val RECEIPT_INVALID = "Проверьте строки: заполните условия и выберите доступную статью расхода; текст должен содержать буквы или цифры."
    const val RECEIPT_BUILTIN_INVALID = "Укажите непустое значение для включённых встроенных слов."
    const val RECEIPT_NO_DATABASE = "Сначала откройте базу."
    const val RECEIPT_ERROR = "Не удалось прочитать или сохранить настройки чеков:"
    const val RECEIPT_PARTIAL_SAVE = "Часть изменений могла быть сохранена; повторите сохранение или откройте настройки заново."

    fun receiptUnavailableItem(id: Long): String = "Недоступная статья (id: $id)"

    fun receiptDictionaryInvalid(rows: List<Int>): String =
        "Проверьте строки словаря ${rows.joinToString()}: укажите фразу и действительную статью расхода."

    fun receiptBuiltInInvalid(rows: List<Int>): String =
        "Проверьте встроенные записи ${rows.joinToString()}: выберите действительную статью расходов или отключите запись."

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

    // Rates from the Internet (FR-R12; the original's «Сервис → Курсы из Интернета»)
    const val RATES_FROM_INTERNET = "Курсы из Интернета…"
    const val RATE_LOAD_TITLE = "Загрузка курсов"
    const val RATE_LOAD_BUTTON = "Загрузить"
    const val CLOSE = "Закрыть"
    const val CURRENCY_CODE = "Код (ISO или ЦБ)"
    const val BASE_CURRENCY_SET = "Сделать базовой"
    const val BASE_CURRENCY_AUTO = "Базовая — автоматически"
    const val BASE_CURRENCY_MARK = "базовая"
    const val BASE_CURRENCY_MARK_AUTO = "базовая, определена автоматически"
    const val BASE_CURRENCY_NONE = "Базовая валюта не определена — выберите валюту и нажмите «Сделать базовой». Без неё суммы не пересчитываются."
    const val BASE_CURRENCY_HINT = "Базовая валюта — та, в которой заданы курсы остальных. Выбор хранится в настройках Kalita, не в файле базы."
    const val RATES_LOAD_PERIOD = "Период"
    const val RATES_PERIOD_TODAY = "Сегодня"
    const val RATES_PERIOD_WEEK = "Последние 7 дней"
    const val RATES_PERIOD_MONTH = "Последние 30 дней"
    const val RATES_PERIOD_CUSTOM = "Свой период"
    const val RATES_EMPTY = "Курсов пока нет"
    const val ALL = "Все"

    fun ratesPeriodSinceLast(date: String): String = "С последнего курса ($date)"

    fun baseCurrencyMark(explicit: Boolean): String = if (explicit) BASE_CURRENCY_MARK else BASE_CURRENCY_MARK_AUTO
    const val CURRENCY_RATE_TODAY = "Курс на сегодня"
    const val CURRENCY_RATE_FROM_INTERNET = "Из Интернета"
    const val CURRENCY_RATE_HINT = "Сколько единиц основной валюты стоит одна единица новой. Без курса суммы в этой валюте не пересчитываются."
    const val CURRENCY_RATE_INVALID = "Курс должен быть числом больше нуля"
    const val CURRENCY_NO_RATE_QUESTION = "Курс не задан — суммы в этой валюте не будут пересчитываться, пока его нет.\n\nДобавить валюту без курса?"

    fun currencyCodeSuggested(code: String): String = "по названию: $code"

    fun currencyRateFound(source: String, date: String): String = "$source, курс на $date"
    const val RATE_LOAD_NO_SOURCE = "Нет включённого плагина загрузки курсов (меню «Плагины»)."
    const val RATE_LOAD_NOTHING_NEW = "Новых курсов нет"
    const val RATE_LOAD_UNMATCHED = "Нет в источнике (проверьте код валюты в справочнике)"

    fun rateLoadDone(added: Int, updated: Int, unchanged: Int): String =
        "Загружено: новых $added, изменённых $updated, без изменений $unchanged"

    fun rateLoadFailed(source: String, reason: String): String = "Не удалось получить курсы ($source): $reason"
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

    const val IMPORT_REMEMBER_RULE = "Запомнить"
    const val IMPORT_REMEMBER_RULE_HINT = "Запомнить выбранные статью и «Кто/кому» как правило для этого продавца — следующие выписки подставят их сами"
    const val IMPORT_AMOUNT_TOLERANCE = "Допуск суммы (ед. валюты):"

    const val RULES_MENU = "Правила категоризации…"
    const val RULES_TITLE = "Правила категоризации"
    const val RULES_HINT = "Новым операциям из выписки статья и «Кто/кому» подставляются по первому подходящему правилу сверху вниз, затем — по истории журнала."
    const val RULE_FIELD = "Где искать"
    const val RULE_MATCH = "Условие"
    const val RULE_PATTERN = "Текст"
    const val RULE_FIELD_MERCHANT = "Продавец"
    const val RULE_FIELD_DESCRIPTION = "Описание"
    const val RULE_FIELD_MCC = "MCC"
    const val RULE_MATCH_CONTAINS = "содержит"
    const val RULE_MATCH_STARTS = "начинается с"
    const val RULE_MATCH_EQUALS = "равно"

    fun rulesIncomplete(rows: List<Int>): String =
        "В строках ${rows.joinToString(", ")} нужен текст и хотя бы статья или «Кто/кому»."

    fun rulesRemembered(count: Int): String = "Запомнено правил: $count."

    fun importApplied(created: Int, moved: Int, linked: Int, ignored: Int): String =
        "Готово. Создано операций: $created, перенесено на счёт: $moved, связано с журналом: $linked, игнорировано: $ignored."

    // Duplicate control (dev_plan.md «Контроль дублей», FR-R6)
    const val DUPLICATES_TITLE = "Контроль дублей"
    const val DUPLICATES_SETTINGS_MENU = "Контроль дублей…"
    const val DUPLICATES_SCAN_MENU = "Проверить дубли за период…"
    const val DUPLICATES_MODE = "Режим проверки"
    const val DUPLICATES_MODE_ON_SAVE = "Проверять при каждом сохранении"
    const val DUPLICATES_MODE_MANUAL = "Только по кнопке «Проверить»"
    const val DUPLICATES_TOLERANCE = "Допуск суммы (ед. валюты)"
    const val DUPLICATES_DAYS = "Окно вокруг даты, дней"
    const val DUPLICATES_SAVE = "Сохранить настройки"
    const val DUPLICATES_PERIOD = "Период"
    const val DUPLICATES_SCAN = "Проверить"
    const val DUPLICATES_CHECK_BUTTON = "Проверить дубли"
    const val DUPLICATES_NONE = "Похожих операций в журнале не найдено."
    const val DUPLICATES_PREFIX = "Возможные дубли в журнале (не доказательство):"
    const val DUPLICATES_MATCHES = "Похожие записи журнала"
    const val DUPLICATES_ALTERNATIVE = "…или:"
    const val DUPLICATES_TRUNCATED = "Поиск был ограничен — часть похожих строк могла не попасть в проверку."
    const val DUPLICATES_KEEP = "Оставить"
    const val DUPLICATES_LESS_DETAIL = "менее подробная запись"
    const val DUPLICATES_ENRICH = "Дополнить при выборе «Оставить и дополнить»:"
    const val DUPLICATES_QUESTION = "Что сделать с новыми операциями?"

    fun duplicateDiff(difference: java.math.BigDecimal): String =
        if (difference.signum() == 0) "суммы сходятся" else "разница ${Formatter.amount(difference)}"

    fun duplicateActionLabel(action: kalita.core.DuplicateAction): String = when (action) {
        kalita.core.DuplicateAction.REPLACE -> "Заменить старые записи новыми"
        kalita.core.DuplicateAction.KEEP_OLD -> "Оставить записи журнала и дополнить их"
        kalita.core.DuplicateAction.KEEP_BOTH -> "Всё равно записать — это другая покупка"
        kalita.core.DuplicateAction.LATER -> "Отложить проверку"
        kalita.core.DuplicateAction.DISCARD -> "Отказаться от записи"
    }

    fun duplicatesDeleteSuggested(ids: List<Long>): String = "Удалить менее подробные записи (#${ids.joinToString()})"

    fun duplicatesFound(count: Int): String = when {
        count == 1 -> "Найдена 1 похожая запись."
        count in 2..4 -> "Найдено $count похожих записи."
        else -> "Найдено $count похожих записей."
    }
}
