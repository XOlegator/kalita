// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.host

import kalita.plugin.api.ActionOutcome
import kalita.plugin.api.ActionProvider
import kalita.plugin.api.DatabaseContext
import kalita.plugin.api.LifecycleHook
import kalita.plugin.api.Plugin
import kalita.plugin.api.PluginAction
import kalita.plugin.api.PluginDescriptor
import kalita.plugin.api.PluginLoadOutcome
import kalita.plugin.api.PluginLogLevel
import kalita.plugin.api.SettingsError
import kalita.plugin.api.SettingsField
import kalita.plugin.api.asBoolean
import kalita.plugin.api.asDoubleOrNull
import kalita.plugin.api.discoverPlugins
import kalita.plugin.api.loadExternalPlugins
import java.io.File

/** One row of the «Плагины» screen (FR-P1): what was found, whether it runs, and why not if it doesn't. */
data class PluginEntry(
    val descriptor: PluginDescriptor,
    /** `null` for an incompatible plugin — there is no instance the host will ever call. */
    val plugin: Plugin?,
    val enabled: Boolean,
    /** [PluginLoadOutcome.Incompatible]'s reason, or the message of an exception `initialize` threw. */
    val problem: String?,
    /** External jar plugins run with the host's full rights (§9.11) — the UI marks them. */
    val external: Boolean,
) {
    val active: Boolean get() = plugin != null && enabled && problem == null
}

/**
 * Everything a shell needs to run plugins (dev_plan.md §9.11, FR-P1): the list for the settings
 * screen, enable/disable, the settings form round-trip, and dispatch to the extension points
 * plugins contribute to the shell itself ([ActionProvider], [LifecycleHook]). Extension points
 * consumed elsewhere (`StatementImporter`, `Categorizer`, …) are reached through [extensions].
 *
 * `initialize` runs once per plugin on load and again after its settings change or it is
 * re-enabled; an exception there marks the entry with [PluginEntry.problem] and keeps the plugin
 * out of [extensions] — one broken plugin never takes the shell down with it.
 */
class PluginRegistry(
    outcomes: List<Pair<PluginLoadOutcome, Boolean>>,
    private val settings: PluginSettingsStore,
    private val services: PluginHostServices,
) {
    private val states = LinkedHashMap<String, State>()

    private class State(
        val descriptor: PluginDescriptor,
        val plugin: Plugin?,
        val external: Boolean,
        var problem: String?,
    )

    init {
        for ((outcome, external) in outcomes) {
            when (outcome) {
                is PluginLoadOutcome.Loaded -> {
                    val id = outcome.plugin.descriptor.id
                    if (id in states) {
                        services.forPlugin(id).logger.log(PluginLogLevel.Warn, "duplicate plugin id ignored (${outcome.plugin.javaClass.name})")
                        continue
                    }
                    states[id] = State(outcome.plugin.descriptor, outcome.plugin, external, null)
                    if (settings.isEnabled(id)) initialize(states.getValue(id))
                }
                is PluginLoadOutcome.Incompatible ->
                    states.putIfAbsent(outcome.descriptor.id, State(outcome.descriptor, null, external, outcome.reason))
            }
        }
    }

    val entries: List<PluginEntry>
        get() = states.values.map { PluginEntry(it.descriptor, it.plugin, settings.isEnabled(it.descriptor.id), it.problem, it.external) }

    fun entry(pluginId: String): PluginEntry? = entries.firstOrNull { it.descriptor.id == pluginId }

    /** Active plugins implementing [T] — the shape every consumer of an extension point uses (§9.11: a plain `is` check). */
    inline fun <reified T : Any> extensions(): List<Pair<PluginDescriptor, T>> = activePlugins().filter { it is T }.map { it.descriptor to it as T }

    fun activePlugins(): List<Plugin> = entries.filter { it.active }.mapNotNull { it.plugin }

    fun setEnabled(pluginId: String, enabled: Boolean) {
        val state = states[pluginId] ?: return
        settings.setEnabled(pluginId, enabled)
        if (enabled && state.plugin != null) initialize(state)
    }

    /** The values the settings form should show: saved values over schema defaults, secrets blank. */
    fun settingsValues(pluginId: String): Map<String, Any?> {
        val state = states[pluginId] ?: return emptyMap()
        return state.descriptor.settings.withDefaults(settings.values(pluginId))
    }

    /** Whether a secret field already has a value in the OS store — the form shows «сохранено» instead of the value. */
    fun hasSecret(pluginId: String, fieldId: String): Boolean = services.secretStoreFor(pluginId).get(fieldId) != null

    /**
     * Validates and saves the form's values. Secret fields go to the OS store (an empty secret
     * keeps whatever is already stored — the form never echoes it back, so "empty" means
     * "unchanged", not "clear"); everything else is persisted and the plugin re-initialized.
     */
    fun updateSettings(pluginId: String, values: Map<String, Any?>): List<SettingsError> {
        val state = states[pluginId] ?: return listOf(SettingsError("", "unknown plugin $pluginId"))
        val schema = state.descriptor.settings
        val secretFields = schema.fields.filterIsInstance<SettingsField.Secret>().map { it.id }.toSet()
        val toValidate = values.toMutableMap()
        val secrets = services.secretStoreFor(pluginId)
        for (id in secretFields) if ((toValidate[id] == null || toValidate[id] == "") && secrets.get(id) != null) toValidate[id] = "(stored)"
        val errors = schema.validate(toValidate)
        if (errors.isNotEmpty()) return errors
        for (id in secretFields) {
            val v = values[id] as? String
            if (!v.isNullOrEmpty()) secrets.put(id, v)
        }
        settings.saveValues(pluginId, normalize(schema.fields, values.filterKeys { it !in secretFields }))
        if (settings.isEnabled(pluginId) && state.plugin != null) initialize(state)
        return emptyList()
    }

    /** Typed by schema regardless of which shell's widget produced the value: a number is a `Double`, a toggle a `Boolean`, the rest text. */
    private fun normalize(fields: List<SettingsField>, values: Map<String, Any?>): Map<String, Any?> {
        val byId = fields.associateBy { it.id }
        return values.mapValues { (id, v) ->
            when (byId[id]) {
                is SettingsField.Number -> v.asDoubleOrNull()
                is SettingsField.Toggle -> v.asBoolean()
                else -> v?.toString()
            }
        }
    }

    private fun initialize(state: State) {
        val plugin = state.plugin ?: return
        val id = state.descriptor.id
        state.problem = try {
            plugin.initialize(services.forPlugin(id), state.descriptor.settings.withDefaults(settings.values(id)))
            null
        } catch (e: Exception) {
            services.forPlugin(id).logger.log(PluginLogLevel.Error, "initialize failed: $e")
            "initialize failed: ${e.message ?: e.toString()}"
        }
    }

    // --- Extension points the shell itself consumes ---

    fun actions(): List<Pair<PluginDescriptor, PluginAction>> =
        extensions<ActionProvider>().flatMap { (d, p) -> p.actions.map { d to it } }

    suspend fun perform(pluginId: String, actionId: String): ActionOutcome {
        val plugin = states[pluginId]?.plugin as? ActionProvider ?: return ActionOutcome.Failed("plugin $pluginId has no actions")
        return try {
            plugin.perform(actionId, services.forPlugin(pluginId))
        } catch (e: Exception) {
            services.forPlugin(pluginId).logger.log(PluginLogLevel.Error, "action $actionId failed: $e")
            ActionOutcome.Failed(e.message ?: e.toString())
        }
    }

    /** Runs every active [LifecycleHook]; returns the failures for the shell to show. A throwing hook is logged and skipped. */
    suspend fun beforeClose(database: DatabaseContext): List<Pair<PluginDescriptor, ActionOutcome.Failed>> =
        extensions<LifecycleHook>().mapNotNull { (d, hook) ->
            val outcome = try {
                hook.beforeClose(database, services.forPlugin(d.id))
            } catch (e: Exception) {
                services.forPlugin(d.id).logger.log(PluginLogLevel.Error, "beforeClose failed: $e")
                ActionOutcome.Failed(e.message ?: e.toString())
            }
            (outcome as? ActionOutcome.Failed)?.let { d to it }
        }

    companion object {
        /** Built-in plugins (on [classLoader]) plus external jars from [externalDirectory] (`plugins/`, §9.11). */
        fun load(classLoader: ClassLoader, externalDirectory: File?, settings: PluginSettingsStore, services: PluginHostServices): PluginRegistry {
            val builtIn = discoverPlugins(classLoader).map { it to false }
            val external = externalDirectory?.let { loadExternalPlugins(it, classLoader) }.orEmpty().map { it to true }
            return PluginRegistry(builtIn + external, settings, services)
        }
    }
}
