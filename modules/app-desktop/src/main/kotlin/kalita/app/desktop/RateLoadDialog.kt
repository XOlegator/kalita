// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QDate
import io.qt.core.Qt
import io.qt.gui.QCursor
import io.qt.widgets.QAbstractItemView
import io.qt.widgets.QApplication
import io.qt.widgets.QComboBox
import io.qt.widgets.QDateEdit
import io.qt.widgets.QDialog
import io.qt.widgets.QDialogButtonBox
import io.qt.widgets.QHBoxLayout
import io.qt.widgets.QLabel
import io.qt.widgets.QProgressBar
import io.qt.widgets.QPushButton
import io.qt.widgets.QTableWidget
import io.qt.widgets.QTableWidgetItem
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.plugin.host.RateLoadResult
import kalita.presentation.Formatter
import kalita.presentation.Strings
import kalita.storage.api.SubmitResult
import java.time.LocalDate

/**
 * «Загрузка курсов» (dev_plan.md FR-R12), laid out like the original's «Сервис → Курсы из
 * Интернета» window: a period, «Загрузить», a progress bar, and what arrived — the newest rate
 * per currency. Rates are written as soon as they are fetched, as one change set; a second run
 * over the same period finds nothing new. With several rate-source plugins a selector appears.
 */
class RateLoadDialog(
    parent: QWidget?,
    private val controller: AppController,
    today: LocalDate = LocalDate.now(),
) : QDialog(parent) {
    private val sources = controller.rateSources()
    private val sourceCombo = QComboBox()
    private val fromDate = QDateEdit()
    private val toDate = QDateEdit()
    private val loadButton = QPushButton(Strings.RATE_LOAD_BUTTON)
    private val progress = QProgressBar()
    private val table = QTableWidget(0, 3)
    private val status = QLabel()

    /** Whether anything was written — the caller refreshes its views then. */
    var changed = false
        private set

    init {
        setWindowTitle(Strings.RATE_LOAD_TITLE)
        resize(460, 420)
        val layout = QVBoxLayout(this)

        if (sources.size > 1) {
            for ((descriptor, source) in sources) sourceCombo.addItem("${source.sourceName} — ${descriptor.displayName}")
            layout.addWidget(sourceCombo)
        }

        val period = QHBoxLayout()
        period.addWidget(QLabel(Strings.FROM_DATE))
        for (edit in listOf(fromDate, toDate)) {
            edit.setCalendarPopup(true)
            edit.setDate(QDate(today.year, today.monthValue, today.dayOfMonth))
        }
        period.addWidget(fromDate, 1)
        period.addWidget(QLabel(Strings.TO_DATE))
        period.addWidget(toDate, 1)
        loadButton.setEnabled(sources.isNotEmpty())
        loadButton.clicked.connect(this::onLoad)
        period.addWidget(loadButton)
        layout.addLayout(period)

        progress.setRange(0, 100)
        progress.setValue(0)
        layout.addWidget(progress)

        table.setHorizontalHeaderLabels(listOf(Strings.CURRENCY, Strings.DATE, Strings.RATE))
        table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        table.verticalHeader().setVisible(false)
        table.horizontalHeader().setStretchLastSection(true)
        table.setColumnWidth(0, 200)
        layout.addWidget(table, 1)

        status.setWordWrap(true)
        status.setText(if (sources.isEmpty()) Strings.RATE_LOAD_NO_SOURCE else sources.first().second.let { "${it.sourceName}, курсы в валюте «${it.quoteCurrencyName}»" })
        layout.addWidget(status)

        val buttons = QDialogButtonBox(QDialogButtonBox.StandardButtons(QDialogButtonBox.StandardButton.Close))
        buttons.button(QDialogButtonBox.StandardButton.Close)?.setText(Strings.CLOSE)
        buttons.rejected.connect(this::accept)
        layout.addWidget(buttons)
    }

    private fun localDate(edit: QDateEdit): LocalDate = edit.date().let { LocalDate.of(it.year(), it.month(), it.day()) }

    @Suppress("UNUSED_PARAMETER")
    private fun onLoad(checked: Boolean = false) {
        val sourceId = sources.getOrNull(sourceCombo.currentIndex().coerceAtLeast(0))?.first?.id ?: return
        loadButton.setEnabled(false)
        progress.setRange(0, 0) // busy indicator while the request is out
        QApplication.setOverrideCursor(QCursor(Qt.CursorShape.WaitCursor))
        QApplication.processEvents()
        val result = try {
            controller.planRateLoad(sourceId, localDate(fromDate), localDate(toDate))
        } finally {
            QApplication.restoreOverrideCursor()
            progress.setRange(0, 100)
            loadButton.setEnabled(true)
        }
        when (result) {
            is RateLoadResult.Refused -> {
                progress.setValue(0)
                status.setText(result.reason)
            }
            is RateLoadResult.Plan -> show(result)
        }
    }

    private fun show(plan: RateLoadResult.Plan) {
        val names = controller.currentDatabase()?.currencies.orEmpty().associate { it.id to (it.name ?: "id:${it.id}") }
        val newest = plan.quotes.groupBy { it.currencyId }.map { (_, quotes) -> quotes.maxBy { it.date } }.sortedBy { names[it.currencyId] }
        table.setRowCount(newest.size)
        newest.forEachIndexed { row, quote ->
            table.setItem(row, 0, QTableWidgetItem(names[quote.currencyId] ?: "id:${quote.currencyId}"))
            table.setItem(row, 1, QTableWidgetItem(Formatter.date(quote.date)))
            table.setItem(row, 2, QTableWidgetItem(Formatter.rate(quote.value)))
        }
        val lines = mutableListOf<String>()
        if (plan.isEmpty) {
            lines += "${Strings.RATE_LOAD_NOTHING_NEW} (без изменений: ${plan.unchanged})"
        } else {
            when (val submitted = controller.applyRateLoad(plan)) {
                is SubmitResult.Applied -> {
                    changed = true
                    lines += Strings.rateLoadDone(plan.inserts.size, plan.updates.size, plan.unchanged)
                }
                is SubmitResult.Conflicts -> lines += "Не записано: данные изменены извне, повторите загрузку."
                is SubmitResult.Failed -> lines += "Не записано: ${submitted.reason}"
            }
        }
        if (plan.unmatched.isNotEmpty()) lines += "${Strings.RATE_LOAD_UNMATCHED}: ${plan.unmatched.joinToString { it.name ?: "id:${it.id}" }}"
        status.setText(lines.joinToString("\n"))
        progress.setValue(100)
    }
}
