// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.kotest.matchers.shouldBe
import io.qt.core.QMetaObject
import io.qt.core.QTimer
import io.qt.widgets.QApplication
import io.qt.widgets.QDialog
import io.qt.widgets.QWidget
import kalita.platform.NoopSecretStore
import kalita.storage.mdb.createDemoFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ReconcileConfigDialogTest {
    @TempDir
    lateinit var tempDir: Path
    @Test
    fun `defaults, percentage conversion, disabling and cancellation`() {
        QApplication.initialize(arrayOf("test", "-platform", "offscreen"))
        try {
            val dialog = ReconcileConfigDialog(null)
            dialog.config().rateTolerance shouldBe 0.01
            dialog.percent.setValue(2.5)
            dialog.config().rateTolerance shouldBe 0.025
            dialog.percent.setValue(0.0)
            dialog.config().rateTolerance shouldBe 0.0
            dialog.enabled.setChecked(false)
            dialog.percent.isEnabled shouldBe false
            dialog.config().rateTolerance shouldBe -1.0
            dialog.reject()
            dialog.result() shouldBe QDialog.DialogCode.Rejected.value()
            val next = ReconcileConfigDialog(null)
            next.config().rateTolerance shouldBe 0.01

            val controller = AppController(AppSettings(tempDir.resolve("config")), NoopSecretStore, externalPluginsDirectory = null)
            controller.openDatabase(createDemoFixture(tempDir.resolve("Data.mdb").toFile(), operationCount = 3))
            val transactions = controller.readStatement("import-apibank", File(javaClass.getResource("/statement-text.xls")!!.toURI())).getOrThrow()
            controller.enqueueStatement(transactions)
            val pending = controller.pendingStatements()
            val before = (controller.state.value as AppState.Ready).dataset
            val timer = QTimer()
            timer.setSingleShot(true)
            timer.timeout.connect(QMetaObject.Slot0 { (QApplication.activeModalWidget() as QDialog).reject() })
            timer.start(0)
            runPendingStatements(QWidget(), controller)
            controller.pendingStatements() shouldBe pending
            (controller.state.value as AppState.Ready).dataset shouldBe before
            controller.bankMappings()!!.read().accounts.isEmpty() shouldBe true
            controller.closeDatabase()
        } finally {
            QApplication.shutdown()
        }
    }
}