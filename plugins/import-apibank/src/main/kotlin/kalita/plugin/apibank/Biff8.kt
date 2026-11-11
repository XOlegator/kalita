// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.apibank

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Just enough of the OLE2 Compound File Binary format ([MS-CFB]) to pull one named stream out of
 * a legacy `.xls`: header, DIFAT → FAT, directory, and the mini-stream for small streams.
 */
internal class CompoundFile(bytes: ByteArray) {
    private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val fat: IntArray
    private val directory: List<Entry>
    private val miniFat: IntArray
    private val miniStream: ByteArray
    private val miniCutoff: Int

    internal class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    init {
        require(bytes.size >= 512 && buf.getLong(0) == SIGNATURE) { "not an OLE2 compound file" }
        sectorSize = 1 shl buf.getShort(30).toInt()
        miniSectorSize = 1 shl buf.getShort(32).toInt()
        val fatSectorCount = buf.getInt(44)
        val directoryStart = buf.getInt(48)
        val miniStreamCutoff = buf.getInt(56)
        val miniFatStart = buf.getInt(60)
        val difatStart = buf.getInt(68)
        val difatCount = buf.getInt(72)

        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) {
            val s = buf.getInt(76 + 4 * i)
            if (s < 0) break // 0xFFFFFFFA..0xFFFFFFFF (FREESECT/ENDOFCHAIN/FATSECT/DIFSECT) are negative as Int
            fatSectors += s
        }
        var difat = difatStart
        var guard = 0
        while (difat >= 0 && guard++ < difatCount + 1) {
            val base = sectorOffset(difat)
            for (i in 0 until sectorSize / 4 - 1) {
                val s = buf.getInt(base + 4 * i)
                if (s < 0) break
                fatSectors += s
            }
            difat = buf.getInt(base + sectorSize - 4)
        }
        fat = IntArray(fatSectors.size * (sectorSize / 4))
        fatSectors.forEachIndexed { n, s ->
            val base = sectorOffset(s)
            for (i in 0 until sectorSize / 4) fat[n * (sectorSize / 4) + i] = buf.getInt(base + 4 * i)
        }
        check(fatSectors.size >= fatSectorCount.coerceAtMost(fatSectors.size))

        val dirBytes = readChain(directoryStart)
        directory = (0 until dirBytes.size / 128).map { i ->
            val e = ByteBuffer.wrap(dirBytes, i * 128, 128).order(ByteOrder.LITTLE_ENDIAN)
            val nameLen = e.getShort(i * 128 + 64).toInt().coerceIn(0, 64)
            val name = String(dirBytes, i * 128, (nameLen - 2).coerceAtLeast(0), Charsets.UTF_16LE)
            Entry(name, dirBytes[i * 128 + 66].toInt(), e.getInt(i * 128 + 116), e.getInt(i * 128 + 120).toLong() and 0xFFFFFFFFL)
        }
        val root = directory.firstOrNull { it.type == 5 }
        miniStream = if (root != null && root.start >= 0) readChain(root.start) else ByteArray(0)
        miniFat = if (miniFatStart >= 0) {
            val b = readChain(miniFatStart)
            IntArray(b.size / 4) { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(it * 4) }
        } else {
            IntArray(0)
        }
        this.miniCutoff = miniStreamCutoff
    }

    private fun sectorOffset(sector: Int) = 512 + sector.toLong().toInt() * sectorSize

    private fun readChain(start: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && s < fat.size && guard++ <= fat.size) {
            val off = sectorOffset(s)
            if (off + sectorSize > buf.capacity()) break
            out.write(buf.array(), off, sectorSize)
            s = fat[s]
        }
        return out.toByteArray()
    }

    private fun readMiniChain(start: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && s < miniFat.size && guard++ <= miniFat.size) {
            val off = s * miniSectorSize
            if (off + miniSectorSize > miniStream.size) break
            out.write(miniStream, off, miniSectorSize)
            s = miniFat[s]
        }
        return out.toByteArray()
    }

    fun streamNames(): List<String> = directory.filter { it.type == 2 }.map { it.name }

    fun stream(name: String): ByteArray? {
        val e = directory.firstOrNull { it.type == 2 && it.name == name } ?: return null
        val raw = if (e.size < miniCutoff) readMiniChain(e.start) else readChain(e.start)
        return raw.copyOf(minOf(e.size, raw.size.toLong()).toInt())
    }

    private companion object {
        const val SIGNATURE = -2226271756974174256L // D0 CF 11 E0 A1 B1 1A E1
    }
}

/** One parsed worksheet: cell values by (row, column), zero-based; text, `Double` or `Boolean`. */
internal class BiffSheet(val name: String, val cells: Map<Pair<Int, Int>, Any>) {
    val rowCount: Int = (cells.keys.maxOfOrNull { it.first } ?: -1) + 1

    fun text(row: Int, col: Int): String? = cells[row to col]?.let { if (it is String) it else null }

    fun value(row: Int, col: Int): Any? = cells[row to col]

    /** All cells of [row] in column order. */
    fun row(row: Int): List<Pair<Int, Any>> = cells.filterKeys { it.first == row }.map { it.key.second to it.value }.sortedBy { it.first }
}

/**
 * The BIFF8 (Excel 97–2003) record subset a statement export actually uses: inline text
 * (`LABEL`), shared-string text (`SST` + `LABELSST`, `CONTINUE`-split), numbers (`NUMBER`, `RK`,
 * `MULRK`), formula results (`FORMULA` + following `STRING`), and sheet boundaries. Only the
 * first worksheet is returned. Formatting, formulas themselves, charts and everything else is
 * skipped on purpose.
 */
internal object Biff8 {
    fun firstSheet(workbook: ByteArray): BiffSheet {
        val buf = ByteBuffer.wrap(workbook).order(ByteOrder.LITTLE_ENDIAN)
        val sst = ArrayList<String>()
        var sheetName = "Sheet1"
        var inFirstSheet = false
        var sheetsSeen = 0
        val cells = LinkedHashMap<Pair<Int, Int>, Any>()
        var pendingFormula: Pair<Int, Int>? = null

        var pos = 0
        while (pos + 4 <= workbook.size) {
            val type = buf.getShort(pos).toInt() and 0xFFFF
            val len = buf.getShort(pos + 2).toInt() and 0xFFFF
            val start = pos + 4
            if (start + len > workbook.size) break
            when (type) {
                BOF -> {
                    // Substream type: 0x0005 workbook globals, 0x0010 worksheet, others (chart, macro) skipped.
                    val kind = if (len >= 4) buf.getShort(start + 2).toInt() and 0xFFFF else 0
                    if (kind == 0x0010) {
                        sheetsSeen++
                        inFirstSheet = sheetsSeen == 1
                    }
                }
                EOF -> if (inFirstSheet) return BiffSheet(sheetName, cells)
                BOUNDSHEET -> if (sheetsSeen == 0 && sheetName == "Sheet1") {
                    sheetName = readShortUnicode(buf, start + 6)
                }
                SST -> {
                    val (strings, next) = readSst(workbook, pos)
                    sst += strings
                    pos = next
                    continue
                }
                LABELSST -> if (inFirstSheet) {
                    val idx = buf.getInt(start + 6)
                    sst.getOrNull(idx)?.let { cells[cell(buf, start)] = it }
                }
                LABEL, RSTRING -> if (inFirstSheet) cells[cell(buf, start)] = readUnicode(buf, start + 6, buf.getShort(start + 6).toInt() and 0xFFFF, start + 8).first
                NUMBER -> if (inFirstSheet) cells[cell(buf, start)] = buf.getDouble(start + 6)
                RK -> if (inFirstSheet) cells[cell(buf, start)] = decodeRk(buf.getInt(start + 6))
                MULRK -> if (inFirstSheet) {
                    val row = buf.getShort(start).toInt() and 0xFFFF
                    val firstCol = buf.getShort(start + 2).toInt() and 0xFFFF
                    val lastCol = buf.getShort(start + len - 2).toInt() and 0xFFFF
                    for (c in firstCol..lastCol) cells[row to c] = decodeRk(buf.getInt(start + 4 + (c - firstCol) * 6 + 2))
                }
                FORMULA -> if (inFirstSheet) {
                    val at = cell(buf, start)
                    val b6 = buf.get(start + 12).toInt() and 0xFF
                    val b7 = buf.get(start + 13).toInt() and 0xFF
                    if (b6 == 0xFF && b7 == 0xFF) {
                        when (buf.get(start + 6).toInt()) {
                            0 -> pendingFormula = at
                            1 -> cells[at] = buf.get(start + 8).toInt() != 0
                        }
                    } else {
                        cells[at] = buf.getDouble(start + 6)
                    }
                }
                STRING -> if (inFirstSheet && pendingFormula != null) {
                    cells[pendingFormula!!] = readUnicode(buf, start, buf.getShort(start).toInt() and 0xFFFF, start + 2).first
                    pendingFormula = null
                }
            }
            pos = start + len
        }
        return BiffSheet(sheetName, cells)
    }

    private fun cell(buf: ByteBuffer, start: Int): Pair<Int, Int> =
        (buf.getShort(start).toInt() and 0xFFFF) to (buf.getShort(start + 2).toInt() and 0xFFFF)

    private fun decodeRk(rk: Int): Double {
        val div100 = rk and 1 != 0
        val isInt = rk and 2 != 0
        val v = if (isInt) (rk shr 2).toDouble() else java.lang.Double.longBitsToDouble((rk.toLong() and 0xFFFFFFFCL) shl 32)
        return if (div100) v / 100.0 else v
    }

    /** BIFF8 short unicode string (8-bit length), as in BOUNDSHEET. */
    private fun readShortUnicode(buf: ByteBuffer, at: Int): String {
        val len = buf.get(at).toInt() and 0xFF
        return readUnicode(buf, at, len, at + 1).first
    }

    /**
     * A BIFF8 unicode string whose character count [len] is already known; [flagsAt] points at the
     * option flags byte. Returns the text and the offset just past it (runs/ext data skipped).
     */
    private fun readUnicode(buf: ByteBuffer, @Suppress("UNUSED_PARAMETER") lenAt: Int, len: Int, flagsAt: Int): Pair<String, Int> {
        val flags = buf.get(flagsAt).toInt() and 0xFF
        var p = flagsAt + 1
        val runs = if (flags and 0x08 != 0) (buf.getShort(p).toInt() and 0xFFFF).also { p += 2 } else 0
        val ext = if (flags and 0x04 != 0) buf.getInt(p).also { p += 4 } else 0
        val wide = flags and 0x01 != 0
        val text = if (wide) {
            String(buf.array(), p, len * 2, Charsets.UTF_16LE).also { p += len * 2 }
        } else {
            String(buf.array(), p, len, Charsets.ISO_8859_1).also { p += len }
        }
        p += runs * 4 + ext
        return text to p
    }

    /**
     * The shared string table, which may spill into `CONTINUE` records — and a string may be cut
     * at a record boundary, where the continuation starts with a fresh flags byte (the
     * compressed/wide choice can change mid-string). Returns the strings and the offset of the
     * first record after the SST and its continuations.
     */
    private fun readSst(wb: ByteArray, sstPos: Int): Pair<List<String>, Int> {
        val buf = ByteBuffer.wrap(wb).order(ByteOrder.LITTLE_ENDIAN)
        // Collect the record payloads: SST first, then every directly following CONTINUE.
        val chunks = ArrayList<IntRange>()
        var pos = sstPos
        var first = true
        while (pos + 4 <= wb.size) {
            val type = buf.getShort(pos).toInt() and 0xFFFF
            val len = buf.getShort(pos + 2).toInt() and 0xFFFF
            if (first && type != SST) break
            if (!first && type != CONTINUE) break
            chunks += (pos + 4) until (pos + 4 + len)
            pos += 4 + len
            first = false
        }
        val strings = ArrayList<String>()
        if (chunks.isEmpty()) return strings to pos
        val unique = buf.getInt(chunks[0].first + 4)
        var chunk = 0
        var p = chunks[0].first + 8

        fun ensure(need: Int) {
            // Move to the next chunk when the current one is exhausted (only valid at a field boundary).
            while (chunk < chunks.size - 1 && p + need > chunks[chunk].last + 1) {
                chunk++
                p = chunks[chunk].first
            }
        }

        repeat(unique) {
            ensure(3)
            if (chunk >= chunks.size || p + 3 > chunks[chunk].last + 1) return strings to pos
            val len = buf.getShort(p).toInt() and 0xFFFF
            p += 2
            var flags = buf.get(p).toInt() and 0xFF
            p += 1
            val runs = if (flags and 0x08 != 0) { ensure(2); (buf.getShort(p).toInt() and 0xFFFF).also { p += 2 } } else 0
            val ext = if (flags and 0x04 != 0) { ensure(4); buf.getInt(p).also { p += 4 } } else 0
            val sb = StringBuilder(len)
            var remaining = len
            while (remaining > 0) {
                val wide = flags and 0x01 != 0
                val end = chunks[chunk].last + 1
                val avail = (end - p) / (if (wide) 2 else 1)
                if (avail <= 0) {
                    if (chunk >= chunks.size - 1) break
                    chunk++
                    p = chunks[chunk].first
                    flags = buf.get(p).toInt() and 0xFF
                    p += 1
                    continue
                }
                val take = minOf(avail, remaining)
                if (wide) {
                    sb.append(String(wb, p, take * 2, Charsets.UTF_16LE))
                    p += take * 2
                } else {
                    sb.append(String(wb, p, take, Charsets.ISO_8859_1))
                    p += take
                }
                remaining -= take
            }
            // Rich-text runs and extended data trail the characters and may themselves span chunks.
            var skip = runs * 4 + ext
            while (skip > 0) {
                val end = chunks[chunk].last + 1
                val here = minOf(skip, end - p)
                p += here
                skip -= here
                if (skip > 0 && chunk < chunks.size - 1) { chunk++; p = chunks[chunk].first }
                else if (skip > 0) break
            }
            strings += sb.toString()
        }
        return strings to pos
    }

    private const val BOF = 0x0809
    private const val EOF = 0x000A
    private const val BOUNDSHEET = 0x0085
    private const val SST = 0x00FC
    private const val CONTINUE = 0x003C
    private const val LABELSST = 0x00FD
    private const val LABEL = 0x0204
    private const val RSTRING = 0x00D6
    private const val NUMBER = 0x0203
    private const val RK = 0x027E
    private const val MULRK = 0x00BD
    private const val FORMULA = 0x0006
    private const val STRING = 0x0207
}

/** `.xls` bytes → first worksheet, through [CompoundFile] + [Biff8]. */
internal fun readXlsFirstSheet(bytes: ByteArray): BiffSheet {
    val cfb = CompoundFile(bytes)
    val stream = cfb.stream("Workbook") ?: cfb.stream("Book") ?: error("no Workbook stream in the file (streams: ${cfb.streamNames()})")
    return Biff8.firstSheet(stream)
}
