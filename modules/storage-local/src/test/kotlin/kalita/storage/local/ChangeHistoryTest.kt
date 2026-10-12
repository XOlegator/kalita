// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import io.kotest.matchers.shouldBe
import kalita.storage.api.ChangeSet
import kalita.storage.api.FieldChange
import kalita.storage.api.Op
import kalita.storage.api.RowRef
import kalita.storage.api.Table
import kalita.storage.api.TempKey
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

class ChangeHistoryTest {
    private val everything = ChangeSet(
        UUID.randomUUID(), "laptop", 3, Instant.parse("2026-10-12T10:00:00Z"), "Расход 350,00 — Продукты",
        listOf(
            Op.Insert(
                Table.Operations, TempKey("o"),
                mapOf(
                    "Summa" to BigDecimal("350.50"), "Course" to 1.5, "Date" to LocalDateTime.of(2026, 10, 12, 9, 0, 1),
                    "Comment" to "ёЁ \"кавычки\"\nстрока", "del" to false, "AccountFrom" to TempKey("a"), "People_ID" to null, "id" to 7L,
                ),
            ),
            Op.Update(Table.Accounts, RowRef.New(TempKey("a")), mapOf("Name" to FieldChange("A", null))),
            Op.SetDeleted(Table.Accounts, RowRef.Existing(2), base = false, new = true),
            Op.HardDelete(Table.Rates, RowRef.Existing(9), mapOf("Course" to 90.5)),
        ),
    )

    @Test
    fun `entries survive a round trip exactly`(@TempDir dir: Path) {
        val history = ChangeHistory(dir.resolve("h/history.jsonl").toFile())
        val entry = HistoryEntry(everything, Instant.parse("2026-10-12T10:00:05Z"), mapOf(TempKey("o") to 7L))
        history.append(listOf(entry))
        history.append(listOf(entry.copy(tempKeys = emptyMap())))

        history.read() shouldBe listOf(entry, entry.copy(tempKeys = emptyMap()))
    }

    @Test
    fun `a torn last line is ignored`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "history.jsonl")
        val history = ChangeHistory(file)
        val entry = HistoryEntry(everything, Instant.EPOCH, emptyMap())
        history.append(listOf(entry))
        file.appendText("{\"appliedAt\":\"2026-10")

        history.read() shouldBe listOf(entry)
    }

    @Test
    fun `missing file means empty history`(@TempDir dir: Path) {
        ChangeHistory(dir.resolve("none").toFile()).read() shouldBe emptyList()
    }
}
