// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kalita.storage.api.SyncStatus
import org.junit.jupiter.api.Test

class SyncStatusTextTest {
    @Test
    fun `databases that never sync show no status line`() {
        syncStatusText(null) shouldBe null
        syncStatusText(SyncStatus.Local) shouldBe null
        syncStatusText(SyncStatus.ReadOnly("busy")) shouldBe null
    }

    @Test
    fun `nextcloud states are told in words, with the queue size declined`() {
        syncStatusText(SyncStatus.Synced) shouldBe "Всё отправлено"
        syncStatusText(SyncStatus.Pending(1)) shouldBe "Ждёт отправки: 1 изменение"
        syncStatusText(SyncStatus.Pending(3)) shouldBe "Ждёт отправки: 3 изменения"
        syncStatusText(SyncStatus.Pending(5)) shouldBe "Ждёт отправки: 5 изменений"
        syncStatusText(SyncStatus.Pending(11)) shouldBe "Ждёт отправки: 11 изменений"
        syncStatusText(SyncStatus.Pending(21)) shouldBe "Ждёт отправки: 21 изменение"
        syncStatusText(SyncStatus.Offline("x"))!! shouldContain "Нет связи"
        syncStatusText(SyncStatus.Conflicted(emptyList()))!! shouldContain "Конфликт"
    }
}
