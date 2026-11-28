// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QMetaObject
import io.qt.core.Qt
import io.qt.core.QDate
import io.qt.widgets.QBoxLayout
import io.qt.widgets.QCheckBox
import io.qt.widgets.QDateEdit
import io.qt.widgets.QDialog
import io.qt.widgets.QDialogButtonBox
import io.qt.widgets.QDoubleSpinBox
import io.qt.widgets.QGroupBox
import io.qt.widgets.QHBoxLayout
import io.qt.widgets.QLabel
import io.qt.widgets.QMessageBox
import io.qt.widgets.QFormLayout
import io.qt.widgets.QLayout
import io.qt.widgets.QPushButton
import io.qt.widgets.QRadioButton
import io.qt.widgets.QScrollArea
import io.qt.widgets.QSpinBox
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.core.DuplicateAction
import kalita.core.DuplicateCheckMode
import kalita.core.DuplicateCheckOptions
import kalita.core.DuplicateFinding
import kalita.core.DuplicateSession
import kalita.core.DuplicateSource
import kalita.core.EnrichChange
import kalita.presentation.DuplicateTexts
import kalita.presentation.Strings
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The universal duplicate-resolution window (dev_plan.md «Контроль дублей»): one question per
 * finding — which journal rows look like the draft, and what to do about it. Every answer goes
 * into [DuplicateSession]; when nothing is left the caller reads [DuplicateSession.plan] and
 * writes everything as one change set.
 */
internal class DuplicateConflictDialog(
    parent: QWidget?,
    private val dataset: kalita.core.Dataset,
    private val session: DuplicateSession,
) : QDialog(parent) {
    enum class Result { Applied, Later, Cancelled }

    private var outcome = Result.Cancelled
    private val info = QLabel()
    private val actionsLayout = QVBoxLayout()
    private val actionsWidget = QWidget()
    private val enrichHint = QLabel(Strings.DUPLICATES_ENRICH)
    private val enrichLayout = QVBoxLayout()
    private val enrichWidget = QWidget()
    private val okButton = QPushButton(Strings.APPLY)
    private val laterButton = QPushButton(Strings.RECEIPT_LATER)
    private val buttons = QHBoxLayout()
    private var changes = listOf<EnrichChange>()
    private var current: DuplicateFinding? = null

    init {
        setWindowTitle(Strings.DUPLICATES_TITLE)
        resize(780, 600)
        val layout = QVBoxLayout(this)
        info.setWordWrap(true)
        info.setTextFormat(Qt.TextFormat.PlainText)
        layout.addWidget(info)
        layout.addWidget(QLabel(Strings.DUPLICATES_QUESTION))
        actionsWidget.setLayout(actionsLayout)
        layout.addWidget(actionsWidget)
        enrichHint.setWordWrap(true)
        layout.addWidget(enrichHint)
        enrichWidget.setLayout(enrichLayout)
        layout.addWidget(enrichWidget)
        layout.addStretch(1)
        okButton.clicked.connect(QMetaObject.Slot1<Boolean> { onOk() })
        laterButton.clicked.connect(QMetaObject.Slot1<Boolean> { outcome = Result.Later; accept() })
        buttons.addWidget(okButton)
        buttons.addWidget(laterButton)
        buttons.addStretch(1)
        val cancel = QPushButton(Strings.CANCEL)
        cancel.clicked.connect(QMetaObject.Slot1<Boolean> { outcome = Result.Cancelled; accept() })
        buttons.addWidget(cancel)
        layout.addLayout(buttons)
    }

    /** Shows the findings one by one; [Result.Applied] means every answer is in [session]. */
    fun run(): Result {
        showFinding(session.next())
        exec()
        return outcome
    }

    private fun showFinding(finding: DuplicateFinding?) {
        if (finding == null) {
            outcome = Result.Applied
            accept()
            return
        }
        current = finding
        info.setText(DuplicateTexts.finding(dataset, session.candidates, finding))
        val actions = session.actions(finding).filterNot { it == DuplicateAction.DISCARD }
        clearLayout(actionsLayout)
        val radios = actions.map { it to QRadioButton(Strings.duplicateActionLabel(it)) }
        val default = actions.firstOrNull { it == DuplicateAction.KEEP_OLD }
            ?: actions.firstOrNull { it == DuplicateAction.KEEP_BOTH }
            ?: actions.firstOrNull()
        radios.firstOrNull { it.first == default }?.second?.setChecked(true)
        radios.forEach { actionsLayout.addWidget(it.second) }
        changes = session.changes(finding)
        clearLayout(enrichLayout)
        if (changes.isEmpty()) {
            enrichHint.setVisible(false)
            enrichWidget.setVisible(false)
        } else {
            enrichHint.setVisible(true)
            enrichWidget.setVisible(true)
            for (change in changes) {
                val check = QCheckBox(DuplicateTexts.change(dataset, change))
                check.setChecked(change.suggested)
                enrichLayout.addWidget(check)
            }
        }
        laterButton.setVisible(actions.contains(DuplicateAction.LATER))
    }

    private fun onOk() {
        val finding = current ?: return
        val chosen = (0 until actionsLayout.count()).mapNotNull { actionsLayout.itemAt(it)?.widget() as? QRadioButton }
            .firstOrNull { it.isChecked } ?: return
        val action = session.actions(finding).firstOrNull { Strings.duplicateActionLabel(it) == chosen.text() } ?: return
        val checked = (0 until enrichLayout.count()).mapNotNull { enrichLayout.itemAt(it)?.widget() as? QCheckBox }
            .filter { it.isChecked }
            .mapNotNull { check -> changes.firstOrNull { DuplicateTexts.change(dataset, it) == check.text() } }
        session.decide(finding, action, checked)
        showFinding(session.next())
    }

    private fun clearLayout(layout: QLayout) {
        while (layout.count() > 0) {
            val item = layout.takeAt(0)
            // Hidden widgets are skipped by the layout; the dialog's own lifetime frees them.
            item?.widget()?.setVisible(false)
        }
    }
}

/** The «Контроль дублей…» settings: the check mode and how far amounts and dates may drift. */
internal class DuplicateSettingsDialog(
    private val controller: AppController,
    parent: QWidget?,
) : QDialog(parent) {
    private val onSave = QRadioButton(Strings.DUPLICATES_MODE_ON_SAVE)
    private val manual = QRadioButton(Strings.DUPLICATES_MODE_MANUAL)
    private val tolerance = QDoubleSpinBox()
    private val days = QSpinBox()

    init {
        setWindowTitle(Strings.DUPLICATES_TITLE)
        resize(480, 240)
        val options = controller.duplicateOptions()
        val layout = QVBoxLayout(this)
        val form = QFormLayout()
        form.addRow(QLabel(Strings.DUPLICATES_MODE))
        (if (options.mode == DuplicateCheckMode.ON_SAVE) onSave else manual).setChecked(true)
        form.addRow(onSave)
        form.addRow(manual)
        tolerance.setDecimals(2)
        tolerance.setRange(0.0, 10000.0)
        tolerance.setSingleStep(1.0)
        tolerance.setValue(options.amountTolerance.toDouble())
        form.addRow(Strings.DUPLICATES_TOLERANCE, tolerance)
        days.setRange(0, 31)
        days.setValue(options.daysWindow)
        form.addRow(Strings.DUPLICATES_DAYS, days)
        layout.addLayout(form)
        layout.addStretch(1)
        val buttons = QDialogButtonBox(QDialogButtonBox.StandardButtons(QDialogButtonBox.StandardButton.Ok, QDialogButtonBox.StandardButton.Cancel))
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText(Strings.CANCEL)
        buttons.accepted.connect(this::accept)
        buttons.rejected.connect(this::reject)
        layout.addWidget(buttons)
    }

    override fun accept() {
        val mode = if (onSave.isChecked) DuplicateCheckMode.ON_SAVE else DuplicateCheckMode.MANUAL
        controller.setDuplicateOptions(DuplicateCheckOptions(mode, BigDecimal.valueOf(tolerance.value()), days.value()))
        super.accept()
    }
}

/** «Проверить дубли за период…»: a period, then the journal rows that look like one purchase entered twice. */
internal class DuplicateScanDialog(
    private val controller: AppController,
    parent: QWidget?,
) : QDialog(parent) {
    private val from = QDateEdit()
    private val to = QDateEdit()
    private val status = QLabel()
    private val results = QVBoxLayout()
    private val resultsWidget = QWidget()

    init {
        setWindowTitle(Strings.DUPLICATES_SCAN_MENU.removeSuffix("…"))
        resize(860, 620)
        val today = LocalDate.now()
        from.setCalendarPopup(true)
        from.setDate(QDate(today.minusMonths(1).year, today.minusMonths(1).monthValue, today.dayOfMonth))
        to.setCalendarPopup(true)
        to.setDate(QDate(today.year, today.monthValue, today.dayOfMonth))
        val period = QHBoxLayout()
        period.addWidget(QLabel(Strings.DUPLICATES_PERIOD))
        period.addWidget(from)
        period.addWidget(QLabel("—"))
        period.addWidget(to)
        val scan = QPushButton(Strings.DUPLICATES_SCAN)
        scan.clicked.connect(QMetaObject.Slot1<Boolean> { doScan() })
        period.addWidget(scan)
        period.addStretch(1)
        val outer = QVBoxLayout(this)
        outer.addLayout(period)
        status.setWordWrap(true)
        outer.addWidget(status)
        resultsWidget.setLayout(results)
        val scroll = QScrollArea()
        scroll.setWidgetResizable(true)
        scroll.setWidget(resultsWidget)
        outer.addWidget(scroll, 1)
        doScan()
    }

    private fun doScan() {
        val f = from.date().let { LocalDate.of(it.year(), it.month(), it.day()) }
        val t = to.date().let { LocalDate.of(it.year(), it.month(), it.day()) }
        clearLayout(results)
        val scan = try {
            controller.scanDuplicates(f, t)
        } catch (e: Exception) {
            status.setText(e.message ?: e.toString())
            return
        }
        if (scan.clean) {
            status.setText(if (scan.truncated) Strings.DUPLICATES_NONE + " " + Strings.DUPLICATES_TRUNCATED else Strings.DUPLICATES_NONE)
            return
        }
        status.setText(Strings.duplicatesFound(scan.groups.size) + if (scan.truncated) " " + Strings.DUPLICATES_TRUNCATED else "")
        val dataset = controller.currentDatabase() ?: return
        for (group in scan.groups) {
            val box = QGroupBox(
                (group.rows.firstOrNull()?.date?.let(kalita.presentation.Formatter::date) ?: "—") +
                    " — " + (group.rows.lastOrNull()?.date?.let(kalita.presentation.Formatter::date) ?: ""),
            )
            val layout = QVBoxLayout(box)
            val suggested = group.suggested.toList().sorted()
            for (row in group.rows) {
                val label = QLabel(
                    DuplicateTexts.row(dataset, kalita.core.DuplicateOperation(row, false, false)) +
                        if (row.id in group.suggested) "  ← ${Strings.DUPLICATES_LESS_DETAIL}" else "",
                )
                label.setWordWrap(true)
                layout.addWidget(label)
            }
            for (link in group.links) {
                layout.addWidget(
                    QLabel(
                        "  " + link.right.joinToString(" + ", "#${link.left} = ") { "#$it" } +
                            " · " + Strings.duplicateDiff(link.difference),
                    ),
                )
            }
            val buttons = QHBoxLayout()
            if (suggested.isNotEmpty()) {
                val delete = QPushButton(Strings.duplicatesDeleteSuggested(suggested))
                delete.clicked.connect(QMetaObject.Slot1<Boolean> {
                    if (QMessageBox.question(this, Strings.DUPLICATES_TITLE, Strings.duplicatesDeleteSuggested(suggested) + "?") == QMessageBox.StandardButton.Yes) {
                        val result = controller.softDeleteOperations(suggested)
                        if (result is kalita.storage.api.SubmitResult.Failed) QMessageBox.warning(this, Strings.DUPLICATES_TITLE, result.reason)
                        doScan()
                    }
                })
                buttons.addWidget(delete)
            }
            val keep = QPushButton(Strings.DUPLICATES_KEEP)
            keep.clicked.connect(QMetaObject.Slot1<Boolean> { box.setVisible(false) })
            buttons.addWidget(keep)
            buttons.addStretch(1)
            layout.addLayout(buttons)
            results.addWidget(box)
        }
        results.addStretch(1)
    }

    private fun clearLayout(layout: QLayout) {
        while (layout.count() > 0) {
            val item = layout.takeAt(0)
            // Hidden widgets are skipped by the layout; the dialog's own lifetime frees them.
            item?.widget()?.setVisible(false)
        }
    }
}
