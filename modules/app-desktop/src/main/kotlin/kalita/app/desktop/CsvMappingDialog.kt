// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QMetaObject
import io.qt.widgets.QComboBox
import io.qt.widgets.QDialog
import io.qt.widgets.QDialogButtonBox
import io.qt.widgets.QFormLayout
import io.qt.widgets.QLabel
import io.qt.widgets.QMessageBox
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.plugin.api.BankTransaction
import kalita.plugin.statement.CsvColumns
import kalita.plugin.statement.StatementFilePlugin

internal class CsvMappingDialog(parent: QWidget, private val bytes: ByteArray, private val fileName: String) : QDialog(parent) {
    private val importer = StatementFilePlugin()
    private val preview = importer.previewCsv(bytes)
    private val start = QComboBox()
    private val fields = List(9) { QComboBox() }
    var transactions: List<BankTransaction> = emptyList()
        private set

    init {
        require(preview.rows.isNotEmpty()) { "CSV не содержит строк" }
        setWindowTitle("Сопоставление колонок CSV")
        resize(620, 550)
        val layout = QVBoxLayout(this)
        val hint = QLabel("Выберите первую строку данных и назначение колонок. Номера строк не учитывают пустые строки.")
        hint.setWordWrap(true)
        layout.addWidget(hint)
        val form = QFormLayout()
        preview.rows.forEachIndexed { index, row -> start.addItem("${index + 1}: ${row.joinToString(" | ").take(160)}") }
        start.setCurrentIndex(((preview.headerRow ?: -1) + 1).coerceIn(0, preview.rows.lastIndex))
        form.addRow("Первая строка данных", start)
        val suggested = preview.suggestedColumns
        val defaults = listOf(suggested?.date, suggested?.amount, suggested?.debit, suggested?.credit, suggested?.currency, suggested?.description, suggested?.account, suggested?.id, suggested?.status)
        val labels = listOf("Дата", "Сумма", "Расход", "Приход", "Валюта", "Описание", "Счёт", "ID", "Статус")
        val width = preview.rows.maxOf { it.size }
        fields.forEachIndexed { i, combo ->
            combo.addItem("—")
            for (column in 0 until width) {
                val examples = preview.rows.take(3).mapNotNull { it.getOrNull(column) }.joinToString(" / ").take(100)
                combo.addItem("${column + 1}: $examples")
            }
            combo.setCurrentIndex((defaults[i] ?: -1) + 1)
            form.addRow(labels[i], combo)
        }
        layout.addLayout(form)
        val buttons = QDialogButtonBox(QDialogButtonBox.StandardButtons(QDialogButtonBox.StandardButton.Ok, QDialogButtonBox.StandardButton.Cancel))
        buttons.accepted.connect(QMetaObject.Slot0 { import() })
        buttons.rejected.connect(this::reject)
        layout.addWidget(buttons)
    }

    private fun import() {
        fun col(i: Int): Int? = (fields[i].currentIndex() - 1).takeIf { it >= 0 }
        val date = col(0)
        if (date == null) {
            QMessageBox.warning(this, windowTitle(), "Выберите колонку даты.")
            return
        }
        val columns = CsvColumns(date, col(1), col(2), col(3), col(4), col(5), col(6), col(7), col(8))
        transactions = runCatching { importer.importCsv(bytes, fileName, start.currentIndex(), columns) }.getOrElse { e ->
            QMessageBox.warning(this, windowTitle(), "Не удалось прочитать CSV: ${e.message ?: e}")
            return
        }
        accept()
    }
}