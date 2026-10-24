// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.agent.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.LocalDate

/**
 * JSON conventions shared by every tool in this package (dev_plan.md §12.13): compact objects with
 * Russian names and `id`s; money as [BigDecimal.toPlainString] (never a `Double`); dates as ISO
 * (`LocalDate.toString`). Text that came from the database (comments, names) is returned as plain
 * data in its own field — never as something an agent should treat as an instruction.
 */
internal fun idName(id: Long, name: String?): JsonElement = buildJsonObject {
    put("id", id)
    put("name", name)
}

internal fun JsonObjectBuilder.putAmount(key: String, value: BigDecimal?) {
    put(key, value?.toPlainString())
}

internal fun JsonObjectBuilder.putDate(key: String, value: LocalDate?) {
    put(key, value?.toString())
}

internal fun errorResult(message: String): JsonObject = buildJsonObject {
    put("ok", false)
    put("error", message)
}
