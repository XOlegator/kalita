// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import io.kotest.matchers.shouldBe
import kalita.core.DuplicateCheckMode
import kalita.core.DuplicateCheckOptions
import kalita.storage.api.DbSnapshot
import kalita.storage.api.Table
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.math.BigDecimal
import java.nio.file.Path

class DatabaseSettingsTest {
    @Test
    fun `the base currency is kept in a file of the data directory and can be cleared`(@TempDir dir: Path) {
        val data = dir.resolve("Data.kalita-data").toFile()
        DatabaseSettings(data).baseCurrencyId shouldBe null

        DatabaseSettings(data).baseCurrencyId = 12
        DatabaseSettings(data).baseCurrencyId shouldBe 12L
        File(data, "database-settings.json").readText() shouldBe """{"baseCurrencyId":12}"""

        DatabaseSettings(data).baseCurrencyId = null
        DatabaseSettings(data).baseCurrencyId shouldBe null
        // A damaged file is "no settings", never a failure to open the database.
        File(data, "database-settings.json").writeText("not json")
        DatabaseSettings(data).baseCurrencyId shouldBe null
    }

    @Test
    fun `a snapshot becomes a dataset with the setting applied`(@TempDir dir: Path) {
        val data = dir.toFile()
        val snapshot = DbSnapshot(mapOf(Table.Currencies to listOf(mapOf("id" to 12L, "Name" to "Рубль", "del" to false), mapOf("id" to 20L, "Name" to "RSD", "del" to false))))
        snapshot.toDataset(data).detectBaseCurrency() shouldBe null

        DatabaseSettings(data).baseCurrencyId = 20
        snapshot.toDataset(data).detectBaseCurrency() shouldBe 20L
        snapshot.toDataset(null).detectBaseCurrency() shouldBe null
    }

    @Test
    fun `duplicate options round trip, fall back to defaults and keep the base currency`(@TempDir dir: Path) {
        val data = dir.resolve("Data.kalita-data").toFile()
        DatabaseSettings(data).duplicateOptions() shouldBe DuplicateCheckOptions.DEFAULT

        DatabaseSettings(data).setDuplicateOptions(DuplicateCheckOptions(DuplicateCheckMode.MANUAL, BigDecimal("12.5"), 7))
        DatabaseSettings(data).duplicateOptions() shouldBe DuplicateCheckOptions(DuplicateCheckMode.MANUAL, BigDecimal("12.5"), 7)

        // A damaged file is "no settings", never a failure; so are unknown values.
        File(data, "database-settings.json").writeText("""{"duplicateCheckMode":"nope","duplicateAmountTolerance":"-5","duplicateDaysWindow":99}""")
        DatabaseSettings(data).duplicateOptions() shouldBe DuplicateCheckOptions.DEFAULT

        DatabaseSettings(data).baseCurrencyId = 3L
        DatabaseSettings(data).setDuplicateOptions(DuplicateCheckOptions(DuplicateCheckMode.ON_SAVE, BigDecimal("0"), 3))
        DatabaseSettings(data).baseCurrencyId shouldBe 3L
        DatabaseSettings(data).duplicateOptions() shouldBe DuplicateCheckOptions(DuplicateCheckMode.ON_SAVE, BigDecimal("0"), 3)
    }
}
