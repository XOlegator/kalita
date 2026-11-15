// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.mdb

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class XCashCompatTest {
    @Test
    fun `database without service tables is reported and upgraded without touching application data`(@TempDir dir: File) {
        val file = createDemoFixture(File(dir, "Data.mdb"))
        val rowsBefore = Jackcess.open(file, readOnly = true).use { it.getTable("DvizhenieDeneg").rowCount }

        val report = XCashCompat.check(file).shouldBeInstanceOf<XCashCompatReport.Incompatible>()
        ("_Languages" in report.missing) shouldBe true

        val result = XCashCompat.upgrade(file).shouldBeInstanceOf<UpgradeResult.Upgraded>()
        result.added.sorted() shouldBe report.missing.sorted()
        result.backup.isFile shouldBe true

        XCashCompat.check(file) shouldBe XCashCompatReport.Compatible
        Jackcess.open(file, readOnly = true).use { db ->
            db.getTable("DvizhenieDeneg").rowCount shouldBe rowsBefore
            db.getTable("EventPeriod").rowCount shouldBe 5
            db.getTable("_MessagesTypes").rowCount shouldBe 5
            db.getTable("EventPeriod").first()["Name"] shouldBe "Ежегодно"
            SchemaFingerprint.check(db) shouldBe SchemaCheck.Compatible
        }
        XCashCompat.upgrade(file) shouldBe UpgradeResult.NothingToDo
    }

    @Test
    fun `reference database replaces only the screen-description tables`(@TempDir dir: File) {
        val meta = listOf("_Tables", "_Columns", "_Objects", "_Registers", "_RegistersColumns", "_RegistersTables")
        fun withMeta(name: String, rows: Int): File {
            val file = createDemoFixture(File(dir, name))
            Jackcess.open(file).use { db ->
                for (m in meta) {
                    val t = TableBuilder(m).addColumn(Jackcess.autoNumber(ColumnBuilder("id", DataType.LONG)))
                        .addColumn(ColumnBuilder("Name", DataType.TEXT))
                        .addIndex(Jackcess.primaryKey(Jackcess.indexOn(IndexBuilder(Jackcess.PRIMARY_KEY_NAME), true, "id")))
                        .toTable(db)
                    repeat(rows) { t.addRow(Jackcess.AUTO_NUMBER, "$m $it") }
                }
            }
            return file
        }
        val old = withMeta("old.mdb", rows = 2)
        val reference = withMeta("ref.mdb", rows = 5)
        val opsBefore = Jackcess.open(old, readOnly = true).use { it.getTable("DvizhenieDeneg").rowCount }

        XCashCompat.upgrade(old, reference).shouldBeInstanceOf<UpgradeResult.Upgraded>()

        Jackcess.open(old, readOnly = true).use { db ->
            for (m in meta) db.getTable(m).rowCount shouldBe 5
            db.getTable("DvizhenieDeneg").rowCount shouldBe opsBefore
        }
    }
}
