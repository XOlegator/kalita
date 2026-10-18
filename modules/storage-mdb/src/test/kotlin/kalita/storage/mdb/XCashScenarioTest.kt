// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files

// JVM sanity check for the scenario stage 0.4 replays on the Android emulator — catches schema
// and arithmetic mistakes here, cheaply, before they show up as an emulator-CI failure.
class XCashScenarioTest {
    @Test
    fun `scenario 8_2 produces the expected row counts`() {
        val file = Files.createTempFile("kalita-scenario82-", ".mdb").toFile()
        file.deleteOnExit()

        val result = runXCashWriteScenario(file)

        result.operations shouldBe 600
        result.groupBudget shouldBe 5
        result.accounts shouldBe 1
        result.courses shouldBe 370
        (result.editedOperations > 0) shouldBe true
        (result.softDeletedOperations > 0) shouldBe true
        (result.nulledOperations > 0) shouldBe true
    }

    @Test
    fun `scenario 8_2 output is readable back by Jackcess with no discrepancies`() {
        val file = Files.createTempFile("kalita-scenario82-", ".mdb").toFile()
        file.deleteOnExit()
        val result = runXCashWriteScenario(file)

        Jackcess.open(file).use { db ->
            val dd = db.getTable(OPERATIONS_TABLE)
            dd.rowCount shouldBe result.operations
            dd.count { it[DELETED_COLUMN] == true } shouldBe result.softDeletedOperations
            dd.count { it["Summa"] == 12345.67 && it["Comment"] == null } shouldBe result.nulledOperations

            val course = db.getTable("Course")
            course.rowCount shouldBe result.courses
            for (index in course.indexes) {
                var count = 0
                for (row in Jackcess.cursor(index)) {
                    count++
                    row["id"] // force a read of each row via this index, not just a position count
                }
                count shouldBe result.courses
            }

            val groupBudget = db.getTable("GroupBudget")
            groupBudget.rowCount shouldBe result.groupBudget
            groupBudget.any { (it["Name"] as String).startsWith("Тест статья") } shouldBe true
        }
    }
}
