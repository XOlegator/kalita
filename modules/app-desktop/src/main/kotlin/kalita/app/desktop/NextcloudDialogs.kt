// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QTimer
import io.qt.core.QUrl
import io.qt.gui.QDesktopServices
import io.qt.widgets.QDialog
import io.qt.widgets.QDialogButtonBox
import io.qt.widgets.QInputDialog
import io.qt.widgets.QLabel
import io.qt.widgets.QLineEdit
import io.qt.widgets.QMessageBox
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.sync.LoginFlowSession
import kalita.sync.LoginFlowV2
import kalita.sync.NextcloudCredentials

/**
 * FR-S1/FR-S2's Nextcloud Login Flow v2 (dev_plan.md §10.8): asks for the server address, opens
 * the one-time login page in the system browser, and polls (every 2 s, via [QTimer] — the same
 * poll-on-a-timer idiom [MainWindow]'s own `remoteChangePollTimer` already uses) until the user
 * finishes logging in there. Returns `null` on any cancel/failure — [server] is only a starting
 * suggestion (the last one used), never required to be non-empty.
 */
fun promptNextcloudLogin(parent: QWidget, lastServer: String?): NextcloudCredentials? {
    val serverInput = QInputDialog.getText(parent, "Nextcloud", "Адрес сервера (например, https://cloud.example.com):", QLineEdit.EchoMode.Normal, lastServer ?: "")
    val server = serverInput?.trim()
    if (server.isNullOrEmpty()) return null

    val session = try {
        LoginFlowV2().start(server)
    } catch (e: Exception) {
        QMessageBox.critical(parent, "Nextcloud", "Не удалось начать вход: ${e.message}")
        return null
    }
    QDesktopServices.openUrl(QUrl(session.loginUrl))
    return WaitForLoginDialog(parent, session).let { dialog ->
        if (dialog.exec() == QDialog.DialogCode.Accepted.value()) dialog.credentials else null
    }
}

/** Asks for the WebDAV path of `Data.mdb` on the server (e.g. `/Documents/private/Data.mdb`) — a
 * plain text field for now; browsing the server's own folder listing (`NextcloudClient.list`,
 * already built) is a nicer follow-up, not required for this slice to be real and usable. */
fun promptRemotePath(parent: QWidget, initial: String = "/Data.mdb"): String? {
    val result = QInputDialog.getText(parent, "Nextcloud", "Путь к файлу базы на сервере:", QLineEdit.EchoMode.Normal, initial)
    return result?.trim()?.ifEmpty { null }
}

/** Modal dialog shown while [promptNextcloudLogin] waits on the browser side of Login Flow v2. */
private class WaitForLoginDialog(parent: QWidget, private val session: LoginFlowSession) : QDialog(parent) {
    private val flow = LoginFlowV2()
    private val timer = QTimer(this)
    var credentials: NextcloudCredentials? = null
        private set

    init {
        setWindowTitle("Вход в Nextcloud")
        val layout = QVBoxLayout(this)
        layout.addWidget(QLabel("Завершите вход в открывшейся странице браузера…"))
        val buttons = QDialogButtonBox(QDialogButtonBox.StandardButtons(QDialogButtonBox.StandardButton.Cancel))
        buttons.rejected.connect(this::reject)
        layout.addWidget(buttons)

        timer.setInterval(2000)
        timer.timeout.connect(this::poll)
        timer.start()
    }

    private fun poll() {
        val result = try {
            flow.poll(session)
        } catch (e: Exception) {
            timer.stop()
            QMessageBox.critical(this, "Nextcloud", "Ошибка при проверке входа: ${e.message}")
            reject()
            return
        }
        if (result != null) {
            timer.stop()
            credentials = result
            accept()
        }
    }
}
