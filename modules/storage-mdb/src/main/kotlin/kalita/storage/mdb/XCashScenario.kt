// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import io.github.spannm.jackcess.Column
import io.github.spannm.jackcess.ColumnBuilder
import io.github.spannm.jackcess.CursorBuilder
import io.github.spannm.jackcess.DataType
import io.github.spannm.jackcess.Database.FileFormat
import io.github.spannm.jackcess.DatabaseBuilder
import io.github.spannm.jackcess.IndexBuilder
import io.github.spannm.jackcess.TableBuilder
import java.io.File
import java.time.LocalDateTime
import kotlin.random.Random

// Stage 0.4 PoC (dev_plan.md §8.2, §15 stage 0, 0.4): the write-stress scenario that research
// section 8.2 ran by hand against a copy of the real Data.mdb, replayed here on a synthetic,
// vendor-free schema so it can run unattended on an Android emulator in CI. Row counts are the
// contract checked by the `oracle-jet` cross-check (real Microsoft Jet via ACE/DAO) — see
// ScenarioResult and writeSidecar below.
data class ScenarioResult(
    val operations: Int,
    val groupBudget: Int,
    val accounts: Int,
    val courses: Int,
    val editedOperations: Int,
    val softDeletedOperations: Int,
    val nulledOperations: Int,
) {
    // key=value lines — plain enough for classic VBScript (no JSON parser) to read back in the
    // `oracle-jet` CI job, which checks the real Microsoft engine's counts against these.
    fun writeSidecar(file: File) {
        file.writeText(
            """
            operations=$operations
            groupBudget=$groupBudget
            accounts=$accounts
            courses=$courses
            editedOperations=$editedOperations
            softDeletedOperations=$softDeletedOperations
            nulledOperations=$nulledOperations

            """.trimIndent(),
        )
    }
}

private const val GROUP_BUDGET_TABLE = "GroupBudget"
private const val ACCOUNT_TABLE = "Account"
private const val COURSE_TABLE = "Course"
private const val EDITED_COMMENT_PREFIX = "Изменено "
private const val NULLED_SUMMA = 12345.67

fun runXCashWriteScenario(file: File, seed: Long = 42L): ScenarioResult {
    val rnd = Random(seed)
    val now = LocalDateTime.of(2026, 10, 10, 12, 0)

    return DatabaseBuilder.create(FileFormat.V2000, file).use { db ->
        val dvizhenieDeneg = TableBuilder(OPERATIONS_TABLE)
            .addColumn(ColumnBuilder("id", DataType.LONG).withAutoNumber(true))
            .addColumn(ColumnBuilder("Editor", DataType.LONG))
            .addColumn(ColumnBuilder("Creator", DataType.LONG))
            .addColumn(ColumnBuilder("DateCreate", DataType.SHORT_DATE_TIME))
            .addColumn(ColumnBuilder("DateEdit", DataType.SHORT_DATE_TIME))
            .addColumn(ColumnBuilder(DELETED_COLUMN, DataType.BOOLEAN))
            .addColumn(ColumnBuilder("Date", DataType.SHORT_DATE_TIME))
            .addColumn(ColumnBuilder("Summa", DataType.DOUBLE))
            .addColumn(ColumnBuilder("Currency_ID", DataType.LONG))
            .addColumn(ColumnBuilder("TypeOper_ID", DataType.LONG))
            .addColumn(ColumnBuilder("GroupBudget_ID", DataType.LONG))
            .addColumn(ColumnBuilder("AccountFrom", DataType.LONG))
            .addColumn(ColumnBuilder("AccountTo", DataType.LONG))
            .addColumn(ColumnBuilder("People_ID", DataType.LONG))
            // MEMO (Jet "Long Text"), not TEXT (255-char limit) — scenario 8.2 deliberately writes
            // comments over 4 KB to exercise multi-page LVAL, the finding that mattered most there.
            .addColumn(ColumnBuilder("Comment", DataType.MEMO))
            .toTable(db)

        val groupBudget = TableBuilder(GROUP_BUDGET_TABLE)
            .addColumn(ColumnBuilder("id", DataType.LONG).withAutoNumber(true))
            .addColumn(ColumnBuilder("Editor", DataType.LONG))
            .addColumn(ColumnBuilder("Creator", DataType.LONG))
            .addColumn(ColumnBuilder("DateCreate", DataType.SHORT_DATE_TIME))
            .addColumn(ColumnBuilder("DateEdit", DataType.SHORT_DATE_TIME))
            .addColumn(ColumnBuilder(DELETED_COLUMN, DataType.BOOLEAN))
            .addColumn(ColumnBuilder("Parent_ID", DataType.LONG))
            .addColumn(ColumnBuilder("Name", DataType.TEXT))
            .addColumn(ColumnBuilder("Ord", DataType.LONG))
            .toTable(db)

        val account = TableBuilder(ACCOUNT_TABLE)
            .addColumn(ColumnBuilder("id", DataType.LONG).withAutoNumber(true))
            .addColumn(ColumnBuilder("Editor", DataType.LONG))
            .addColumn(ColumnBuilder("Creator", DataType.LONG))
            .addColumn(ColumnBuilder("DateCreate", DataType.SHORT_DATE_TIME))
            .addColumn(ColumnBuilder("DateEdit", DataType.SHORT_DATE_TIME))
            .addColumn(ColumnBuilder(DELETED_COLUMN, DataType.BOOLEAN))
            .addColumn(ColumnBuilder("Name", DataType.TEXT))
            .toTable(db)

        val course = TableBuilder(COURSE_TABLE)
            .addColumn(ColumnBuilder("id", DataType.LONG).withAutoNumber(true))
            .addColumn(ColumnBuilder("Editor", DataType.LONG))
            .addColumn(ColumnBuilder("Creator", DataType.LONG))
            .addColumn(ColumnBuilder("DateCreate", DataType.SHORT_DATE_TIME))
            .addColumn(ColumnBuilder("DateEdit", DataType.SHORT_DATE_TIME))
            .addColumn(ColumnBuilder(DELETED_COLUMN, DataType.BOOLEAN))
            .addColumn(ColumnBuilder("Currency_ID", DataType.LONG))
            .addColumn(ColumnBuilder("Date", DataType.SHORT_DATE_TIME))
            .addColumn(ColumnBuilder("Course", DataType.DOUBLE))
            // Two secondary indexes, one DESC (dev_plan.md §8.2) — exercises Jet's own index
            // maintenance/traversal on both orderings, not just the autonumber primary key.
            .addIndex(IndexBuilder("idxDate").withColumns(true, "Date"))
            .addIndex(IndexBuilder("idxDateDesc").withColumns(false, "Date"))
            .toTable(db)

        repeat(5) { i ->
            groupBudget.addRow(
                Column.AUTO_NUMBER,
                3,
                3,
                now,
                now,
                false,
                2,
                "Тест статья $i ёЁ",
                200 + i,
            )
        }

        account.addRow(Column.AUTO_NUMBER, 3, 3, now, now, false, "Карта Тинькофф")

        val types = listOf(2 to 2, 1 to 1, 8 to 72)
        repeat(600) { i ->
            val (typeOperId, groupBudgetId) = types[rnd.nextInt(types.size)]
            val comment = when (val kind = rnd.nextInt(10)) {
                in 0..4 -> "Покупка №$i"
                in 5..7 -> "Длинный комментарий ".repeat(10 + rnd.nextInt(20)) + i
                8 -> "Очень длинный ".repeat(400) + i // > 4 KB -> multi-page LVAL
                else -> "Комментарий $kind №$i"
            }
            dvizhenieDeneg.addRow(
                Column.AUTO_NUMBER,
                3,
                3,
                now,
                now,
                false,
                LocalDateTime.of(2024 + rnd.nextInt(3), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28), 0, 0),
                (rnd.nextInt(10_000_000) / 100.0),
                if (rnd.nextInt(5) == 0) 10 else 12,
                typeOperId,
                groupBudgetId,
                if (typeOperId != 1) 1 + rnd.nextInt(6) else null,
                if (typeOperId != 2) 1 + rnd.nextInt(6) else null,
                if (rnd.nextBoolean()) 4 + rnd.nextInt(4) else null,
                comment,
            )
        }

        var edited = 0
        var softDeleted = 0
        var nulled = 0
        CursorBuilder.createCursor(dvizhenieDeneg).forEachIndexed { n, row ->
            when {
                n % 7 == 0 -> {
                    row.put("Comment", EDITED_COMMENT_PREFIX.repeat(50 + n % 30) + row["id"])
                    row.put("DateEdit", now.plusMinutes(1))
                    dvizhenieDeneg.updateRow(row)
                    edited++
                }
                n % 11 == 0 -> {
                    row.put(DELETED_COLUMN, true)
                    dvizhenieDeneg.updateRow(row)
                    softDeleted++
                }
                n % 13 == 0 -> {
                    row.put("Summa", NULLED_SUMMA)
                    row.put("Comment", null)
                    dvizhenieDeneg.updateRow(row)
                    nulled++
                }
            }
        }

        repeat(400) { i ->
            course.addRow(
                Column.AUTO_NUMBER,
                3,
                3,
                now,
                now,
                false,
                listOf(10, 11, 17)[i % 3],
                LocalDateTime.of(2026, 1, 1, 0, 0).plusDays((i / 3).toLong()),
                80 + rnd.nextDouble() * 20,
            )
        }

        var deleted = 0
        val courseCursor = CursorBuilder.createCursor(course)
        for (row in courseCursor) {
            if (deleted >= 30) break
            if ((row["id"] as Int) % 13 == 0) {
                courseCursor.deleteCurrentRow()
                deleted++
            }
        }
        check(deleted == 30) { "expected to hard-delete exactly 30 course rows, deleted $deleted" }

        ScenarioResult(
            operations = dvizhenieDeneg.rowCount,
            groupBudget = groupBudget.rowCount,
            accounts = account.rowCount,
            courses = course.rowCount,
            editedOperations = edited,
            softDeletedOperations = softDeleted,
            nulledOperations = nulled,
        )
    }
}
