// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.storage.local

import java.io.File

/**
 * The device-local folder for one `Data.mdb` (dev_plan.md §9.4): backups, history and the
 * agent-api inbox/idempotency log all live here. Shared by every opener of the file — the desktop
 * app ([kalita.app.desktop.AppController]) and `agent-api`/`kalita` alike — so a database opened
 * by either one finds the same lock, backups and history as the other.
 */
fun defaultDataDirectory(dbFile: File): File =
    File(dbFile.parentFile, dbFile.nameWithoutExtension + ".kalita-data")
