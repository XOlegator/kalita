// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.api

import kalita.core.AuditStamp
import kalita.core.TreeChange
import java.time.Instant
import java.util.UUID

/**
 * Builds a [ChangeSet] from a user action (dev_plan.md §9.5): the caller describes what to insert,
 * update or delete; the builder reads the base ("was") values from the [snapshot] and adds audit
 * fields automatically.
 *
 * Usage: `ChangeSetBuilder(snapshot, audit, device).insert(…).update(…).build("description")`.
 * The resulting [ChangeSet] is ready for [StorageSession.submit].
 */
class ChangeSetBuilder(
    private val snapshot: DbSnapshot,
    private val audit: AuditStamp,
    private val device: String,
) {
    private val ops = mutableListOf<Op>()
    private var tempKeyCounter = 0

    fun nextTempKey(): TempKey = TempKey("t${++tempKeyCounter}")

    /** Insert a new row. Audit fields (`Creator`, `DateCreate`, `Editor`, `DateEdit`) are added. */
    fun insert(table: Table, tempKey: TempKey, values: Map<String, Any?>): ChangeSetBuilder {
        ops += Op.Insert(table, tempKey, values + auditInsertFields())
        return this
    }

    /**
     * Update fields of an existing row. Base values are read from the [snapshot]; audit fields
     * (`Editor`, `DateEdit`) are updated automatically.
     *
     * @throws IllegalArgumentException if the row is missing from the snapshot.
     */
    fun update(table: Table, id: Long, newValues: Map<String, Any?>): ChangeSetBuilder {
        val row = requireRow(table, id)
        val fields = buildMap<String, FieldChange>(newValues.size + 2) {
            for ((col, newVal) in newValues) {
                put(col, FieldChange(base = row[col], new = newVal))
            }
            putAll(auditUpdateFields(row))
        }
        ops += Op.Update(table, RowRef.Existing(id), fields)
        return this
    }

    fun softDelete(table: Table, id: Long): ChangeSetBuilder {
        val row = requireRow(table, id)
        ops += Op.SetDeleted(table, RowRef.Existing(id), base = row["del"] == true, new = true)
        return this
    }

    fun restore(table: Table, id: Long): ChangeSetBuilder {
        val row = requireRow(table, id)
        ops += Op.SetDeleted(table, RowRef.Existing(id), base = row["del"] == true, new = false)
        return this
    }

    /** Physical delete (only rates, dev_plan.md §5.5); captures the full base row. */
    fun hardDelete(table: Table, id: Long): ChangeSetBuilder {
        val row = requireRow(table, id)
        ops += Op.HardDelete(table, RowRef.Existing(id), baseRow = row)
        return this
    }

    /**
     * Translate [TreeChange]s (from [kalita.core.BudgetTreeService]) into [Op.Update] operations.
     * Changes for the same item are merged into a single update.
     */
    fun applyTreeChanges(changes: List<TreeChange>): ChangeSetBuilder {
        val byItem = LinkedHashMap<Long, MutableMap<String, Any?>>()
        for (change in changes) {
            val fields = byItem.getOrPut(change.itemId) { mutableMapOf() }
            when (change) {
                is TreeChange.OrdChanged -> fields["Ord"] = change.new
                is TreeChange.ParentChanged -> fields["Parent_ID"] = change.new
            }
        }
        for ((itemId, newValues) in byItem) {
            update(Table.BudgetItems, itemId, newValues)
        }
        return this
    }

    fun build(description: String): ChangeSet = ChangeSet(
        id = UUID.randomUUID(),
        device = device,
        author = audit.userId,
        createdAt = Instant.now(),
        description = description,
        ops = ops.toList(),
    )

    private fun requireRow(table: Table, id: Long): Row =
        snapshot.row(table, id)
            ?: throw IllegalArgumentException("Row $id not found in ${table.tableName}")

    private fun auditInsertFields(): Map<String, Any?> = mapOf(
        "Creator" to audit.userId?.toLong(),
        "DateCreate" to audit.timestamp,
        "Editor" to audit.userId?.toLong(),
        "DateEdit" to audit.timestamp,
    )

    private fun auditUpdateFields(row: Row): Map<String, FieldChange> = mapOf(
        "Editor" to FieldChange(base = row["Editor"], new = audit.userId?.toLong()),
        "DateEdit" to FieldChange(base = row["DateEdit"], new = audit.timestamp),
    )
}
