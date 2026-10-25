// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.widgets.QComboBox
import io.qt.widgets.QDialog
import io.qt.widgets.QDialogButtonBox
import io.qt.widgets.QFormLayout
import io.qt.widgets.QHBoxLayout
import io.qt.widgets.QLineEdit
import io.qt.widgets.QListWidget
import io.qt.widgets.QMessageBox
import io.qt.widgets.QPushButton
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.agent.api.AgentConnection
import kalita.agent.api.AgentConnectionStore
import kalita.agent.api.WriteLimits
import kalita.agent.api.WriteMode
import kalita.presentation.Strings
import java.math.BigDecimal

/**
 * «Агенты → Подключения…» (dev_plan.md §12.14/§12.15, FR-AG5): manages the named connections
 * [AgentConnectionStore] persists for the open database — the same list `kalita mcp --connection
 * NAME` reads mode/limits from, so a human sets this up once here instead of repeating
 * `--mode`/`--max-amount`/`--max-daily` on every launch.
 */
class AgentConnectionsDialog(parent: QWidget?, private val store: AgentConnectionStore) : QDialog(parent) {
    private val list = QListWidget()
    private var connections: List<AgentConnection> = emptyList()

    init {
        setWindowTitle(Strings.AGENT_CONNECTIONS_TITLE)
        resize(420, 320)
        val layout = QVBoxLayout(this)
        layout.addWidget(list, 1)
        refresh()

        val actionRow = QHBoxLayout()
        val addButton = QPushButton(Strings.ADD)
        addButton.clicked.connect(this::onAdd)
        actionRow.addWidget(addButton)
        val editButton = QPushButton(Strings.EDIT)
        editButton.clicked.connect(this::onEdit)
        actionRow.addWidget(editButton)
        val removeButton = QPushButton(Strings.DELETE)
        removeButton.clicked.connect(this::onRemove)
        actionRow.addWidget(removeButton)
        layout.addLayout(actionRow)

        val buttons = QDialogButtonBox(QDialogButtonBox.StandardButtons(QDialogButtonBox.StandardButton.Close))
        buttons.rejected.connect(this::accept)
        buttons.accepted.connect(this::accept)
        layout.addWidget(buttons)
    }

    private fun refresh() {
        connections = store.list()
        list.clear()
        if (connections.isEmpty()) {
            list.addItem(Strings.NO_AGENT_CONNECTIONS)
            return
        }
        for (c in connections) list.addItem(describe(c))
    }

    private fun describe(c: AgentConnection): String {
        val mode = modeLabel(c.mode)
        val limits = listOfNotNull(
            c.limits.maxOperationAmount?.let { "${Strings.AGENT_CONNECTION_MAX_OPERATION} $it" },
            c.limits.maxDailyAmount?.let { "${Strings.AGENT_CONNECTION_MAX_DAILY} $it" },
        ).joinToString(", ")
        return "${c.name} — $mode" + if (limits.isNotEmpty()) " ($limits)" else ""
    }

    private fun selected(): AgentConnection? = connections.getOrNull(list.currentRow())

    @Suppress("UNUSED_PARAMETER")
    private fun onAdd(checked: Boolean = false) {
        val dialog = AgentConnectionEditDialog(this, null)
        if (dialog.exec() == QDialog.DialogCode.Accepted.value()) {
            store.upsert(dialog.connection())
            refresh()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onEdit(checked: Boolean = false) {
        val c = selected() ?: return
        val dialog = AgentConnectionEditDialog(this, c)
        if (dialog.exec() == QDialog.DialogCode.Accepted.value()) {
            store.upsert(dialog.connection())
            refresh()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onRemove(checked: Boolean = false) {
        val c = selected() ?: return
        store.remove(c.name)
        refresh()
    }
}

private fun modeLabel(mode: WriteMode): String = when (mode) {
    WriteMode.ReadOnly -> Strings.AGENT_MODE_READ_ONLY
    WriteMode.Draft -> Strings.AGENT_MODE_DRAFT
    WriteMode.Write -> Strings.AGENT_MODE_WRITE
}

/** Add/edit form for one [AgentConnection]; validates on OK rather than on close, so an invalid
 * amount reopens the same dialog instead of silently discarding what the user typed. */
private class AgentConnectionEditDialog(parent: QWidget?, existing: AgentConnection?) : QDialog(parent) {
    private val nameField = QLineEdit(existing?.name ?: "")
    private val modeCombo = QComboBox()
    private val maxOperationField = QLineEdit(existing?.limits?.maxOperationAmount?.toPlainString() ?: "")
    private val maxDailyField = QLineEdit(existing?.limits?.maxDailyAmount?.toPlainString() ?: "")
    private var enteredConnection: AgentConnection? = null

    init {
        setWindowTitle(Strings.EDIT_AGENT_CONNECTION_TITLE)
        val layout = QFormLayout(this)
        layout.addRow(Strings.AGENT_CONNECTION_NAME, nameField)

        val modes = listOf(WriteMode.ReadOnly, WriteMode.Draft, WriteMode.Write)
        modes.forEach { modeCombo.addItem(modeLabel(it)) }
        modeCombo.setCurrentIndex(modes.indexOf(existing?.mode ?: WriteMode.Draft).coerceAtLeast(0))
        layout.addRow(Strings.AGENT_CONNECTION_MODE, modeCombo)

        layout.addRow(Strings.AGENT_CONNECTION_MAX_OPERATION, maxOperationField)
        layout.addRow(Strings.AGENT_CONNECTION_MAX_DAILY, maxDailyField)

        val buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButtons(QDialogButtonBox.StandardButton.Ok, QDialogButtonBox.StandardButton.Cancel),
        )
        buttons.accepted.connect(this::onOk)
        buttons.rejected.connect(this::reject)
        layout.addWidget(buttons)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onOk(checked: Boolean = false) {
        val name = nameField.text().trim()
        if (name.isBlank()) {
            QMessageBox.warning(this, Strings.EDIT_AGENT_CONNECTION_TITLE, Strings.AGENT_CONNECTION_NAME_REQUIRED)
            return
        }
        val maxOperation = LimitInput.parse(maxOperationField.text())
        val maxDaily = LimitInput.parse(maxDailyField.text())
        if (maxOperation is LimitInput.Invalid || maxDaily is LimitInput.Invalid) {
            QMessageBox.warning(
                this, Strings.EDIT_AGENT_CONNECTION_TITLE,
                "${Strings.AGENT_CONNECTION_MAX_OPERATION}/${Strings.AGENT_CONNECTION_MAX_DAILY}: число или пусто",
            )
            return
        }
        val modes = listOf(WriteMode.ReadOnly, WriteMode.Draft, WriteMode.Write)
        enteredConnection = AgentConnection(
            name, modes[modeCombo.currentIndex()],
            WriteLimits((maxOperation as? LimitInput.Valid)?.value, (maxDaily as? LimitInput.Valid)?.value),
        )
        accept()
    }

    fun connection(): AgentConnection = checkNotNull(enteredConnection)

    /** A limit field's parsed content: blank means "unlimited" (a valid, deliberate choice, not an
     * error), unparsable text is [Invalid] and makes [onOk] warn and keep the dialog open. */
    private sealed interface LimitInput {
        data object Blank : LimitInput
        data object Invalid : LimitInput
        data class Valid(val value: BigDecimal) : LimitInput

        companion object {
            fun parse(text: String): LimitInput {
                if (text.isBlank()) return Blank
                return try {
                    Valid(BigDecimal(text.trim()))
                } catch (e: NumberFormatException) {
                    Invalid
                }
            }
        }
    }
}
