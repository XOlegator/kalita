// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kalita.presentation.CalculatorOp
import kalita.presentation.CalculatorState
import kalita.presentation.Strings
import java.math.BigDecimal

/**
 * FR-A3's «настоящий калькулятор»: the same [CalculatorState] machine the desktop popup uses
 * (§6.4), laid out as a phone keypad. «Ok» evaluates whatever is pending and hands the number back
 * through [onResult]; the amount field is seeded from [initial].
 */
@Composable
fun CalculatorDialog(initial: BigDecimal?, onResult: (BigDecimal) -> Unit, onDismiss: () -> Unit) {
    var calc by remember { mutableStateOf(CalculatorState.seeded(initial)) }

    @Composable
    fun Key(label: String, modifier: Modifier = Modifier, primary: Boolean = false, action: (CalculatorState) -> CalculatorState) {
        val onClick = { calc = action(calc) }
        if (primary) Button(onClick = onClick, modifier = modifier) { Text(label) } else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }

    @Composable
    fun KeyRow(vararg keys: Triple<String, Boolean, (CalculatorState) -> CalculatorState>) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            for ((label, primary, action) in keys) Key(label, Modifier.weight(1f), primary, action)
        }
    }

    fun digit(c: Char) = Triple(c.toString(), false) { s: CalculatorState -> s.digit(c) }
    fun op(label: String, o: CalculatorOp) = Triple(label, true) { s: CalculatorState -> s.operator(o) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.CALCULATOR) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    calc.display.replace('.', ','),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                KeyRow(
                    Triple("C", false) { s -> s.clear() },
                    Triple("⌫", false) { s -> s.backspace() },
                    Triple("%", false) { s -> s.percent() },
                    op("÷", CalculatorOp.Divide),
                )
                KeyRow(digit('7'), digit('8'), digit('9'), op("×", CalculatorOp.Multiply))
                KeyRow(digit('4'), digit('5'), digit('6'), op("−", CalculatorOp.Subtract))
                KeyRow(digit('1'), digit('2'), digit('3'), op("+", CalculatorOp.Add))
                KeyRow(
                    Triple("±", false) { s -> s.toggleSign() },
                    digit('0'),
                    Triple(",", false) { s -> s.decimalPoint() },
                    Triple("=", true) { s -> s.evaluate() },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Key("√", Modifier.weight(1f)) { it.sqrt() }
                    Key("1/x", Modifier.weight(1f)) { it.reciprocal() }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onResult(calc.evaluate().value) }) { Text(Strings.CALCULATOR_OK) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.CANCEL) } },
    )
}
