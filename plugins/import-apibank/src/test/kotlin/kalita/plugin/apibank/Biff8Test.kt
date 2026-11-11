// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.apibank

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Hand-assembled BIFF8 record streams for the record types the LibreOffice fixtures don't produce. */
class Biff8Test {
    private class Records {
        val out = ByteArrayOutputStream()
        fun record(type: Int, body: ByteArray) {
            out.write(le16(type)); out.write(le16(body.size)); out.write(body)
        }
        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        fun bof(kind: Int) = record(0x0809, le16(0x0600) + le16(kind) + ByteArray(12))
        fun eof() = record(0x000A, ByteArray(0))
        fun cell(row: Int, col: Int) = le16(row) + le16(col) + le16(0)
        /** LABEL: inline string, compressed (8-bit) or wide (UTF-16LE). */
        fun label(row: Int, col: Int, text: String, wide: Boolean) {
            val chars = if (wide) text.toByteArray(Charsets.UTF_16LE) else text.toByteArray(Charsets.ISO_8859_1)
            record(0x0204, cell(row, col) + le16(text.length) + byteArrayOf(if (wide) 1 else 0) + chars)
        }
        fun number(row: Int, col: Int, v: Double) = record(0x0203, cell(row, col) + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array())
        fun rk(row: Int, col: Int, rk: Int) = record(0x027E, cell(row, col) + le32(rk))
        fun mulrk(row: Int, firstCol: Int, rks: List<Int>) =
            record(0x00BD, le16(row) + le16(firstCol) + rks.flatMap { (le16(0) + le32(it)).toList() }.toByteArray() + le16(firstCol + rks.size - 1))
        fun formulaString(row: Int, col: Int, text: String) {
            record(0x0006, cell(row, col) + byteArrayOf(0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte()) + ByteArray(8))
            record(0x0207, le16(text.length) + byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1))
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    @Test
    fun `inline labels, numbers, RK, MULRK and formula strings of the first sheet are read`() {
        val r = Records()
        r.bof(0x0005) // workbook globals
        r.record(0x0085, r.le32(0) + byteArrayOf(0, 0) + byteArrayOf(5, 0) + "Sheet".toByteArray(Charsets.ISO_8859_1))
        r.eof()
        r.bof(0x0010) // first worksheet
        r.label(0, 0, "Client name", wide = false)
        r.label(0, 1, "ЖЕЛЕЗНЫЙ ИВАН", wide = true)
        r.number(1, 0, 1650.5)
        r.rk(1, 1, (1234 shl 2) or 2) // integer RK 1234
        r.rk(1, 2, (123456 shl 2) or 3) // integer RK /100 → 1234.56
        r.mulrk(2, 3, listOf((7 shl 2) or 2, (8 shl 2) or 2))
        r.formulaString(3, 0, "computed")
        r.eof()
        r.bof(0x0010) // a second sheet must be ignored
        r.label(0, 0, "other sheet", wide = false)
        r.eof()

        val sheet = Biff8.firstSheet(r.bytes())

        sheet.name shouldBe "Sheet"
        sheet.text(0, 0) shouldBe "Client name"
        sheet.text(0, 1) shouldBe "ЖЕЛЕЗНЫЙ ИВАН"
        sheet.value(1, 0) shouldBe 1650.5
        sheet.value(1, 1) shouldBe 1234.0
        sheet.value(1, 2) shouldBe 1234.56
        sheet.value(2, 3) shouldBe 7.0
        sheet.value(2, 4) shouldBe 8.0
        sheet.text(3, 0) shouldBe "computed"
        sheet.rowCount shouldBe 4
        sheet.row(1).map { it.first } shouldBe listOf(0, 1, 2)
    }

    @Test
    fun `a shared string split across a CONTINUE boundary is reassembled`() {
        val r = Records()
        r.bof(0x0005)
        // SST with 2 strings: "abc" fits; "defgh" is cut after "de", continuation is wide.
        val sstBody = r.le32(2) + r.le32(2) +
            r.le16(3) + byteArrayOf(0) + "abc".toByteArray(Charsets.ISO_8859_1) +
            r.le16(5) + byteArrayOf(0) + "de".toByteArray(Charsets.ISO_8859_1)
        r.record(0x00FC, sstBody)
        r.record(0x003C, byteArrayOf(1) + "fgh".toByteArray(Charsets.UTF_16LE))
        r.eof()
        r.bof(0x0010)
        r.record(0x00FD, r.cell(0, 0) + r.le32(1))
        r.record(0x00FD, r.cell(0, 1) + r.le32(0))
        r.eof()

        val sheet = Biff8.firstSheet(r.bytes())

        sheet.text(0, 0) shouldBe "defgh"
        sheet.text(0, 1) shouldBe "abc"
    }

    @Test
    fun `a file that is not OLE2 is rejected up front`() {
        runCatching { readXlsFirstSheet("<html>not an xls</html>".toByteArray()) }.exceptionOrNull()?.message shouldBe "not an OLE2 compound file"
    }
}
