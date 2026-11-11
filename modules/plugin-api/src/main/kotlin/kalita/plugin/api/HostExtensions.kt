// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api

import java.io.File

/**
 * The database the host currently has open, as far as a plugin may know it (dev_plan.md §9.11:
 * plugins never write to the database directly — this is read-side context only). [file] is a
 * readable copy of the `.mdb` on this device: the file itself for a local database, the
 * device-local cache for a Nextcloud one (§10.2) — a backup or an export plugin copies it the
 * same way either way. [dataDirectory] is the per-database local folder of §9.4 where host-side
 * stores (links, mappings, history) live; a plugin gets its own subfolder via [pluginDirectory]
 * rather than writing next to those files.
 */
data class DatabaseContext(
    val displayName: String,
    val file: File,
    val dataDirectory: File,
) {
    fun pluginDirectory(pluginId: String): File = File(dataDirectory, "plugins/$pluginId")
}

/**
 * One entry a plugin wants in the host's «Плагины» menu (dev_plan.md §9.11: output is
 * declarative — the plugin names the action, each shell draws it its own way). [requiresDatabase]
 * lets the shell disable it while no database is open instead of every plugin re-checking.
 */
data class PluginAction(
    val id: String,
    val label: String,
    val requiresDatabase: Boolean = true,
)

/**
 * Extension point: a plugin that contributes user-triggered actions (a menu item on the computer,
 * a list entry on Android). [perform] runs on the caller's thread; anything slow belongs in the
 * plugin's own coroutine, reporting back through the returned [ActionOutcome].
 */
interface ActionProvider {
    val actions: List<PluginAction>

    suspend fun perform(actionId: String, host: PluginHost): ActionOutcome
}

/** What the shell shows the user after an [ActionProvider.perform]. */
sealed interface ActionOutcome {
    data class Done(val message: String? = null) : ActionOutcome

    data class Failed(val message: String) : ActionOutcome
}

/**
 * Extension point: a plugin that wants to act on host lifecycle moments. [beforeClose] runs on the
 * thread that is closing the database or the whole application, **before** the session is closed
 * — the file is still consistent and still locked by this process (the backup plugin copies it
 * here). It must not block for long and must not throw for an expected failure: return
 * [ActionOutcome.Failed] and the shell tells the user; a thrown exception is logged and the close
 * continues regardless, a plugin can never hold the application hostage.
 */
interface LifecycleHook {
    suspend fun beforeClose(database: DatabaseContext, host: PluginHost): ActionOutcome = ActionOutcome.Done()
}
