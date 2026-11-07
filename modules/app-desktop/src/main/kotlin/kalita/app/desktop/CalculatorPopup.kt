// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QMetaObject
import io.qt.core.Qt
import io.qt.widgets.QFrame
import io.qt.widgets.QGridLayout
import io.qt.widgets.QLabel
import io.qt.widgets.QPushButton
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.presentation.CalculatorOp
import kalita.presentation.CalculatorState
import kalita.presentation.Strings
import java.math.BigDecimal

/**
 * The amount field's quick calculator (dev_plan.md §6.4), the same popup X-Cash's own
 * "Добавление" dialog opens from a small button next to the amount — digits, the four basic
 * operators (chained), √, 1/x, %, clear/backspace, and "Ok" to write the result back.
 *
 * A [Qt.WindowType.Popup] top-level widget, the same mechanism `QComboBox`'s own dropdown uses:
 * it grabs the pointer and closes itself on an outside click, with no extra wiring needed here.
 */
class CalculatorPopup(
    parent: QWidget?,
    initial: BigDecimal?,
    private val onAccept: (BigDecimal) -> Unit,
) : QFrame(parent, Qt.WindowType.Popup) {
    private var state = CalculatorState.seeded(initial)
    private val display = QLabel()

    init {
        setFrameShape(Shape.Panel)
        setFrameShadow(Shadow.Raised)
        val outer = QVBoxLayout(this)

        display.setAlignment(Qt.AlignmentFlag.AlignRight)
        display.setStyleSheet("font-size: 16pt; padding: 4px;")
        outer.addWidget(display)

        val grid = QGridLayout()
        grid.setSpacing(2)
        button(grid, 0, 0, "7") { state.digit('7') }
        button(grid, 0, 1, "8") { state.digit('8') }
        button(grid, 0, 2, "9") { state.digit('9') }
        button(grid, 0, 3, "/") { state.operator(CalculatorOp.Divide) }
        button(grid, 0, 4, "sqrt") { state.sqrt() }

        button(grid, 1, 0, "4") { state.digit('4') }
        button(grid, 1, 1, "5") { state.digit('5') }
        button(grid, 1, 2, "6") { state.digit('6') }
        button(grid, 1, 3, "*") { state.operator(CalculatorOp.Multiply) }
        button(grid, 1, 4, "%") { state.percent() }

        button(grid, 2, 0, "1") { state.digit('1') }
        button(grid, 2, 1, "2") { state.digit('2') }
        button(grid, 2, 2, "3") { state.digit('3') }
        button(grid, 2, 3, "-") { state.operator(CalculatorOp.Subtract) }
        button(grid, 2, 4, "1/x") { state.reciprocal() }

        button(grid, 3, 0, "0") { state.digit('0') }
        button(grid, 3, 1, "+/-") { state.toggleSign() }
        button(grid, 3, 2, ".") { state.decimalPoint() }
        button(grid, 3, 3, "+") { state.operator(CalculatorOp.Add) }
        button(grid, 3, 4, "C") { state.clear() }

        val backButton = QPushButton("<-")
        backButton.clicked.connect(QMetaObject.Slot1 { apply(state.backspace()) })
        grid.addWidget(backButton, 4, 0, 1, 3)

        val okButton = QPushButton(Strings.CALCULATOR_OK)
        okButton.clicked.connect(QMetaObject.Slot1 { onOk() })
        grid.addWidget(okButton, 4, 3, 1, 2)

        outer.addLayout(grid)
        refreshDisplay()
    }

    private fun button(grid: QGridLayout, row: Int, col: Int, label: String, transform: () -> CalculatorState) {
        val btn = QPushButton(label)
        btn.clicked.connect(QMetaObject.Slot1 { apply(transform()) })
        grid.addWidget(btn, row, col)
    }

    private fun apply(next: CalculatorState) {
        state = next
        refreshDisplay()
    }

    private fun refreshDisplay() {
        display.setText(state.display)
    }

    private fun onOk() {
        onAccept(state.evaluate().value)
        close()
    }
}
