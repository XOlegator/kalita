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
import io.qt.widgets.QFileDialog
import io.qt.widgets.QLabel
import io.qt.widgets.QMainWindow
import io.qt.widgets.QMenu
import io.qt.widgets.QMessageBox
import java.io.File

/**
 * The application's main window (dev_plan.md §9.9, stage 3): menu bar, status bar and a central
 * area that will host the journal grid and dock panels in later stages. Saves and restores its
 * geometry and dock state via [QSettings].
 */
class MainWindow(private val controller: AppController, private val settings: AppSettings) : QMainWindow() {
    private val statusLabel = QLabel()
    private val recentMenu = QMenu("&Недавние")
    private val centralLabel = QLabel("Откройте базу данных через меню «Файл → Открыть…»")

    init {
        setWindowTitle("Kalita")
        resize(960, 640)
        centralLabel.setAlignment(Qt.AlignmentFlag.AlignCenter)
        setCentralWidget(centralLabel)
        setupMenuBar()
        setupStatusBar()
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

        val closeAction = QAction("&Закрыть базу", this)
        closeAction.setShortcut(QKeySequence("Ctrl+W"))
        closeAction.triggered.connect(this::onCloseDatabase)
        fileMenu.addAction(closeAction)

        fileMenu.addSeparator()

        val exitAction = QAction("&Выход", this)
        exitAction.setShortcut(QKeySequence(QKeySequence.StandardKey.Quit))
        exitAction.triggered.connect(QMetaObject.Slot0 { QApplication.quit() })
        fileMenu.addAction(exitAction)

        val helpMenu = menuBar().addMenu("&Справка")
        val aboutAction = QAction("О &программе", this)
        aboutAction.triggered.connect(this::onAbout)
        helpMenu.addAction(aboutAction)
    }

    private fun setupStatusBar() {
        statusBar().addWidget(statusLabel)
        updateStatusBar()
    }

    fun updateFromState() {
        val appState = controller.state.value
        when (appState) {
            is AppState.Ready -> {
                val ro = if (appState.readOnly) " [только чтение]" else ""
                setWindowTitle("Kalita — ${File(appState.filePath).name}$ro")
                val ops = appState.dataset.operations.count { !it.deleted }
                centralLabel.setText("База: ${appState.filePath}\nОпераций: $ops")
            }
            is AppState.NoDatabase -> {
                setWindowTitle("Kalita")
                centralLabel.setText("Откройте базу данных через меню «Файл → Открыть…»")
            }
            is AppState.Error -> {
                setWindowTitle("Kalita")
                centralLabel.setText("Ошибка: ${appState.message}")
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
                    "${File(appState.filePath).name}$ro"
                }
                is AppState.NoDatabase -> "Нет открытой базы"
                is AppState.Error -> "Ошибка"
            },
        )
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
    private fun onCloseDatabase(checked: Boolean = false) {
        controller.closeDatabase()
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
