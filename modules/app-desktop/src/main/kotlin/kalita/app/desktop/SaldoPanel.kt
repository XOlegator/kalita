// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QAbstractTableModel
import io.qt.core.QModelIndex
import io.qt.core.Qt
import io.qt.gui.QBrush
import io.qt.gui.QColor
import io.qt.gui.QFont
import io.qt.widgets.QAbstractItemView
import io.qt.widgets.QHeaderView
import io.qt.widgets.QTableView
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.core.SaldoLine
import kalita.core.SaldoTotal
import kalita.presentation.Formatter
import kalita.presentation.Strings
import java.math.BigDecimal

/**
 * «Остатки» dock panel (dev_plan.md §5.3, FR-29): per-account balances with a
 * «ИТОГО» footer row in the reduction currency.
 */
class SaldoPanel(
    private val controller: AppController,
    parent: QWidget? = null,
) : QWidget(parent) {

    private val tableModel = SaldoTableModel()
    private val tableView = QTableView()

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
    }

    fun refreshData() {
        val state = controller.saldo.state.value
        val dataset = (controller.state.value as? AppState.Ready)?.dataset ?: return
        tableModel.setData(state.saldo.lines, state.saldo.total, dataset)
    }
}

private enum class SaldoCol { Account, Currency, Received, Spent, Balance }
private val SALDO_COLS = SaldoCol.entries.toTypedArray()

private class SaldoTableModel : QAbstractTableModel() {
    private var lines: List<SaldoLine> = emptyList()
    private var total: SaldoTotal = SaldoTotal(BigDecimal.ZERO, BigDecimal.ZERO, emptySet())
    private var dataset: kalita.core.Dataset = kalita.core.Dataset.EMPTY

    fun setData(lines: List<SaldoLine>, total: SaldoTotal, dataset: kalita.core.Dataset) {
        beginResetModel()
        this.lines = lines
        this.total = total
        this.dataset = dataset
        endResetModel()
    }

    override fun rowCount(parent: QModelIndex): Int = lines.size + 1

    override fun columnCount(parent: QModelIndex): Int = SALDO_COLS.size

    override fun data(index: QModelIndex, role: Int): Any? {
        val col = SALDO_COLS.getOrNull(index.column()) ?: return null
        val isTotalRow = index.row() == lines.size

        if (role == Qt.ItemDataRole.DisplayRole) {
            if (isTotalRow) return totalText(col)
            val line = lines.getOrNull(index.row()) ?: return null
            return lineText(line, col)
        }
        if (role == Qt.ItemDataRole.FontRole && isTotalRow) {
            val font = QFont()
            font.setBold(true)
            return font
        }
        if (role == Qt.ItemDataRole.ForegroundRole) {
            val balance = if (isTotalRow) total.balance else lines.getOrNull(index.row())?.balance
            if (col == SaldoCol.Balance && balance != null && balance < BigDecimal.ZERO) {
                return QBrush(QColor(200, 0, 0))
            }
        }
        return null
    }

    override fun headerData(section: Int, orientation: Qt.Orientation, role: Int): Any? {
        if (role != Qt.ItemDataRole.DisplayRole || orientation != Qt.Orientation.Horizontal) return null
        return when (SALDO_COLS.getOrNull(section)) {
            SaldoCol.Account -> Strings.ACCOUNTS_TITLE
            SaldoCol.Currency -> Strings.CURRENCY
            SaldoCol.Received -> Strings.RECEIVED
            SaldoCol.Spent -> Strings.SPENT
            SaldoCol.Balance -> Strings.BALANCE
            null -> null
        }
    }

    private fun lineText(line: SaldoLine, col: SaldoCol): String = when (col) {
        SaldoCol.Account -> dataset.account(line.accountId)?.name ?: ""
        SaldoCol.Currency -> line.currencyId?.let { dataset.currency(it)?.name } ?: ""
        SaldoCol.Received -> Formatter.amount(line.received)
        SaldoCol.Spent -> Formatter.amount(line.spent)
        SaldoCol.Balance -> Formatter.amount(line.balance)
    }

    private fun totalText(col: SaldoCol): String = when (col) {
        SaldoCol.Account -> Strings.TOTAL
        SaldoCol.Currency -> ""
        SaldoCol.Received -> Formatter.amount(total.received)
        SaldoCol.Spent -> Formatter.amount(total.spent)
        SaldoCol.Balance -> Formatter.amount(total.balance)
    }
}
