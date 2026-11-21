// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QMetaObject
import io.qt.widgets.QAbstractItemView
import io.qt.widgets.QDialog
import io.qt.widgets.QLabel
import io.qt.widgets.QMessageBox
import io.qt.widgets.QPushButton
import io.qt.widgets.QTabWidget
import io.qt.widgets.QTableWidget
import io.qt.widgets.QTableWidgetItem
import io.qt.widgets.QVBoxLayout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun agentInboxRows(result: JsonObject): List<List<String>> =
    result.getValue("entries").jsonArray.map { element ->
        val entry = element.jsonObject
        val preview = entry.getValue("preview").jsonObject
        fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        listOf(entry.text("createdAt"), entry.text("device"), entry.text("clientRequestId"), entry.text("id")) +
            listOf("date", "amount", "currency", "operationType", "budgetItem", "counterparty", "accountFrom", "accountTo", "comment")
                .map { preview.text(it) }
    }

internal fun installInboxMenu(window: MainWindow, controller: AppController) {
    val menu = window.menuBar().addMenu("Входящие")
    menu.addAction("Открыть входящие…").triggered.connect(QMetaObject.Slot0 {
        if (controller.state.value !is AppState.Ready) {
            QMessageBox.information(window, "Входящие", "Сначала откройте базу.")
        } else {
            InboxDialog(window, controller).exec()
            window.updateFromState()
        }
    })
}

internal class InboxDialog(
    private val window: MainWindow,
    private val controller: AppController,
) : QDialog(window) {
    private val agents = table(listOf("Создано", "Агент", "Запрос", "ID", "Дата", "Сумма", "Валюта", "Тип", "Статья", "Контрагент", "Со счёта", "На счёт", "Комментарий"))
    private val recovery = table(listOf("Создано", "Агент", "Запрос", "ID", "Дата", "Сумма", "Валюта", "Тип", "Статья", "Контрагент", "Со счёта", "На счёт", "Комментарий"))
    private val banks = table(listOf("Источник", "ID", "Счёт", "Дата", "Сумма", "Валюта", "Описание", "Продавец", "MCC"))
    private val agentStatus = QLabel()
    private val recoveryStatus = QLabel()
    private val bankStatus = QLabel()

    init {
        setWindowTitle("Входящие")
        resize(1100, 600)
        val layout = QVBoxLayout(this)
        val tabs = QTabWidget()
        val agentPage = io.qt.widgets.QWidget()
        val agentLayout = QVBoxLayout(agentPage)
        agentLayout.addWidget(agentStatus)
        agentLayout.addWidget(agents, 1)
        val approve = QPushButton("Подтвердить")
        approve.clicked.connect(QMetaObject.Slot0 { decideAgent(true) })
        agentLayout.addWidget(approve)
        val reject = QPushButton("Отклонить")
        reject.clicked.connect(QMetaObject.Slot0 { decideAgent(false) })
        agentLayout.addWidget(reject)
        tabs.addTab(agentPage, "Черновики агентов")
        val recoveryPage = io.qt.widgets.QWidget()
        val recoveryLayout = QVBoxLayout(recoveryPage)
        recoveryLayout.addWidget(recoveryStatus)
        recoveryLayout.addWidget(recovery, 1)
        val committed = QPushButton("Операция записана")
        committed.clicked.connect(QMetaObject.Slot0 { resolveAgent(true) })
        recoveryLayout.addWidget(committed)
        val notCommitted = QPushButton("Операция не записана")
        notCommitted.clicked.connect(QMetaObject.Slot0 { resolveAgent(false) })
        recoveryLayout.addWidget(notCommitted)
        tabs.addTab(recoveryPage, "Прерванные подтверждения")
        val bankPage = io.qt.widgets.QWidget()
        val bankLayout = QVBoxLayout(bankPage)
        bankLayout.addWidget(bankStatus)
        bankLayout.addWidget(banks, 1)
        val process = QPushButton("Обработать банковские строки…")
        process.clicked.connect(QMetaObject.Slot0 {
            runPendingStatements(this, controller)
            window.updateFromState()
            refresh()
        })
        bankLayout.addWidget(process)
        tabs.addTab(bankPage, "Банковские строки")
        layout.addWidget(tabs, 1)
        val refresh = QPushButton("Обновить")
        refresh.clicked.connect(QMetaObject.Slot0 { refresh() })
        layout.addWidget(refresh)
        refresh()
    }

    private fun refresh() {
        agents.setRowCount(0)
        recovery.setRowCount(0)
        banks.setRowCount(0)
        if (controller.state.value !is AppState.Ready) return
        runCatching {
            agentInboxRows(controller.agentInbox()) to agentInboxRows(controller.agentInboxRecovery())
        }.onSuccess { (rows, recoveryRows) ->
            fill(agents, rows)
            fill(recovery, recoveryRows)
            agentStatus.setText("Черновиков: ${rows.size}. Прерванных подтверждений: ${recoveryRows.size}.")
            recoveryStatus.setText("Проверьте журнал базы и историю изменений. Неоднозначные операции оставьте без восстановления.")
        }.onFailure {
            agentStatus.setText("Не удалось прочитать черновики: ${it.message ?: it}")
            recoveryStatus.setText("Не удалось прочитать прерванные подтверждения: ${it.message ?: it}")
        }
        runCatching { controller.pendingStatements() }.onSuccess { rows ->
            fill(banks, rows.map { listOf(it.source, it.externalId, it.bankAccount, it.postedAt.toString(), it.amount.toPlainString(), it.currency, it.description, it.merchant.orEmpty(), it.mcc.orEmpty()) })
            bankStatus.setText("Ожидающих строк: ${rows.size}")
        }.onFailure { bankStatus.setText("Не удалось прочитать банковские строки: ${it.message ?: it}") }
    }

    private fun decideAgent(approve: Boolean) {
        val row = agents.currentRow()
        val id = if (row >= 0) agents.item(row, 3)?.text() else null
        if (id.isNullOrEmpty()) {
            QMessageBox.information(this, "Входящие", "Выберите черновик.")
            return
        }
        val action = if (approve) "Подтвердить" else "Отклонить"
        if (QMessageBox.question(this, "Входящие", "$action черновик $id?") != QMessageBox.StandardButton.Yes) return
        runCatching { if (approve) controller.approveAgentInbox(id) else controller.rejectAgentInbox(id) }
            .onSuccess { result ->
                if (result["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                    QMessageBox.warning(this, "Входящие", "Не удалось обработать черновик: $result")
                }
            }.onFailure { QMessageBox.warning(this, "Входящие", "Не удалось обработать черновик: ${it.message ?: it}") }
        window.updateFromState()
        refresh()
    }

    private fun resolveAgent(committed: Boolean) {
        val row = recovery.currentRow()
        val id = if (row >= 0) recovery.item(row, 3)?.text() else null
        if (id.isNullOrEmpty()) {
            QMessageBox.information(this, "Восстановление", "Выберите прерванное подтверждение.")
            return
        }
        val outcome = if (committed) "Операция точно записана: черновик будет отмечен подтверждённым. Если это ошибка, операция может быть потеряна." else
            "Операция точно не записана: черновик вернётся в ожидание. Если это ошибка, повторное подтверждение может создать дубликат."
        if (QMessageBox.question(this, "Восстановление", "Проверьте журнал базы и историю изменений.\n\n$outcome\n\nВы проверили черновик $id и уверены в результате?") != QMessageBox.StandardButton.Yes) return
        runCatching { controller.resolveAgentInbox(id, committed) }
            .onSuccess { result ->
                if (result["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                    QMessageBox.warning(this, "Восстановление", "Не удалось восстановить черновик: $result")
                }
            }.onFailure { QMessageBox.warning(this, "Восстановление", "Не удалось восстановить черновик: ${it.message ?: it}") }
        refresh()
    }

    private fun table(headers: List<String>): QTableWidget = QTableWidget(0, headers.size).also {
        it.setHorizontalHeaderLabels(headers)
        it.verticalHeader().setVisible(false)
        it.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        it.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        it.horizontalHeader().setStretchLastSection(true)
    }

    private fun fill(table: QTableWidget, rows: List<List<String>>) {
        table.setRowCount(rows.size)
        rows.forEachIndexed { row, values -> values.forEachIndexed { column, value -> table.setItem(row, column, QTableWidgetItem(value)) } }
        table.resizeColumnsToContents()
    }
}