// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.statement

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kalita.plugin.api.BankTransaction.Status
import kalita.plugin.api.Plugin
import kalita.plugin.api.StatementImporter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.ServiceLoader

class StatementFilePluginTest {
    private val plugin = StatementFilePlugin()

    @Test
    fun `is registered as a built-in plugin and claims csv ofx qfx`() {
        val found = ServiceLoader.load(Plugin::class.java).filterIsInstance<StatementFilePlugin>().single()
        (found as StatementImporter).supportedExtensions shouldBe setOf("csv", "ofx", "qfx")
    }

    @Test
    fun `parses a semicolon csv with decimal commas and day-first dates`() = runTest {
        val csv = "Date;Description;Amount;Currency\n" +
            "09.11.2026;\"MAXI, BEOGRAD\";-1 650,50;rsd\n" +
            "10.11.2026;Salary;12.345,67;rsd\n"
        val tx = plugin.import(csv.toByteArray(), "acc.csv")

        tx shouldHaveSize 2
        tx[0].postedAt shouldBe LocalDate.of(2026, 11, 9)
        tx[0].amount shouldBe BigDecimal("-1650.50")
        tx[0].currency shouldBe "RSD"
        tx[0].description shouldBe "MAXI, BEOGRAD"
        tx[0].merchant shouldBe "MAXI"
        tx[0].bankAccount shouldBe "acc"
        tx[1].amount shouldBe BigDecimal("12345.67")
        tx[0].status shouldBe Status.Posted
    }

    @Test
    fun `reads russian headers with separate debit and credit columns`() = runTest {
        val csv = "Дата операции,Назначение,Расход,Приход,Валюта\n" +
            "2026-11-09,Магазин,\"1,234.50\",,RUB\n" +
            "2026-11-10,Зарплата,,50000.00,RUB\n"
        val tx = plugin.import(csv.toByteArray(), "x.csv")

        tx.map { it.amount } shouldBe listOf(BigDecimal("-1234.50"), BigDecimal("50000.00"))
        tx[1].description shouldBe "Зарплата"
    }

    @Test
    fun `falls back to windows-1251 when the file is not utf-8`() = runTest {
        val csv = "Дата;Описание;Сумма\n09.11.2026;Кофе;-250,00\n"
        val tx = plugin.import(csv.toByteArray(charset("windows-1251")), "x.csv")
        tx.single().description shouldBe "Кофе"
    }

    @Test
    fun `decides month-first order from the file itself and skips preamble rows`() = runTest {
        val csv = "Statement for card 1234\n\nPosting Date,Details,Amount\n11/25/2026,Shop,-5.00\n12/01/2026,Shop,-6.00\n"
        val tx = plugin.import(csv.toByteArray(), "card.csv")
        tx.map { it.postedAt } shouldBe listOf(LocalDate.of(2026, 11, 25), LocalDate.of(2026, 12, 1))
    }

    @Test
    fun `uses the id column when present and keeps identical lines distinct otherwise`() = runTest {
        val withId = "Date,Amount,ID\n2026-11-01,-1.00,A1\n2026-11-01,-1.00,A2\n"
        val a = plugin.import(withId.toByteArray(), "x.csv")
        a[0].externalId shouldNotBe a[1].externalId

        val twins = "Date,Amount,Description\n2026-11-01,-1.00,Bus\n2026-11-01,-1.00,Bus\n"
        val b = plugin.import(twins.toByteArray(), "x.csv")
        b[0].externalId shouldNotBe b[1].externalId
        plugin.import(twins.toByteArray(), "x.csv").map { it.externalId } shouldBe b.map { it.externalId }
    }

    @Test
    fun `status column marks holds as pending`() = runTest {
        val csv = "Date,Amount,Status\n2026-11-01,-1.00,Executed\n2026-11-02,-2.00,Reserved\n"
        plugin.import(csv.toByteArray(), "x.csv").map { it.status } shouldBe listOf(Status.Posted, Status.Pending)
    }

    @Test
    fun `quoted fields may hold newlines and doubled quotes`() = runTest {
        val csv = "Date,Description,Amount\n2026-11-01,\"a \"\"b\"\"\nc\",-1.00\n"
        plugin.import(csv.toByteArray(), "x.csv").single().description shouldBe "a \"b\"\nc"
    }

    @Test
    fun `unrecognised csv is refused with a clear message`() = runTest {
        val e = shouldThrow<IllegalArgumentException> { plugin.import("foo,bar\n1,2\n".toByteArray(), "x.csv") }
        e.message!!.contains("заголовков") shouldBe true
        shouldThrow<IllegalArgumentException> { plugin.import("Date,Amount\nnot a date,1\n".toByteArray(), "x.csv") }
        shouldThrow<IllegalArgumentException> { plugin.import("Date,Amount\n2026-01-01,abc\n".toByteArray(), "x.csv") }
    }

    @Test
    fun `preview and manual mapping support unknown headers and headerless files`() = runTest {
        val bytes = "Report\n\nКогда;Что;Ушло;Пришло;Счёт;Валюта;Код;Состояние\n2026-11-01;Кофе;10;;A;rub;42;Pending\n2026-11-02;Доход;;50;A;rub;43;Posted\n".toByteArray(charset("windows-1251"))
        val preview = plugin.previewCsv(bytes)
        preview.headerRow shouldBe null
        preview.suggestedColumns shouldBe null
        preview.rows.size shouldBe 4
        val columns = CsvColumns(date = 0, description = 1, debit = 2, credit = 3, account = 4, currency = 5, id = 6, status = 7)
        val tx = plugin.importCsv(bytes, "x.csv", 2, columns)
        tx.map { it.amount } shouldBe listOf(BigDecimal("-10"), BigDecimal("50"))
        tx[0].description shouldBe "Кофе"
        tx[0].bankAccount shouldBe "A"
        tx[0].currency shouldBe "RUB"
        tx[0].status shouldBe Status.Pending
        tx[0].externalId shouldNotBe tx[1].externalId
        plugin.importCsv("2026-01-01,5\n".toByteArray(), "x.csv", 0, CsvColumns(0, amount = 1)).single().amount shouldBe BigDecimal("5")
    }

    @Test
    fun `recognised mapping reproduces automatic import`() = runTest {
        val bytes = "Preamble\nDate,Amount,Description\n2026-01-01,-2,Bus\n".toByteArray()
        val preview = plugin.previewCsv(bytes)
        preview.headerRow shouldBe 1
        preview.suggestedColumns shouldBe CsvColumns(date = 0, amount = 1, description = 2)
        plugin.importCsv(bytes, "x.csv", preview.headerRow!! + 1, preview.suggestedColumns!!) shouldBe plugin.import(bytes, "x.csv")
    }

    @Test
    fun `manual mapping validates rows and column selection`() {
        val bytes = "a,b,c\n2026-01-01,5,x\n".toByteArray()
        for (columns in listOf(CsvColumns(0), CsvColumns(-1, amount = 1), CsvColumns(0, amount = 3), CsvColumns(0, amount = 1, description = 1))) {
            shouldThrow<IllegalArgumentException> { plugin.importCsv(bytes, "x.csv", 1, columns) }
        }
        for (start in listOf(-1, 3)) {
            shouldThrow<IllegalArgumentException> { plugin.importCsv(bytes, "x.csv", start, CsvColumns(0, amount = 1)) }
        }
        shouldThrow<IllegalArgumentException> { plugin.importCsv(ByteArray(0), "x.csv", 0, CsvColumns(0, amount = 1)) }
        shouldThrow<IllegalArgumentException> { plugin.importCsv("x,y\nno,5\n".toByteArray(), "x.csv", 1, CsvColumns(0, amount = 1)) }
        shouldThrow<IllegalArgumentException> { plugin.importCsv("x,y\n2026-01-01,no\n".toByteArray(), "x.csv", 1, CsvColumns(0, amount = 1)) }
    }

    @Test
    fun `parses ofx 1x sgml without closing tags`() = runTest {
        val ofx = """
            OFXHEADER:100
            DATA:OFXSGML
            CHARSET:1252

            <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS>
            <CURDEF>EUR
            <BANKACCTFROM><BANKID>1<ACCTID>DE001<ACCTTYPE>CHECKING</BANKACCTFROM>
            <BANKTRANLIST>
            <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261109120000[0:GMT]<TRNAMT>-12.50<FITID>F1<NAME>Cafe &amp; Bar<MEMO>latte</STMTTRN>
            <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261110<TRNAMT>1000,00<FITID>F2<NAME>Salary
            </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
        """.trimIndent()
        val tx = plugin.import(ofx.toByteArray(), "x.ofx")

        tx shouldHaveSize 2
        tx[0].bankAccount shouldBe "DE001"
        tx[0].currency shouldBe "EUR"
        tx[0].amount shouldBe BigDecimal("-12.50")
        tx[0].postedAt shouldBe LocalDate.of(2026, 11, 9)
        tx[0].description shouldBe "Cafe & Bar, latte"
        tx[1].amount shouldBe BigDecimal("1000.00")
        tx[1].operationAt shouldBe null
        tx[0].externalId shouldNotBe tx[1].externalId
    }

    @Test
    fun `parses ofx 2x xml with two accounts`() = runTest {
        val ofx = """<?xml version="1.0"?><OFX>
            <STMTRS><CURDEF>USD</CURDEF><BANKACCTFROM><ACCTID>A</ACCTID></BANKACCTFROM><BANKTRANLIST>
            <STMTTRN><DTPOSTED>20261101</DTPOSTED><TRNAMT>-1.00</TRNAMT><FITID>1</FITID></STMTTRN></BANKTRANLIST></STMTRS>
            <CCSTMTRS><CURDEF>EUR</CURDEF><CCACCTFROM><ACCTID>B</ACCTID></CCACCTFROM><BANKTRANLIST>
            <STMTTRN><DTPOSTED>20261102</DTPOSTED><TRNAMT>-2.00</TRNAMT><FITID>1</FITID></STMTTRN></BANKTRANLIST></CCSTMTRS>
            </OFX>""".trimIndent()
        val tx = plugin.import(ofx.toByteArray(), "x.qfx")

        tx.map { it.bankAccount to it.currency } shouldBe listOf("A" to "USD", "B" to "EUR")
        tx[0].externalId shouldNotBe tx[1].externalId
    }

    @Test
    fun `non-ofx content under an ofx name is refused`() = runTest {
        shouldThrow<IllegalArgumentException> { plugin.import("hello".toByteArray(), "x.ofx") }
    }

    @Test
    fun `amount parser handles the common styles`() {
        parseAmount("(12.50)") shouldBe BigDecimal("-12.50")
        parseAmount("12.50-") shouldBe BigDecimal("-12.50")
        parseAmount("1,234") shouldBe BigDecimal("1234")
        parseAmount("1'234.56") shouldBe BigDecimal("1234.56")
        parseAmount("RSD 1.234,56") shouldBe BigDecimal("1234.56")
        parseAmount("0,5") shouldBe BigDecimal("0.5")
        parseAmount("−7") shouldBe BigDecimal("-7")
        parseAmount("") shouldBe null
        parseAmount("abc") shouldBe null
    }
}
