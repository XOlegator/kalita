// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api

import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

/** The result of trying to load one [Plugin] service entry (dev_plan.md §9.11). */
sealed interface PluginLoadOutcome {
    data class Loaded(val plugin: Plugin) : PluginLoadOutcome

    /** Found and instantiated, but its [PluginDescriptor.requiredApiVersion] rejects this host — shown to the user, not silently dropped (§9.11). */
    data class Incompatible(val descriptor: PluginDescriptor, val reason: String) : PluginLoadOutcome
}

private fun classify(plugin: Plugin, hostApiVersion: PluginVersion): PluginLoadOutcome =
    if (isCompatible(plugin.descriptor.requiredApiVersion, hostApiVersion)) {
        PluginLoadOutcome.Loaded(plugin)
    } else {
        PluginLoadOutcome.Incompatible(
            plugin.descriptor,
            "'${plugin.descriptor.id}' requires plugin-api ${plugin.descriptor.requiredApiVersion}.x, this host implements $hostApiVersion",
        )
    }

/**
 * Discovers [Plugin] implementations registered the standard `java.util.ServiceLoader` way
 * (a `META-INF/services/kalita.plugin.api.Plugin` file naming the implementation class) on
 * [classLoader] — built-in plugins the host itself registers this way — and checks each one's
 * declared [PluginDescriptor.requiredApiVersion] against [hostApiVersion] before it is handed
 * back as usable.
 *
 * Not used by [loadExternalPlugins]: `ServiceLoader`'s resource lookup walks the whole parent
 * chain of a classloader, so calling this directly on a jar's own [URLClassLoader] would also
 * re-report every built-in plugin visible through its parent, once per jar.
 */
fun discoverPlugins(classLoader: ClassLoader, hostApiVersion: PluginVersion = PLUGIN_API_VERSION): List<PluginLoadOutcome> =
    ServiceLoader.load(Plugin::class.java, classLoader).map { classify(it, hostApiVersion) }

/**
 * Loads every `*.jar` directly under [directory] — the computer's `plugins/` folder
 * (dev_plan.md §9.11: "внешние jar из `plugins/`, загрузка через `ServiceLoader` в отдельном
 * classloader'е"). Each jar gets its own [URLClassLoader], delegating to [parent] (the host's
 * own classloader, so a plugin sees `plugin-api`/`storage-api`/`core` but not the host's own
 * app classes) — one jar's classes never collide with another's, or with a same-named class a
 * different jar happens to ship.
 *
 * Only providers whose implementing class [URLClassLoader] itself actually defined are kept —
 * a provider merely *visible* through [parent] (a built-in plugin registered there, or one
 * already loaded from an earlier jar) is **not** re-reported for this jar, since
 * `ServiceLoader`'s resource lookup would otherwise surface it again through every single jar's
 * classloader.
 *
 * An external plugin runs with the full rights of the host process (the JVM has no sandbox);
 * that warning belongs at the install step in the UI, not here (§9.11) — this function only
 * loads what is already in [directory].
 */
fun loadExternalPlugins(directory: File, parent: ClassLoader, hostApiVersion: PluginVersion = PLUGIN_API_VERSION): List<PluginLoadOutcome> {
    if (!directory.isDirectory) return emptyList()
    val jars = directory.listFiles { file -> file.isFile && file.extension.equals("jar", ignoreCase = true) }
        .orEmpty()
        .sortedBy { it.name }
    return jars.flatMap { jar ->
        val classLoader = URLClassLoader(arrayOf(jar.toURI().toURL()), parent)
        ServiceLoader.load(Plugin::class.java, classLoader)
            .filter { it.javaClass.classLoader === classLoader }
            .map { classify(it, hostApiVersion) }
    }
}
