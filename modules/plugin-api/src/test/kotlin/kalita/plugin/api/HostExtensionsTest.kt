// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File

private class HookOnlyPlugin : Plugin, LifecycleHook {
    override val descriptor = PluginDescriptor("hook-only", "Hook", PluginVersion(0, 1, 0), PLUGIN_API_VERSION)
}

private class ActionPlugin : Plugin, ActionProvider {
    override val descriptor = PluginDescriptor("actions", "Actions", PluginVersion(0, 1, 0), PLUGIN_API_VERSION)
    override val actions = listOf(PluginAction("ping", "Ping", requiresDatabase = false))
    var seenDatabase: DatabaseContext? = null

    override suspend fun perform(actionId: String, host: PluginHost): ActionOutcome {
        seenDatabase = host.database()
        return if (actionId == "ping") ActionOutcome.Done("pong") else ActionOutcome.Failed("unknown $actionId")
    }
}

class HostExtensionsTest {
    @Test
    fun `a lifecycle hook that overrides nothing is a no-op`() = runTest {
        val db = DatabaseContext("Data.mdb", File("/x/Data.mdb"), File("/x/Data.kalita-data"))
        HookOnlyPlugin().beforeClose(db, FakePluginHost()).shouldBeInstanceOf<ActionOutcome.Done>()
        db.pluginDirectory("backup") shouldBe File("/x/Data.kalita-data/plugins/backup")
    }

    @Test
    fun `an action provider is reached through the host and sees the current database`() = runTest {
        val plugin = ActionPlugin()
        val host = FakePluginHost()
        plugin.perform("ping", host) shouldBe ActionOutcome.Done("pong")
        plugin.seenDatabase shouldBe null
        plugin.perform("other", host).shouldBeInstanceOf<ActionOutcome.Failed>()
    }

    @Test
    fun `plugins requiring the previous minor api still load against this host`() {
        isCompatible(PluginVersion(1, 0, 0), PLUGIN_API_VERSION) shouldBe true
    }
}
