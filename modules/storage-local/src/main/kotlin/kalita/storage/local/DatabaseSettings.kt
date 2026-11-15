// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import kalita.core.Dataset
import kalita.storage.api.DbSnapshot
import kalita.storage.api.toDataset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Kalita's own per-database settings, in `database-settings.json` of the database's data directory
 * (dev_plan.md §9.4) — never in the `.mdb`, whose format must stay exactly what the original X-Cash
 * expects. The directory is local to the device, so a setting made on the computer is made again on
 * the phone.
 *
 * So far one setting: the base currency, for a database where the rule «the currency without
 * rates» does not single one out ([Dataset.detectBaseCurrency]).
 */
class DatabaseSettings(private val dataDirectory: File) {
    private val file get() = File(dataDirectory, FILE_NAME)

    private fun read(): JsonObject =
        runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    /** The `Currency.id` chosen as the base currency, `null` when it is left to be detected. */
    var baseCurrencyId: Long?
        get() = read()[BASE_CURRENCY]?.jsonPrimitive?.longOrNull
        set(value) {
            val current = read()
            val next = if (value == null) current - BASE_CURRENCY else current + (BASE_CURRENCY to JsonPrimitive(value))
            dataDirectory.mkdirs()
            val temp = File(dataDirectory, "$FILE_NAME.tmp")
            temp.writeText(JsonObject(next).toString())
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

    private companion object {
        const val FILE_NAME = "database-settings.json"
        const val BASE_CURRENCY = "baseCurrencyId"
    }
}

/** [toDataset] with this database's own settings applied — what every shell shows and computes from. */
fun DbSnapshot.toDataset(dataDirectory: File?): Dataset =
    toDataset().let { dataset -> dataDirectory?.let { dataset.withBaseCurrency(DatabaseSettings(it).baseCurrencyId) } ?: dataset }
