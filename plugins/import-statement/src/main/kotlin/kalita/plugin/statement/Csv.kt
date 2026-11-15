// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.statement

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Bytes → text: UTF-8 (BOM allowed) when valid, otherwise windows-1251 (the usual legacy export). */
internal fun decodeText(bytes: ByteArray, fallback: Charset = Charset.forName("windows-1251")): String {
    val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body)).toString()
    } catch (_: CharacterCodingException) {
        String(body, fallback)
    }
}

/** Picks the separator that gives the most consistent multi-column split over the first lines. */
internal fun detectDelimiter(text: String): Char {
    val lines = text.lineSequence().filter { it.isNotBlank() }.take(20).toList()
    return listOf(';', ',', '\t', '|').maxBy { d ->
        val counts = lines.map { l -> parseCsv(l, d).firstOrNull()?.size ?: 1 }
        val common = counts.groupingBy { it }.eachCount().maxByOrNull { it.value }
        if (common == null || common.key < 2) 0 else common.value * 100 + common.key
    }
}

/** RFC 4180 reader: quoted fields, doubled quotes, newlines inside quotes; blank rows are dropped. */
internal fun parseCsv(text: String, delimiter: Char): List<List<String>> {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    fun endField() { row.add(field.toString()); field.setLength(0) }
    fun endRow() {
        endField()
        if (row.any { it.isNotBlank() }) rows.add(row)
        row = ArrayList()
    }
    while (i < text.length) {
        val c = text[i]
        when {
            quoted -> if (c == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false
            } else field.append(c)
            c == '"' && field.isBlank() -> { field.setLength(0); quoted = true }
            c == delimiter -> endField()
            c == '\r' -> { if (i + 1 < text.length && text[i + 1] == '\n') i++; endRow() }
            c == '\n' -> endRow()
            else -> field.append(c)
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) endRow()
    return rows
}
