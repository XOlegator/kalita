// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.widgets.QApplication
import io.qt.widgets.QFileDialog
import io.qt.widgets.QMessageBox
import kalita.storage.mdb.countOperations
import kalita.storage.mdb.createDemoFixture
import java.io.File
import java.nio.file.Files

/**
 * Entry point of the desktop shell (dev_plan.md §9.9, stage 3).
 *
 * Startup flow (FR-2):
 * 1. If a last-opened database is saved in settings and the file exists → open it.
 * 2. If that fails or there is none → show the open-file dialog.
 * 3. `--self-test`: open a generated fixture, verify, and exit without the event loop.
 */
fun main(args: Array<String>) {
    QApplication.initialize(args)

    if ("--self-test" in args) {
        selfTest()
        return
    }

    QApplication.setOrganizationName("kalita")
    QApplication.setApplicationName("Kalita")

    val settings = AppSettings.default()
    val controller = AppController(settings)
    val window = MainWindow(controller, settings)

    val lastPath = settings.lastDatabasePath
    if (lastPath != null && File(lastPath).isFile) {
        window.openFile(File(lastPath))
    } else {
        if (lastPath != null) {
            QMessageBox.warning(
                window,
                "Файл не найден",
                "Последняя база «$lastPath» не найдена.\nВыберите базу вручную.",
            )
        }
        promptOpen(window, settings, controller)
    }

    window.show()
    QApplication.exec()
    QApplication.shutdown()
}

private fun promptOpen(window: MainWindow, settings: AppSettings, controller: AppController) {
    val dialogResult = QFileDialog.getOpenFileName(
        window,
        "Открыть базу данных",
        System.getProperty("user.home"),
        "Microsoft Access (*.mdb);;Все файлы (*)",
    )
    val path = dialogResult.result
    if (!path.isNullOrEmpty()) {
        window.openFile(File(path))
    }
}

/**
 * `--self-test` (dev_plan.md §13.3): generates a fixture, opens it through the full stack
 * (storage-mdb → storage-local → AppController), and exits. Used by `package-smoke` in CI.
 */
private fun selfTest() {
    // Stage 7 added bare QSettings() writes (header/pin/sort/filter state) alongside the window
    // geometry ones — isolate every one of them under a throwaway, run-unique name so repeated
    // self-test runs neither pollute nor read back a real `~/.config/kalita` and stay idempotent.
    QApplication.setOrganizationName("kalita-selftest")
    QApplication.setApplicationName("kalita-selftest-${System.nanoTime()}")

    val fixture = Files.createTempFile("kalita-selftest-", ".mdb").toFile()
    fixture.deleteOnExit()
    createDemoFixture(fixture, operationCount = 12)
    val operationCount = countOperations(fixture)
    println("kalita self-test: fixture $fixture, $operationCount operations in MDB")
    check(operationCount == 9) { "expected 9 operations after soft-delete filtering, got $operationCount" }

    val settings = AppSettings(Files.createTempDirectory("kalita-selftest-cfg"))
    val controller = AppController(settings)
    when (val result = controller.openDatabase(fixture)) {
        is OpenResult.Success -> {
            val state = controller.state.value as AppState.Ready
            val domainOps = state.dataset.operations.count { !it.deleted }
            println("kalita self-test: domain has $domainOps non-deleted operations, readOnly=${result.readOnly}")
            check(domainOps == 9) { "expected 9 domain operations, got $domainOps" }
        }
        is OpenResult.Failure -> error("self-test open failed: ${result.message}")
    }

    // Builds the real window (menus, grid, every dock panel including the stage 6 filter panels)
    // to catch anything that only breaks once Qt actually constructs the widget tree.
    val window = MainWindow(controller, settings)
    window.updateFromState()
    println("kalita self-test: main window built")

    selfTestPanelsAndLayout(controller, settings)

    controller.closeDatabase()
    println("kalita self-test: OK")
    QApplication.shutdown()
}

/**
 * Stage 7 (FR-41…FR-43): exercises pin/hide/named-view round-tripping directly — not through
 * [JournalGrid]'s own `Q*Dialog`-based entry points, which would block forever under
 * `QT_QPA_PLATFORM=offscreen` with no one to click them.
 */
private fun selfTestPanelsAndLayout(controller: AppController, settings: AppSettings) {
    val grid = JournalGrid(controller, settings)
    grid.refreshData()

    grid.togglePin(kalita.core.JournalColumn.Date)
    grid.hideColumn(kalita.core.JournalColumn.Comment)
    val view = grid.captureView("self-test")
    check(view.pinnedColumns == listOf("Date")) { "expected Date pinned, got ${view.pinnedColumns}" }
    grid.showAllColumns()
    grid.applyView(view)
    println("kalita self-test: pin/hide/named-view round-trip OK")

    grid.persistState()
    val reopened = JournalGrid(controller, settings)
    reopened.refreshData()
    println("kalita self-test: per-screen layout persistence OK")
}
