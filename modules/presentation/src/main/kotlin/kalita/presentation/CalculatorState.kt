// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.presentation

import java.math.BigDecimal
import java.math.MathContext

/** The four chainable binary operators of the quick calculator (§6.4's amount-field popup). */
enum class CalculatorOp { Add, Subtract, Multiply, Divide }

/**
 * Pure state machine behind the quick calculator popup on the operation form's amount field
 * (dev_plan.md §6.4), mirroring X-Cash's own mini-calculator: digit entry, the four basic
 * operators — chained, so pressing one evaluates any operator already pending first — √, 1/x, %,
 * and a clear/backspace pair. [display] is what the popup shows; [value] is its numeric reading,
 * what "Ok" hands back to the amount field.
 */
data class CalculatorState(
    val display: String = "0",
    private val accumulator: BigDecimal? = null,
    private val pendingOp: CalculatorOp? = null,
    private val justEvaluated: Boolean = false,
) {
    val value: BigDecimal get() = display.toBigDecimalOrNull() ?: BigDecimal.ZERO

    fun digit(d: Char): CalculatorState {
        val fresh = justEvaluated || display == "0"
        return copy(display = if (fresh) d.toString() else display + d, justEvaluated = false)
    }

    fun decimalPoint(): CalculatorState = when {
        justEvaluated -> copy(display = "0.", justEvaluated = false)
        '.' in display -> this
        else -> copy(display = "$display.")
    }

    fun toggleSign(): CalculatorState = copy(
        display = when {
            display == "0" -> display
            display.startsWith("-") -> display.substring(1)
            else -> "-$display"
        },
    )

    fun backspace(): CalculatorState {
        if (justEvaluated) return clear()
        val trimmed = display.dropLast(1).ifEmpty { "0" }
        return copy(display = if (trimmed == "-") "0" else trimmed)
    }

    fun clear(): CalculatorState = CalculatorState()

    fun sqrt(): CalculatorState = unary { it.sqrt(MathContext.DECIMAL64) }

    fun reciprocal(): CalculatorState = unary { if (it.signum() == 0) it else BigDecimal.ONE.divide(it, MathContext.DECIMAL64) }

    /** `accumulator × value ÷ 100` with a pending operator (e.g. "200 + 10%" → 20), else `value ÷ 100`. */
    fun percent(): CalculatorState {
        val base = accumulator
        val result = if (base != null) base.multiply(value) else value
        return unary { result.divide(BigDecimal(100), MathContext.DECIMAL64) }
    }

    fun operator(op: CalculatorOp): CalculatorState {
        if (justEvaluated && pendingOp != null) return copy(pendingOp = op)
        val resolved = resolvePending()
        return resolved.copy(accumulator = resolved.value, pendingOp = op, justEvaluated = true)
    }

    /** "Ok"/"=": resolves any pending operator and closes the calculation out. */
    fun evaluate(): CalculatorState = resolvePending().copy(accumulator = null, pendingOp = null, justEvaluated = true)

    private fun resolvePending(): CalculatorState {
        val acc = accumulator ?: return this
        val op = pendingOp ?: return this
        return copy(display = apply(op, acc, value).format())
    }

    private fun unary(fn: (BigDecimal) -> BigDecimal): CalculatorState = copy(display = fn(value).format(), justEvaluated = true)

    private fun apply(op: CalculatorOp, a: BigDecimal, b: BigDecimal): BigDecimal = when (op) {
        CalculatorOp.Add -> a.add(b)
        CalculatorOp.Subtract -> a.subtract(b)
        CalculatorOp.Multiply -> a.multiply(b)
        CalculatorOp.Divide -> if (b.signum() == 0) BigDecimal.ZERO else a.divide(b, MathContext.DECIMAL64)
    }

    companion object {
        /** Seeded with the amount field's current value when the popup opens, if it has one. */
        fun seeded(initial: BigDecimal?): CalculatorState =
            if (initial != null) CalculatorState(display = initial.format()) else CalculatorState()
    }
}

private fun BigDecimal.format(): String = stripTrailingZeros().toPlainString()
