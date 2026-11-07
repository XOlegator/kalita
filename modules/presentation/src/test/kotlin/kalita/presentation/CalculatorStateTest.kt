// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CalculatorStateTest {
    @Test
    fun `digits build up the display left to right`() {
        var s = CalculatorState()
        s = s.digit('1').digit('2').digit('3')
        s.display shouldBe "123"
    }

    @Test
    fun `leading zero is replaced, not kept`() {
        CalculatorState().digit('5').display shouldBe "5"
    }

    @Test
    fun `decimal point is only accepted once per number`() {
        val s = CalculatorState().digit('1').decimalPoint().digit('5').decimalPoint().digit('5')
        s.display shouldBe "1.55"
    }

    @Test
    fun `add then equals`() {
        val s = CalculatorState().digit('5').operator(CalculatorOp.Add).digit('3').evaluate()
        s.value shouldBe BigDecimal("8")
    }

    @Test
    fun `chained operators evaluate left to right`() {
        // 5 + 3 * 2 = (5+3) * 2 = 16, same left-to-right chaining as a basic calculator
        val s = CalculatorState().digit('5').operator(CalculatorOp.Add)
            .digit('3').operator(CalculatorOp.Multiply)
            .digit('2').evaluate()
        s.value shouldBe BigDecimal("16")
    }

    @Test
    fun `pressing a second operator without a new digit just swaps the pending one`() {
        // 5 +, then * without typing anything new, then 3 = -> 5 * 3, not 5 + 5
        val s = CalculatorState().digit('5').operator(CalculatorOp.Add)
            .operator(CalculatorOp.Multiply)
            .digit('3').evaluate()
        s.value shouldBe BigDecimal("15")
    }

    @Test
    fun `divide by zero yields zero instead of throwing`() {
        val s = CalculatorState().digit('5').operator(CalculatorOp.Divide).digit('0').evaluate()
        s.value shouldBe BigDecimal.ZERO
    }

    @Test
    fun `toggle sign flips and unflips`() {
        var s = CalculatorState().digit('7').toggleSign()
        s.display shouldBe "-7"
        s = s.toggleSign()
        s.display shouldBe "7"
    }

    @Test
    fun `toggle sign on zero does nothing`() {
        CalculatorState().toggleSign().display shouldBe "0"
    }

    @Test
    fun `backspace removes the last character, bottoming out at zero`() {
        var s = CalculatorState().digit('1').digit('2')
        s = s.backspace()
        s.display shouldBe "1"
        s = s.backspace()
        s.display shouldBe "0"
    }

    @Test
    fun `clear resets display and any pending operator`() {
        val s = CalculatorState().digit('5').operator(CalculatorOp.Add).digit('3').clear()
        s.display shouldBe "0"
        s.value shouldBe BigDecimal.ZERO
    }

    @Test
    fun `square root of nine is three`() {
        CalculatorState().digit('9').sqrt().value shouldBe BigDecimal("3")
    }

    @Test
    fun `reciprocal of four is one quarter`() {
        CalculatorState().digit('4').reciprocal().value shouldBe BigDecimal("0.25")
    }

    @Test
    fun `reciprocal of zero stays zero instead of throwing`() {
        CalculatorState().reciprocal().value shouldBe BigDecimal.ZERO
    }

    @Test
    fun `percent with no pending operator divides by 100`() {
        CalculatorState().digit('5').digit('0').percent().value shouldBe BigDecimal("0.5")
    }

    @Test
    fun `percent of an accumulated value, e g 200 plus 10 percent`() {
        // 200 + 10% -> 10% of 200 = 20; "=" then adds it to the 200
        val s = CalculatorState().digit('2').digit('0').digit('0')
            .operator(CalculatorOp.Add)
            .digit('1').digit('0').percent()
        s.value shouldBe BigDecimal("20")
        s.evaluate().value shouldBe BigDecimal("220")
    }

    @Test
    fun `seeded from an existing amount shows it verbatim`() {
        CalculatorState.seeded(BigDecimal("150.50")).display shouldBe "150.5"
    }

    @Test
    fun `seeded from null starts at zero`() {
        CalculatorState.seeded(null).display shouldBe "0"
    }
}
