// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.app.desktop

import io.qt.core.QByteArray
import io.qt.core.QSettings
import io.qt.widgets.QHeaderView

/**
 * Column order/width/visibility persistence (dev_plan.md FR-42), shared by every grid-like
 * browser so each remembers its own layout "for each journal and dictionary separately".
 */
internal fun QHeaderView.persistTo(settings: QSettings, key: String) {
    settings.setValue(key, saveState())
}

internal fun QHeaderView.restoreFrom(settings: QSettings, key: String): Boolean {
    val value = settings.value(key)
    return if (value is QByteArray && !value.isEmpty) restoreState(value) else false
}
