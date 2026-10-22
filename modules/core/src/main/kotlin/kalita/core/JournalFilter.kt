// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.core

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/**
 * The journal filter of the «Фильтр» panel (dev_plan.md §6.3): one condition per checked field, all
 * of which must hold. It is plain data — relative dates stay relative until [JournalQuery] resolves
 * them against "today" — so a saved filter (FR-28) means the same thing whenever it is loaded, and
 * both shells and future reports share it. JSON form: [JournalFilterJson].
 */
data class JournalFilter(val conditions: List<FilterCondition> = emptyList()) {
    companion object {
        /** No conditions: every row passes. */
        val NONE = JournalFilter()

        /** The journal's default filter: the current month (FR-26). */
        val CURRENT_MONTH = JournalFilter(listOf(DateCondition(listOf(DateInterval.CURRENT_MONTH))))
    }
}

/**
 * One field's condition. The panel's two common flags apply to every kind: a row whose field is
 * empty (NULL) passes only with [includeEmpty] — the condition itself is not asked — and [inverted]
 * negates the outcome, empty rows included.
 */
sealed interface FilterCondition {
    val includeEmpty: Boolean
    val inverted: Boolean
}

/** Operation date in any of [intervals] (several only in the panel's «Интервалы» mode). */
data class DateCondition(
    val intervals: List<DateInterval>,
    override val includeEmpty: Boolean = false,
    override val inverted: Boolean = false,
) : FilterCondition

/** Inclusive date range; an open end is unbounded. */
data class DateInterval(val from: DateBound?, val to: DateBound?) {
    fun contains(date: LocalDate, today: LocalDate): Boolean =
        (from == null || !date.isBefore(from.resolve(today))) && (to == null || !date.isAfter(to.resolve(today)))

    companion object {
        fun day(date: LocalDate) = DateInterval(DateBound.Fixed(date), DateBound.Fixed(date))

        fun month(year: Int, month: Int): DateInterval {
            val first = LocalDate.of(year, month, 1)
            return DateInterval(DateBound.Fixed(first), DateBound.Fixed(first.with(TemporalAdjusters.lastDayOfMonth())))
        }

        fun quarter(year: Int, quarter: Int): DateInterval {
            require(quarter in 1..4) { "quarter must be 1..4, was $quarter" }
            return DateInterval(
                DateBound.Fixed(LocalDate.of(year, quarter * 3 - 2, 1)),
                DateBound.Fixed(LocalDate.of(year, quarter * 3, 1).with(TemporalAdjusters.lastDayOfMonth())),
            )
        }

        // The panel's «Текущий» flag: the period that contains today, whenever the filter runs.
        val TODAY = DateInterval(DateBound.Relative(DateAnchor.Today), DateBound.Relative(DateAnchor.Today))
        val CURRENT_MONTH = DateInterval(DateBound.Relative(DateAnchor.MonthStart), DateBound.Relative(DateAnchor.MonthEnd))
        val CURRENT_QUARTER =
            DateInterval(DateBound.Relative(DateAnchor.QuarterStart), DateBound.Relative(DateAnchor.QuarterEnd))
    }
}

sealed interface DateBound {
    fun resolve(today: LocalDate): LocalDate

    data class Fixed(val date: LocalDate) : DateBound {
        override fun resolve(today: LocalDate) = date
    }

    /** «Сегодня», «Начало месяца», … — moves with the calendar. */
    data class Relative(val anchor: DateAnchor) : DateBound {
        override fun resolve(today: LocalDate) = anchor.resolve(today)
    }
}

/** Relative date bounds; weeks start on Monday, as in the Russian locale. */
enum class DateAnchor {
    Today,
    WeekStart,
    WeekEnd,
    MonthStart,
    MonthEnd,
    QuarterStart,
    QuarterEnd,
    YearStart,
    YearEnd,
    ;

    fun resolve(today: LocalDate): LocalDate = when (this) {
        Today -> today
        WeekStart -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        WeekEnd -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        MonthStart -> today.withDayOfMonth(1)
        MonthEnd -> today.with(TemporalAdjusters.lastDayOfMonth())
        QuarterStart -> today.with(IsoFields.DAY_OF_QUARTER, 1)
        QuarterEnd -> today.with(IsoFields.DAY_OF_QUARTER, 1).plusMonths(3).minusDays(1)
        YearStart -> today.withDayOfYear(1)
        YearEnd -> today.with(TemporalAdjusters.lastDayOfYear())
    }
}

/** Reference fields of the panel (shown in bold there). */
enum class RefField {
    OperationType,
    BudgetItem,
    Currency,
    Counterparty,
    AccountFrom,
    AccountTo,

    /** «Счёт»: either account of the operation; empty only when both are. */
    Account,
}

/** The field references one of the checked [ids]. */
data class RefCondition(
    val field: RefField,
    val ids: Set<Long>,
    /** «Показывать подуровни»: a checked budget item also matches everything below it. */
    val withDescendants: Boolean = false,
    override val includeEmpty: Boolean = false,
    override val inverted: Boolean = false,
) : FilterCondition {
    init {
        require(!withDescendants || field == RefField.BudgetItem) { "only budget items have sublevels, not $field" }
    }
}

/**
 * The stored amount (`Summa`, always positive — dev_plan.md §5.1) within an inclusive range; an
 * open end is unbounded.
 */
data class AmountCondition(
    val min: BigDecimal?,
    val max: BigDecimal?,
    override val includeEmpty: Boolean = false,
    override val inverted: Boolean = false,
) : FilterCondition

/** Text fields of the panel. */
enum class TextField { Comment }

/** The field contains [substring], ignoring case. */
data class TextCondition(
    val field: TextField,
    val substring: String,
    override val includeEmpty: Boolean = false,
    override val inverted: Boolean = false,
) : FilterCondition
