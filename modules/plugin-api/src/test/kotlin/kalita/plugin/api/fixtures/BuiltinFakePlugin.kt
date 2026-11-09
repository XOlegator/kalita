// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api.fixtures

import kalita.plugin.api.Plugin
import kalita.plugin.api.PluginDescriptor
import kalita.plugin.api.PluginVersion

/** Registered for [kalita.plugin.api.PluginLoaderTest] via `src/test/resources/META-INF/services`, simulating a built-in plugin. */
class BuiltinFakePlugin : Plugin {
    override val descriptor = PluginDescriptor(
        id = "builtin-fake",
        displayName = "Builtin Fake",
        version = PluginVersion(1, 0, 0),
        requiredApiVersion = PluginVersion(1, 0, 0),
    )
}

/** Same registration, but asks for a plugin-api major version this host does not implement. */
class IncompatibleFakePlugin : Plugin {
    override val descriptor = PluginDescriptor(
        id = "incompatible-fake",
        displayName = "Incompatible Fake",
        version = PluginVersion(1, 0, 0),
        requiredApiVersion = PluginVersion(99, 0, 0),
    )
}
