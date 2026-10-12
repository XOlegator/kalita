// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.api

import java.time.Instant
import java.util.UUID

/** Application tables of `Data.mdb` that Kalita may write (dev_plan.md §3.3, MVP set). */
enum class Table(val tableName: String) {
    Operations("DvizhenieDeneg"),
    BudgetItems("GroupBudget"),
    OperationTypes("TypeOper"),
    Accounts("Account"),
    People("People"),
    Currencies("Currency"),
    Rates("Course"),
}

/** Placeholder id for a row inserted by a not-yet-applied change; resolved to a real `id` on apply. */
@JvmInline
value class TempKey(val value: String)

sealed interface RowRef {
    data class Existing(val id: Long) : RowRef
    data class New(val tempKey: TempKey) : RowRef
}

/**
 * A field edit with its base ("was") value — what makes field-level merge, history and
 * undo possible (dev_plan.md §9.5).
 *
 * Cell values are plain JVM values: `Long`, `Boolean`, `String`, `Double`, `java.math.BigDecimal`
 * (Jet Currency), `java.time.LocalDateTime` (seconds precision) or `null`. A [TempKey] used as a
 * value of a reference column is replaced by the real `id` of the row it names.
 */
data class FieldChange(val base: Any?, val new: Any?)

sealed interface Op {
    val table: Table

    data class Insert(override val table: Table, val tempKey: TempKey, val values: Map<String, Any?>) : Op
    data class Update(override val table: Table, val row: RowRef, val fields: Map<String, FieldChange>) : Op
    data class SetDeleted(override val table: Table, val row: RowRef, val base: Boolean, val new: Boolean) : Op

    /** Physical delete; only exchange rates are ever removed from the database (dev_plan.md §5.5). */
    data class HardDelete(override val table: Table, val row: RowRef, val baseRow: Map<String, Any?>) : Op
}

/** One user action = one atomically applied change set (dev_plan.md §9.5). */
data class ChangeSet(
    val id: UUID,
    val device: String,
    val author: Int?,
    val createdAt: Instant,
    val description: String,
    val ops: List<Op>,
)

sealed interface Conflict {
    val changeSetId: UUID
    val table: Table

    data class RowMissing(override val changeSetId: UUID, override val table: Table, val id: Long) : Conflict

    /** Stored value differs from the change's base value. */
    data class FieldChanged(
        override val changeSetId: UUID,
        override val table: Table,
        val id: Long,
        val column: String,
        val base: Any?,
        val actual: Any?,
    ) : Conflict

    data class UnresolvedReference(override val changeSetId: UUID, override val table: Table, val tempKey: TempKey) : Conflict

    data class UnknownColumn(override val changeSetId: UUID, override val table: Table, val column: String) : Conflict
}

/** [tempKeys] maps every inserted row's temp key to its real id; [conflicts] empty means fully applied. */
data class ApplyResult(val tempKeys: Map<TempKey, Long>, val conflicts: List<Conflict>) {
    val ok: Boolean get() = conflicts.isEmpty()
}
