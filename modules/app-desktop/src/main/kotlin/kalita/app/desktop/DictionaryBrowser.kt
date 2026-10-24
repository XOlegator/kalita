// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QAbstractTableModel
import io.qt.core.QModelIndex
import io.qt.core.QSettings
import io.qt.core.Qt
import io.qt.gui.QKeySequence
import io.qt.gui.QAction
import io.qt.widgets.QDialog
import io.qt.widgets.QDialogButtonBox
import io.qt.widgets.QFormLayout
import io.qt.widgets.QInputDialog
import io.qt.widgets.QLabel
import io.qt.widgets.QLineEdit
import io.qt.widgets.QMessageBox
import io.qt.widgets.QAbstractItemView
import io.qt.widgets.QTableView
import io.qt.widgets.QToolBar
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.presentation.DictKind
import kalita.presentation.Strings
import kalita.storage.api.SubmitResult

/** Which flat dictionary this browser shows. */
enum class DictionaryKind(val title: String, val dictKind: DictKind) {
    Accounts(Strings.ACCOUNTS_TITLE, DictKind.Account),
    Counterparties(Strings.COUNTERPARTIES_TITLE, DictKind.Counterparty),
    Currencies(Strings.CURRENCIES_TITLE, DictKind.CurrencyRef),
}

/** Named item with an id — the common shape of flat dictionary entries. */
data class DictEntry(val id: Long, val name: String?, val deleted: Boolean)

/**
 * Reusable browser panel for flat dictionaries (Accounts, Counterparties, Currencies — FR-10).
 * Toolbar: Add, Edit, Delete/Restore, Show Deleted, Search.
 */
class DictionaryBrowser(
    private val controller: AppController,
    private val kind: DictionaryKind,
    parent: QWidget? = null,
) : QWidget(parent) {

    private val tableModel = DictTableModel()
    private val tableView = QTableView()
    private val searchField = QLineEdit()
    private val showDeletedAction: QAction
    private val infoLabel = QLabel()

    init {
        val layout = QVBoxLayout(this)
        layout.setContentsMargins(0, 0, 0, 0)

        val toolbar = QToolBar()
        val addAction = toolbar.addAction(Strings.ADD)
        addAction.triggered.connect(this::onAdd)

        val editAction = toolbar.addAction(Strings.EDIT)
        editAction.setShortcut(QKeySequence("Ctrl+Return"))
        editAction.triggered.connect(this::onEdit)

        val deleteAction = toolbar.addAction(Strings.DELETE)
        deleteAction.setShortcut(QKeySequence("Ctrl+Delete"))
        deleteAction.triggered.connect(this::onDelete)

        val restoreAction = toolbar.addAction(Strings.RESTORE)
        restoreAction.triggered.connect(this::onRestore)

        toolbar.addSeparator()

        showDeletedAction = toolbar.addAction(Strings.SHOW_DELETED)
        showDeletedAction.setCheckable(true)
        showDeletedAction.setShortcut(QKeySequence("Ctrl+Shift+D"))
        showDeletedAction.toggled.connect(this::onShowDeletedToggled)

        layout.addWidget(toolbar)

        searchField.setPlaceholderText(Strings.SEARCH)
        searchField.setClearButtonEnabled(true)
        searchField.textChanged.connect(this::onSearchChanged)
        layout.addWidget(searchField)

        tableView.setModel(tableModel)
        tableView.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        tableView.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        tableView.setAlternatingRowColors(true)
        tableView.horizontalHeader().setStretchLastSection(true)
        tableView.verticalHeader().setVisible(false)
        tableView.doubleClicked.connect(this::onDoubleClicked)
        tableView.selectionModel().currentChanged.connect(this::onSelectionChanged)
        layout.addWidget(tableView, 1)

        infoLabel.setWordWrap(true)
        layout.addWidget(infoLabel)

        tableView.horizontalHeader().restoreFrom(QSettings(), headerSettingsKey)

        refreshData()
    }

    private val headerSettingsKey get() = "dictionary/${kind.name}/header"

    /** Column order/width persistence (FR-42), called from [MainWindow.closeEvent]. */
    fun persistHeaderState() {
        tableView.horizontalHeader().persistTo(QSettings(), headerSettingsKey)
    }

    fun refreshData() {
        val state = controller.dictionary.state.value
        val entries = when (kind) {
            DictionaryKind.Accounts -> state.accounts.map { DictEntry(it.id, it.name, it.deleted) }
            DictionaryKind.Counterparties -> state.counterparties.map { DictEntry(it.id, it.name, it.deleted) }
            DictionaryKind.Currencies -> state.currencies.map { DictEntry(it.id, it.name, it.deleted) }
        }
        tableModel.setData(entries)
        updateInfo()
    }

    private fun selectedEntry(): DictEntry? {
        val row = tableView.currentIndex().row()
        return if (row >= 0) tableModel.entryAt(row) else null
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onAdd(checked: Boolean = false) {
        val name = QInputDialog.getText(this, Strings.ADD, "${Strings.NAME}:")
        if (name.isNullOrBlank()) return
        if (isDuplicate(name.trim())) {
            QMessageBox.warning(this, Strings.ADD, Strings.duplicateWarning(name.trim()))
        }
        val result = when (kind) {
            DictionaryKind.Accounts -> controller.addAccount(name.trim())
            DictionaryKind.Counterparties -> controller.addCounterparty(name.trim())
            DictionaryKind.Currencies -> controller.addCurrency(name.trim(), null)
        }
        handleResult(result)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onEdit(checked: Boolean = false) {
        val entry = selectedEntry() ?: return
        if (kind == DictionaryKind.Currencies) {
            editCurrency(entry)
            return
        }
        val name = QInputDialog.getText(this, Strings.EDIT, "${Strings.NAME}:", QLineEdit.EchoMode.Normal, entry.name ?: "")
        if (name.isNullOrBlank()) return
        if (isDuplicate(name.trim(), entry.id)) {
            QMessageBox.warning(this, Strings.EDIT, Strings.duplicateWarning(name.trim()))
        }
        val result = when (kind) {
            DictionaryKind.Accounts -> controller.updateAccount(entry.id, name.trim())
            DictionaryKind.Counterparties -> controller.updateCounterparty(entry.id, name.trim())
            DictionaryKind.Currencies -> controller.updateCurrency(entry.id, name.trim(), null)
        }
        handleResult(result)
    }

    private fun editCurrency(entry: DictEntry) {
        val state = controller.dictionary.state.value
        val currency = state.currencies.firstOrNull { it.id == entry.id } ?: return
        val dialog = CurrencyEditDialog(this, currency.name ?: "", currency.cbId ?: "")
        if (dialog.exec() == QDialog.DialogCode.Accepted.value()) {
            val newName = dialog.nameValue().trim()
            val newCbId = dialog.cbIdValue().trim().ifEmpty { null }
            if (newName.isNotEmpty()) {
                if (isDuplicate(newName, entry.id)) {
                    QMessageBox.warning(this, Strings.EDIT, Strings.duplicateWarning(newName))
                }
                handleResult(controller.updateCurrency(entry.id, newName, newCbId))
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onDelete(checked: Boolean = false) {
        val entry = selectedEntry() ?: return
        if (entry.deleted) return
        val refCount = controller.dictionary.operationReferenceCount(kind.dictKind, entry.id)
        val message = if (refCount > 0) {
            Strings.confirmDeleteWithRefs(entry.name ?: "?", refCount)
        } else {
            Strings.confirmDelete(entry.name ?: "?")
        }
        val answer = QMessageBox.question(this, Strings.CONFIRM_DELETE_TITLE, message)
        if (answer != QMessageBox.StandardButton.Yes) return
        val result = when (kind) {
            DictionaryKind.Accounts -> controller.softDeleteAccount(entry.id)
            DictionaryKind.Counterparties -> controller.softDeleteCounterparty(entry.id)
            DictionaryKind.Currencies -> controller.softDeleteCurrency(entry.id)
        }
        handleResult(result)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onRestore(checked: Boolean = false) {
        val entry = selectedEntry() ?: return
        if (!entry.deleted) return
        val result = when (kind) {
            DictionaryKind.Accounts -> controller.restoreAccount(entry.id)
            DictionaryKind.Counterparties -> controller.restoreCounterparty(entry.id)
            DictionaryKind.Currencies -> controller.restoreCurrency(entry.id)
        }
        handleResult(result)
    }

    private fun onShowDeletedToggled(show: Boolean) {
        controller.dictionary.setShowDeleted(show)
        refreshData()
    }

    private fun onSearchChanged(text: String) {
        controller.dictionary.setSearchQuery(text)
        refreshData()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onDoubleClicked(index: QModelIndex) {
        onEdit()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onSelectionChanged(current: QModelIndex, previous: QModelIndex) {
        updateInfo()
    }

    private fun updateInfo() {
        val entry = selectedEntry()
        if (entry == null) {
            infoLabel.setText("")
            return
        }
        val deleted = if (entry.deleted) " [удалён]" else ""
        infoLabel.setText("${entry.name ?: "—"}$deleted (id: ${entry.id})")
    }

    private fun isDuplicate(name: String, excludeId: Long? = null): Boolean {
        val state = controller.dictionary.state.value
        val existing = when (kind) {
            DictionaryKind.Accounts -> state.accounts.filter { !it.deleted && it.id != excludeId }.mapNotNull { it.name }
            DictionaryKind.Counterparties -> state.counterparties.filter { !it.deleted && it.id != excludeId }.mapNotNull { it.name }
            DictionaryKind.Currencies -> state.currencies.filter { !it.deleted && it.id != excludeId }.mapNotNull { it.name }
        }
        return existing.any { it.equals(name, ignoreCase = true) }
    }

    private fun handleResult(result: SubmitResult) {
        when (result) {
            is SubmitResult.Applied -> refreshData()
            is SubmitResult.Conflicts -> QMessageBox.warning(this, "Конфликт", "Данные изменены извне.")
            is SubmitResult.Failed -> QMessageBox.critical(this, "Ошибка", result.reason)
        }
    }
}

/** Simple two-column table model for flat dictionary items. */
private class DictTableModel : QAbstractTableModel() {
    private var entries: List<DictEntry> = emptyList()

    fun setData(data: List<DictEntry>) {
        beginResetModel()
        entries = data
        endResetModel()
    }

    fun entryAt(row: Int): DictEntry? = entries.getOrNull(row)

    override fun rowCount(parent: QModelIndex?): Int = entries.size

    override fun columnCount(parent: QModelIndex?): Int = 2

    override fun data(index: QModelIndex, role: Int): Any? {
        if (role != Qt.ItemDataRole.DisplayRole) return null
        val entry = entries.getOrNull(index.row()) ?: return null
        return when (index.column()) {
            0 -> entry.name ?: ""
            1 -> if (entry.deleted) "✗" else ""
            else -> null
        }
    }

    override fun headerData(section: Int, orientation: Qt.Orientation, role: Int): Any? {
        if (role != Qt.ItemDataRole.DisplayRole || orientation != Qt.Orientation.Horizontal) return null
        return when (section) {
            0 -> Strings.NAME
            1 -> ""
            else -> null
        }
    }
}

/** Dialog for editing a currency (name + CB_ID). */
class CurrencyEditDialog(parent: QWidget?, name: String, cbId: String) : QDialog(parent) {
    private val nameField = QLineEdit(name)
    private val cbIdField = QLineEdit(cbId)

    init {
        setWindowTitle(Strings.EDIT)
        val layout = QFormLayout(this)
        layout.addRow(Strings.NAME, nameField)
        layout.addRow(Strings.CB_ID, cbIdField)
        val buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButtons(
                QDialogButtonBox.StandardButton.Ok, QDialogButtonBox.StandardButton.Cancel,
            ),
        )
        buttons.accepted.connect(this::accept)
        buttons.rejected.connect(this::reject)
        layout.addWidget(buttons)
    }

    fun nameValue(): String = nameField.text()
    fun cbIdValue(): String = cbIdField.text()
}
