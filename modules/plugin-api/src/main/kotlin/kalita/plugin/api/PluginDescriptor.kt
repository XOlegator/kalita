// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api

/**
 * How a plugin describes itself to the host (dev_plan.md §9.11). The host builds the plugin
 * list and the settings form entirely from this — a plugin carries no UI code of its own.
 *
 * [requiredApiVersion] is checked with [isCompatible] against [PLUGIN_API_VERSION] before the
 * plugin is given a chance to run; a mismatch keeps it out of the enabled list with the reason
 * visible to the user, per §9.11, rather than failing silently or crashing the host.
 */
data class PluginDescriptor(
    val id: String,
    val displayName: String,
    val version: PluginVersion,
    val requiredApiVersion: PluginVersion,
    val settings: SettingsSchema = SettingsSchema.EMPTY,
)

/**
 * A loaded plugin instance. The host discovers which extension points (dev_plan.md §9.11 table)
 * a plugin implements with plain `is` checks against this interface — there is no separate
 * capability-flag list to keep in sync with what the class actually implements.
 *
 * One plugin may implement more than one extension point (e.g. `receipts` is expected to combine
 * [DocumentSource], [ReceiptRecognizer] and [Categorizer]).
 */
interface Plugin {
    val descriptor: PluginDescriptor

    /**
     * Called once after the version check passes and the user's settings for this plugin (per
     * [PluginDescriptor.settings]) are available, before any extension-point method is invoked.
     */
    fun initialize(host: PluginHost, settings: Map<String, Any?>) {}
}
