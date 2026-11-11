// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QMetaObject
import io.qt.widgets.QCheckBox
import io.qt.widgets.QComboBox
import io.qt.widgets.QDialog
import io.qt.widgets.QDialogButtonBox
import io.qt.widgets.QDoubleSpinBox
import io.qt.widgets.QFileDialog
import io.qt.widgets.QFormLayout
import io.qt.widgets.QHBoxLayout
import io.qt.widgets.QLabel
import io.qt.widgets.QLineEdit
import io.qt.widgets.QMessageBox
import io.qt.widgets.QPushButton
import io.qt.widgets.QTableWidget
import io.qt.widgets.QTableWidgetItem
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.plugin.api.SettingsField
import kalita.plugin.api.asBoolean
import kalita.plugin.api.asDoubleOrNull
import kalita.plugin.host.PluginEntry
import kalita.plugin.host.PluginRegistry
import kalita.presentation.Strings

/**
 * «Плагины → Управление плагинами…» (dev_plan.md §9.11, FR-P1): every plugin the registry found —
 * built-in or from `plugins/` — with its version, on/off switch, why it isn't running (an
 * incompatible API version, a failed `initialize`) and its settings form.
 */
class PluginsDialog(parent: QWidget?, private val registry: PluginRegistry, private val onChanged: () -> Unit) : QDialog(parent) {
    private val table = QTableWidget()
    private var entries: List<PluginEntry> = emptyList()

    init {
        setWindowTitle(Strings.PLUGINS_TITLE)
        resize(640, 320)
        val layout = QVBoxLayout(this)
        table.setColumnCount(4)
        table.setHorizontalHeaderLabels(listOf(Strings.PLUGIN_ENABLED, Strings.NAME, Strings.PLUGIN_VERSION, Strings.PLUGIN_STATE))
        table.verticalHeader().setVisible(false)
        table.setSelectionBehavior(io.qt.widgets.QAbstractItemView.SelectionBehavior.SelectRows)
        table.setEditTriggers(io.qt.widgets.QAbstractItemView.EditTrigger.NoEditTriggers)
        table.horizontalHeader().setStretchLastSection(true)
        layout.addWidget(table, 1)
        refresh()

        val actionRow = QHBoxLayout()
        val settingsButton = QPushButton(Strings.PLUGIN_SETTINGS)
        settingsButton.clicked.connect(QMetaObject.Slot0 { onSettings() })
        actionRow.addWidget(settingsButton)
        actionRow.addStretch()
        layout.addLayout(actionRow)

        val buttons = QDialogButtonBox(QDialogButtonBox.StandardButtons(QDialogButtonBox.StandardButton.Close))
        buttons.rejected.connect(this::accept)
        buttons.accepted.connect(this::accept)
        layout.addWidget(buttons)
    }

    private fun refresh() {
        entries = registry.entries
        table.setRowCount(entries.size)
        if (entries.isEmpty()) {
            table.setRowCount(1)
            table.setItem(0, 1, QTableWidgetItem(Strings.PLUGIN_NONE))
            return
        }
        for ((row, entry) in entries.withIndex()) {
            val check = QCheckBox()
            check.setChecked(entry.enabled)
            check.setEnabled(entry.plugin != null)
            val id = entry.descriptor.id
            check.toggled.connect(QMetaObject.Slot1 { on: Boolean ->
                registry.setEnabled(id, on)
                onChanged()
                refresh()
            })
            table.setCellWidget(row, 0, check)
            val name = entry.descriptor.displayName + if (entry.external) " (${Strings.PLUGIN_EXTERNAL})" else ""
            table.setItem(row, 1, QTableWidgetItem(name))
            table.setItem(row, 2, QTableWidgetItem(entry.descriptor.version.toString()))
            table.setItem(row, 3, QTableWidgetItem(stateText(entry)))
        }
        table.resizeColumnsToContents()
    }

    private fun stateText(entry: PluginEntry): String = when {
        entry.problem != null -> entry.problem!!
        !entry.enabled -> Strings.PLUGIN_STATE_DISABLED
        else -> Strings.PLUGIN_STATE_ACTIVE
    }

    private fun onSettings() {
        val entry = entries.getOrNull(table.currentRow()) ?: return
        if (entry.descriptor.settings.fields.isEmpty()) {
            QMessageBox.information(this, Strings.PLUGINS_TITLE, Strings.PLUGIN_NO_SETTINGS)
            return
        }
        if (PluginSettingsDialog(this, registry, entry).exec() == QDialog.DialogCode.Accepted.value()) {
            onChanged()
            refresh()
        }
    }
}

/**
 * The generic settings form of dev_plan.md §9.11 ("Обе оболочки строят форму настроек по схеме"):
 * one widget per [SettingsField] kind, validated by the schema on OK, secrets never echoed back.
 */
class PluginSettingsDialog(parent: QWidget?, private val registry: PluginRegistry, private val entry: PluginEntry) : QDialog(parent) {
    private val editors = LinkedHashMap<String, () -> Any?>()

    init {
        setWindowTitle("${entry.descriptor.displayName} — ${Strings.PLUGINS_TITLE}")
        setMinimumWidth(480)
        val layout = QFormLayout(this)
        val values = registry.settingsValues(entry.descriptor.id)
        for (field in entry.descriptor.settings.fields) {
            val current = values[field.id]
            when (field) {
                is SettingsField.Text -> {
                    val edit = QLineEdit(current?.toString() ?: "")
                    layout.addRow(field.label, edit)
                    editors[field.id] = { edit.text() }
                }
                is SettingsField.Secret -> {
                    val edit = QLineEdit()
                    edit.setEchoMode(QLineEdit.EchoMode.Password)
                    if (registry.hasSecret(entry.descriptor.id, field.id)) edit.setPlaceholderText(Strings.PLUGIN_SECRET_STORED)
                    layout.addRow(field.label, edit)
                    editors[field.id] = { edit.text() }
                }
                is SettingsField.Number -> {
                    val spin = QDoubleSpinBox()
                    spin.setDecimals(0)
                    spin.setRange(0.0, 1_000_000.0)
                    spin.setValue(current.asDoubleOrNull() ?: field.default ?: 0.0)
                    layout.addRow(field.label, spin)
                    editors[field.id] = { spin.value() }
                }
                is SettingsField.Toggle -> {
                    val check = QCheckBox(field.label)
                    check.setChecked(current.asBoolean())
                    layout.addRow("", check)
                    editors[field.id] = { check.isChecked }
                }
                is SettingsField.Choice -> {
                    val combo = QComboBox()
                    field.options.forEach { combo.addItem(it) }
                    val idx = field.options.indexOf(current?.toString() ?: field.default)
                    if (idx >= 0) combo.setCurrentIndex(idx)
                    layout.addRow(field.label, combo)
                    editors[field.id] = { field.options.getOrNull(combo.currentIndex()) }
                }
                is SettingsField.Path -> {
                    val edit = QLineEdit(current?.toString() ?: "")
                    field.placeholder?.let { edit.setPlaceholderText(it) }
                    val row = QWidget()
                    val rowLayout = QHBoxLayout(row)
                    rowLayout.setContentsMargins(0, 0, 0, 0)
                    rowLayout.addWidget(edit, 1)
                    val browse = QPushButton(Strings.BROWSE)
                    browse.clicked.connect(QMetaObject.Slot0 {
                        val picked = if (field.kind == SettingsField.Path.Kind.Directory) {
                            QFileDialog.getExistingDirectory(this, field.label, edit.text().ifBlank { System.getProperty("user.home") })
                        } else {
                            QFileDialog.getOpenFileName(this, field.label, edit.text().ifBlank { System.getProperty("user.home") }).result
                        }
                        if (!picked.isNullOrEmpty()) edit.setText(picked)
                    })
                    rowLayout.addWidget(browse)
                    layout.addRow(field.label, row)
                    editors[field.id] = { edit.text() }
                }
            }
        }
        val buttons = QDialogButtonBox(QDialogButtonBox.StandardButtons(QDialogButtonBox.StandardButton.Ok, QDialogButtonBox.StandardButton.Cancel))
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText(Strings.CANCEL)
        buttons.accepted.connect(QMetaObject.Slot0 { onOk() })
        buttons.rejected.connect(this::reject)
        layout.addRow(buttons)
    }

    private fun onOk() {
        val values = editors.mapValues { (_, read) -> read() }
        val errors = registry.updateSettings(entry.descriptor.id, values)
        if (errors.isNotEmpty()) {
            val labels = entry.descriptor.settings.fields.associate { it.id to it.label }
            QMessageBox.warning(this, windowTitle(), errors.joinToString("\n") { "${labels[it.fieldId] ?: it.fieldId}: ${it.message}" })
            return
        }
        accept()
    }
}

/** A row-less label used in place of a form field. */
internal fun hint(text: String): QLabel = QLabel(text).also { it.setWordWrap(true) }
