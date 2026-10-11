package kalita.app.desktop

import io.qt.core.Qt
import io.qt.widgets.QApplication
import io.qt.widgets.QDockWidget
import io.qt.widgets.QLabel
import io.qt.widgets.QMainWindow
import kalita.storage.mdb.countOperations
import kalita.storage.mdb.createOperationsFixture
import java.nio.file.Files

// Stage 0.2 acceptance criterion (dev_plan.md §15): a QtJambi window that calls storage-mdb,
// opens a fixture, and shows the operation count. The fixture is generated on the fly — no
// vendor Data.mdb ships in this repo (§13) — so this runs the same way on any machine.
fun main(args: Array<String>) {
    QApplication.initialize(args)

    val fixture = Files.createTempFile("kalita-fixture-", ".mdb").toFile()
    fixture.deleteOnExit()
    createOperationsFixture(fixture, operationCount = 12)
    val operationCount = countOperations(fixture)
    println("kalita app-desktop: opened fixture $fixture, $operationCount operations")

    val window = QMainWindow()
    window.setWindowTitle("Kalita (prototype)")
    window.setCentralWidget(QLabel("Operations in fixture: $operationCount"))

    val dock = QDockWidget("Info", window)
    dock.setWidget(QLabel("Stage 0.2 scaffold: QtJambi window reading storage-mdb"))
    window.addDockWidget(Qt.DockWidgetArea.TopDockWidgetArea, dock)

    window.resize(640, 400)
    window.show()

    QApplication.exec()
    QApplication.shutdown()
}
