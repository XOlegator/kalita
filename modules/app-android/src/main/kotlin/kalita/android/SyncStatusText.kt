// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import kalita.storage.api.SyncStatus

/** What FR-A7's status line says; `null` for databases that never sync (local file, read-only). */
fun syncStatusText(status: SyncStatus?): String? = when (status) {
    SyncStatus.Synced -> "Всё отправлено"
    is SyncStatus.Pending -> "Ждёт отправки: ${status.queued} ${changesWord(status.queued)}"
    is SyncStatus.Offline -> "Нет связи с сервером — изменения сохранены на телефоне"
    is SyncStatus.Conflicted -> "Конфликт с изменениями на другом устройстве (${status.conflicts.size}) — разбор на компьютере"
    SyncStatus.Local, is SyncStatus.ReadOnly, null -> null
}

private fun changesWord(n: Int): String = when {
    n % 100 in 11..14 -> "изменений"
    n % 10 == 1 -> "изменение"
    n % 10 in 2..4 -> "изменения"
    else -> "изменений"
}
