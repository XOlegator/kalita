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
import io.qt.widgets.QListWidget
import io.qt.widgets.QListWidgetItem
import io.qt.widgets.QMessageBox
import io.qt.widgets.QPushButton
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import kalita.sync.LoginFlowSession
import kalita.sync.LoginFlowV2
import kalita.sync.NextcloudClient
import kalita.sync.NextcloudCredentials
import kalita.sync.NextcloudEntry

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

/**
 * Asks for the WebDAV path of `Data.mdb` on the server (e.g. `/Documents/private/Data.mdb`) by
 * browsing [client]'s own folder listing (`NextcloudClient.list`) rather than a bare text field —
 * [NextcloudBrowserDialog]'s path field stays editable throughout, so a server whose listing fails
 * (an odd WebDAV quirk, a path the account can't `PROPFIND`) still lets the user type the path by
 * hand instead of being stuck.
 */
fun promptRemotePath(parent: QWidget, client: NextcloudClient, initial: String = "/Data.mdb"): String? {
    val dialog = NextcloudBrowserDialog(parent, client, initial)
    return if (dialog.exec() == QDialog.DialogCode.Accepted.value()) dialog.selectedPath else null
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

/**
 * Lets the user navigate [client]'s folders (`PROPFIND Depth: 1`, via [NextcloudClient.list]) and
 * pick `Data.mdb`'s path, instead of typing the whole thing blind. [initialPath]'s folder becomes
 * the starting listing; its last segment pre-fills the path field. The path field is always
 * editable and is what [selectedPath] reads on OK — browsing is a shortcut for filling it in, not
 * the only way: FR-S1's "Переместить базу в Nextcloud" needs a path that does not exist on the
 * server yet, which no listing would ever show as a pickable entry.
 */
private class NextcloudBrowserDialog(parent: QWidget?, private val client: NextcloudClient, initialPath: String) : QDialog(parent) {
    private val folderLabel = QLabel()
    private val list = QListWidget()
    private val pathField = QLineEdit()
    private var currentFolder: String
    private var entries: List<NextcloudEntry> = emptyList()
    var selectedPath: String? = null
        private set

    init {
        setWindowTitle("Nextcloud")
        resize(420, 360)
        val trimmed = initialPath.trim('/')
        currentFolder = trimmed.substringBeforeLast('/', "")
        pathField.setText(trimmed)

        val layout = QVBoxLayout(this)
        layout.addWidget(folderLabel)
        list.itemClicked.connect(this::onItemClicked)
        list.itemDoubleClicked.connect(this::onItemDoubleClicked)
        layout.addWidget(list, 1)
        val upButton = QPushButton("⬆ Вверх")
        upButton.clicked.connect(this::onUp)
        layout.addWidget(upButton)
        layout.addWidget(QLabel("Путь к файлу базы на сервере:"))
        layout.addWidget(pathField)
        val buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButtons(QDialogButtonBox.StandardButton.Ok, QDialogButtonBox.StandardButton.Cancel),
        )
        buttons.accepted.connect(this::onOk)
        buttons.rejected.connect(this::reject)
        layout.addWidget(buttons)

        refreshListing()
    }

    private fun refreshListing() {
        folderLabel.setText("/$currentFolder")
        list.clear()
        entries = try {
            client.list(currentFolder).sortedWith(compareBy({ !it.isFolder }, { it.path.lowercase() }))
        } catch (e: Exception) {
            list.addItem("Не удалось получить список: ${e.message}")
            emptyList()
        }
        for (entry in entries) {
            val name = entry.path.substringAfterLast('/')
            list.addItem((if (entry.isFolder) "📁 " else "📄 ") + name) // 📁/📄
        }
    }

    private fun onItemClicked(item: QListWidgetItem) {
        val entry = entries.getOrNull(list.row(item)) ?: return
        if (!entry.isFolder) pathField.setText(entry.path)
    }

    private fun onItemDoubleClicked(item: QListWidgetItem) {
        val entry = entries.getOrNull(list.row(item)) ?: return
        if (entry.isFolder) {
            currentFolder = entry.path
            refreshListing()
        } else {
            pathField.setText(entry.path)
            onOk()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onUp(checked: Boolean = false) {
        if (currentFolder.isEmpty()) return
        currentFolder = currentFolder.substringBeforeLast('/', "")
        refreshListing()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onOk(checked: Boolean = false) {
        val path = pathField.text().trim().trim('/')
        if (path.isEmpty()) return
        selectedPath = "/$path"
        accept()
    }
}
