// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QByteArray
import io.qt.core.QMetaObject
import io.qt.core.QSettings
import io.qt.core.Qt
import io.qt.gui.QAction
import io.qt.gui.QCloseEvent
import io.qt.gui.QKeySequence
import io.qt.widgets.QApplication
import io.qt.widgets.QDockWidget
import io.qt.widgets.QFileDialog
import io.qt.widgets.QLabel
import io.qt.widgets.QMainWindow
import io.qt.widgets.QMenu
import io.qt.widgets.QMessageBox
import io.qt.widgets.QTabWidget
import kalita.presentation.Strings
import java.io.File

/**
 * The application's main window (dev_plan.md §9.9, stages 3–4): menu bar, status bar, central
 * tab widget for dictionary browsers and future journal grid, and dock panels for info/balances.
 * Saves and restores its geometry and dock state via [QSettings].
 */
class MainWindow(private val controller: AppController, private val settings: AppSettings) : QMainWindow() {
    private val statusLabel = QLabel()
    private val recentMenu = QMenu("&Недавние")
    private val centralTabs = QTabWidget()

    // Dictionary browsers
    private val accountsBrowser = DictionaryBrowser(controller, DictionaryKind.Accounts)
    private val counterpartiesBrowser = DictionaryBrowser(controller, DictionaryKind.Counterparties)
    private val currenciesBrowser = DictionaryBrowser(controller, DictionaryKind.Currencies)
    private val budgetTreeBrowser = BudgetTreeBrowser(controller)
    private val rateBrowser = RateBrowser(controller)

    // Journal and dock panels (stage 5)
    private val journalGrid = JournalGrid(controller)
    private val saldoPanel = SaldoPanel(controller)
    private val historyPanel = HistoryPanel(controller)

    init {
        setWindowTitle("Kalita")
        resize(960, 640)
        centralTabs.setTabsClosable(true)
        centralTabs.tabCloseRequested.connect(this::onTabCloseRequested)
        setCentralWidget(centralTabs)
        setupMenuBar()
        setupStatusBar()
        setupDockPanels()
        restoreWindowState()
    }

    private fun setupMenuBar() {
        val fileMenu = menuBar().addMenu("&Файл")

        val openAction = QAction("&Открыть…", this)
        openAction.setShortcut(QKeySequence(QKeySequence.StandardKey.Open))
        openAction.triggered.connect(this::onOpen)
        fileMenu.addAction(openAction)

        fileMenu.addMenu(recentMenu)
        rebuildRecentMenu()

        fileMenu.addSeparator()

        val refreshAction = QAction("О&бновить", this)
        refreshAction.setShortcut(QKeySequence("F5"))
        refreshAction.triggered.connect(this::onRefresh)
        fileMenu.addAction(refreshAction)

        fileMenu.addSeparator()

        val closeAction = QAction("&Закрыть базу", this)
        closeAction.setShortcut(QKeySequence("Ctrl+W"))
        closeAction.triggered.connect(this::onCloseDatabase)
        fileMenu.addAction(closeAction)

        fileMenu.addSeparator()

        val exitAction = QAction("&Выход", this)
        exitAction.setShortcut(QKeySequence(QKeySequence.StandardKey.Quit))
        exitAction.triggered.connect(QMetaObject.Slot0 { QApplication.quit() })
        fileMenu.addAction(exitAction)

        // Справочники menu (FR-10…FR-12)
        val dictMenu = menuBar().addMenu("&Справочники")

        val accountsAction = QAction(Strings.ACCOUNTS_TITLE, this)
        accountsAction.triggered.connect(QMetaObject.Slot0 { openTab(accountsBrowser, Strings.ACCOUNTS_TITLE) })
        dictMenu.addAction(accountsAction)

        val counterpartiesAction = QAction(Strings.COUNTERPARTIES_TITLE, this)
        counterpartiesAction.triggered.connect(QMetaObject.Slot0 { openTab(counterpartiesBrowser, Strings.COUNTERPARTIES_TITLE) })
        dictMenu.addAction(counterpartiesAction)

        val budgetAction = QAction(Strings.BUDGET_ITEMS_TITLE, this)
        budgetAction.triggered.connect(QMetaObject.Slot0 { openTab(budgetTreeBrowser, Strings.BUDGET_ITEMS_TITLE) })
        dictMenu.addAction(budgetAction)

        val currenciesAction = QAction(Strings.CURRENCIES_TITLE, this)
        currenciesAction.triggered.connect(QMetaObject.Slot0 { openTab(currenciesBrowser, Strings.CURRENCIES_TITLE) })
        dictMenu.addAction(currenciesAction)

        dictMenu.addSeparator()

        val ratesAction = QAction(Strings.RATES_TITLE, this)
        ratesAction.triggered.connect(QMetaObject.Slot0 { openTab(rateBrowser, Strings.RATES_TITLE) })
        dictMenu.addAction(ratesAction)

        // Журналы menu (FR-20)
        val journalMenu = menuBar().addMenu("&Журналы")
        val incExpAction = QAction(Strings.JOURNAL_MENU, this)
        incExpAction.triggered.connect(QMetaObject.Slot0 { openJournal() })
        journalMenu.addAction(incExpAction)

        journalMenu.addSeparator()

        val historyAction = QAction(Strings.HISTORY_TITLE, this)
        historyAction.triggered.connect(QMetaObject.Slot0 { openTab(historyPanel, Strings.HISTORY_TITLE) })
        journalMenu.addAction(historyAction)

        val helpMenu = menuBar().addMenu("&Справка")
        val aboutAction = QAction("О &программе", this)
        aboutAction.triggered.connect(this::onAbout)
        helpMenu.addAction(aboutAction)
    }

    private fun setupStatusBar() {
        statusBar().addWidget(statusLabel)
        updateStatusBar()
    }

    private fun setupDockPanels() {
        val saldoDock = QDockWidget(Strings.SALDO_TITLE, this)
        saldoDock.setWidget(saldoPanel)
        saldoDock.setObjectName("saldoDock")
        addDockWidget(Qt.DockWidgetArea.RightDockWidgetArea, saldoDock)

        val historyDock = QDockWidget(Strings.HISTORY_TITLE, this)
        historyDock.setWidget(historyPanel)
        historyDock.setObjectName("historyDock")
        addDockWidget(Qt.DockWidgetArea.BottomDockWidgetArea, historyDock)
    }

    private fun openJournal() {
        openTab(journalGrid, Strings.JOURNAL_MENU)
    }

    /** Open a browser widget as a tab or activate its existing tab. */
    private fun openTab(widget: io.qt.widgets.QWidget, title: String) {
        val existing = (0 until centralTabs.count()).firstOrNull { centralTabs.widget(it) === widget }
        if (existing != null) {
            centralTabs.setCurrentIndex(existing)
            return
        }
        centralTabs.addTab(widget, title)
        centralTabs.setCurrentWidget(widget)
        when (widget) {
            is DictionaryBrowser -> widget.refreshData()
            is BudgetTreeBrowser -> widget.refreshData()
            is RateBrowser -> widget.refreshData()
            is JournalGrid -> widget.refreshData()
            is HistoryPanel -> widget.refreshData()
        }
    }

    private fun onTabCloseRequested(index: Int) {
        centralTabs.removeTab(index)
    }

    fun updateFromState() {
        val appState = controller.state.value
        when (appState) {
            is AppState.Ready -> {
                val ro = if (appState.readOnly) " [только чтение]" else ""
                setWindowTitle("Kalita — ${File(appState.filePath).name}$ro")
                refreshAllBrowsers()
                openJournal()
            }
            is AppState.NoDatabase -> {
                setWindowTitle("Kalita")
            }
            is AppState.Error -> {
                setWindowTitle("Kalita")
            }
        }
        updateStatusBar()
    }

    private fun updateStatusBar() {
        val appState = controller.state.value
        statusLabel.setText(
            when (appState) {
                is AppState.Ready -> {
                    val ro = if (appState.readOnly) " · Только чтение" else ""
                    val ops = appState.dataset.operations.count { !it.deleted }
                    "${File(appState.filePath).name}$ro · Операций: $ops"
                }
                is AppState.NoDatabase -> "Нет открытой базы"
                is AppState.Error -> "Ошибка"
            },
        )
    }

    private fun refreshAllBrowsers() {
        accountsBrowser.refreshData()
        counterpartiesBrowser.refreshData()
        currenciesBrowser.refreshData()
        budgetTreeBrowser.refreshData()
        rateBrowser.refreshData()
        journalGrid.refreshData()
        saldoPanel.refreshData()
        historyPanel.refreshData()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onOpen(checked: Boolean = false) {
        val dialogResult = QFileDialog.getOpenFileName(
            this,
            "Открыть базу данных",
            settings.lastDatabasePath?.let { File(it).parent } ?: System.getProperty("user.home"),
            "Microsoft Access (*.mdb);;Все файлы (*)",
        )
        val path = dialogResult.result
        if (path.isNullOrEmpty()) return
        openFile(File(path))
    }

    fun openFile(file: File) {
        when (val result = controller.openDatabase(file)) {
            is OpenResult.Success -> {
                if (result.readOnly) {
                    QMessageBox.warning(
                        this,
                        "Только чтение",
                        "База открыта только для чтения: файл занят другой программой.",
                    )
                }
                rebuildRecentMenu()
                updateFromState()
            }
            is OpenResult.Failure -> {
                QMessageBox.critical(this, "Ошибка открытия", result.message)
                updateFromState()
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onRefresh(checked: Boolean = false) {
        controller.refresh()
        updateFromState()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onCloseDatabase(checked: Boolean = false) {
        controller.closeDatabase()
        // Close all tabs
        while (centralTabs.count() > 0) centralTabs.removeTab(0)
        updateFromState()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onAbout(checked: Boolean = false) {
        QMessageBox.about(
            this,
            "О программе Kalita",
            "Kalita — программа учёта доходов и расходов.\n\n" +
                "Совместима с X-Cash 2.1 (Data.mdb).\n" +
                "Лицензия: GPL-3.0-only",
        )
    }

    private fun rebuildRecentMenu() {
        recentMenu.clear()
        val recent = settings.recentDatabases
        if (recent.isEmpty()) {
            recentMenu.setEnabled(false)
            return
        }
        recentMenu.setEnabled(true)
        for (path in recent) {
            val action = recentMenu.addAction(File(path).name)
            action.setToolTip(path)
            val filePath = path
            action.triggered.connect(QMetaObject.Slot0 { openFile(File(filePath)) })
        }
    }

    override fun closeEvent(event: QCloseEvent) {
        saveWindowState()
        controller.closeDatabase()
        super.closeEvent(event)
    }

    private fun saveWindowState() {
        val qs = QSettings()
        qs.setValue("mainWindow/geometry", saveGeometry())
        qs.setValue("mainWindow/state", saveState())
    }

    private fun restoreWindowState() {
        val qs = QSettings()
        val geom = qs.value("mainWindow/geometry")
        if (geom is QByteArray && !geom.isEmpty) restoreGeometry(geom)
        val winState = qs.value("mainWindow/state")
        if (winState is QByteArray && !winState.isEmpty) restoreState(winState)
    }
}
