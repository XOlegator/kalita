// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.widgets.QFormLayout
import io.qt.widgets.QLabel
import io.qt.widgets.QWidget
import kalita.core.Dataset
import kalita.core.JournalColumn
import kalita.core.JournalRow
import kalita.presentation.JournalRowFormatter
import kalita.presentation.Strings

/** «Информация» dock panel (dev_plan.md §6.2/FR-40): a card of the journal's current row. */
class InformationPanel(parent: QWidget? = null) : QWidget(parent) {
    private val valueLabels: Map<JournalColumn, QLabel> =
        JournalColumn.entries.associateWith { QLabel() }

    init {
        val layout = QFormLayout(this)
        for (column in JournalColumn.entries) {
            layout.addRow("${Strings.columnName(column)}:", valueLabels.getValue(column))
        }
        showOperation(null, Dataset.EMPTY)
    }

    fun showOperation(row: JournalRow?, dataset: Dataset) {
        for (column in JournalColumn.entries) {
            val text = row?.let { JournalRowFormatter.cellText(dataset, it, column) }.orEmpty()
            valueLabels.getValue(column).setText(text.ifEmpty { "—" })
        }
    }
}
