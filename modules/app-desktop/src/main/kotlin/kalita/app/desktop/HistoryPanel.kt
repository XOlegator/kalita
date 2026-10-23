// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QAbstractTableModel
import io.qt.core.QModelIndex
import io.qt.core.Qt
import io.qt.widgets.QAbstractItemView
import io.qt.widgets.QHeaderView
import io.qt.widgets.QLabel
import io.qt.widgets.QTableView
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.presentation.Strings
import kalita.storage.local.HistoryEntry
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * «История изменений» dock panel (dev_plan.md §6.5, FR-7): read-only table showing
 * every change set applied to the local database, most recent first.
 */
class HistoryPanel(
    private val controller: AppController,
    parent: QWidget? = null,
) : QWidget(parent) {

    private val tableModel = HistoryTableModel()
    private val tableView = QTableView()
    private val emptyLabel = QLabel(Strings.HISTORY_EMPTY)

    init {
        val layout = QVBoxLayout(this)
        layout.setContentsMargins(0, 0, 0, 0)
        tableView.setModel(tableModel)
        tableView.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        tableView.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        tableView.setAlternatingRowColors(true)
        tableView.verticalHeader()?.setVisible(false)
        tableView.horizontalHeader()?.setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        tableView.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        layout.addWidget(tableView)
        emptyLabel.setAlignment(Qt.AlignmentFlag.AlignCenter)
        layout.addWidget(emptyLabel)
    }

    fun refreshData() {
        val entries = controller.readHistory()
        tableModel.setEntries(entries.asReversed())
        val empty = entries.isEmpty()
        tableView.setVisible(!empty)
        emptyLabel.setVisible(empty)
    }
}

private enum class HistoryCol { Time, Description, Device }
private val HISTORY_COLS = HistoryCol.entries.toTypedArray()
private val TIME_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

private class HistoryTableModel : QAbstractTableModel() {
    private var entries: List<HistoryEntry> = emptyList()

    fun setEntries(data: List<HistoryEntry>) {
        beginResetModel()
        entries = data
        endResetModel()
    }

    override fun rowCount(parent: QModelIndex): Int = entries.size

    override fun columnCount(parent: QModelIndex): Int = HISTORY_COLS.size

    override fun data(index: QModelIndex, role: Int): Any? {
        if (role != Qt.ItemDataRole.DisplayRole) return null
        val entry = entries.getOrNull(index.row()) ?: return null
        return when (HISTORY_COLS.getOrNull(index.column())) {
            HistoryCol.Time -> TIME_FMT.format(entry.appliedAt.atZone(ZoneId.systemDefault()))
            HistoryCol.Description -> entry.changeSet.description
            HistoryCol.Device -> entry.changeSet.device
            null -> null
        }
    }

    override fun headerData(section: Int, orientation: Qt.Orientation, role: Int): Any? {
        if (role != Qt.ItemDataRole.DisplayRole || orientation != Qt.Orientation.Horizontal) return null
        return when (HISTORY_COLS.getOrNull(section)) {
            HistoryCol.Time -> Strings.HISTORY_TIME
            HistoryCol.Description -> Strings.HISTORY_DESCRIPTION
            HistoryCol.Device -> Strings.HISTORY_DEVICE
            null -> null
        }
    }
}
