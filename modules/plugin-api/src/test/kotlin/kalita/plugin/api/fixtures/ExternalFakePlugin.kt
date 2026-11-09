// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api.fixtures

import kalita.plugin.api.Plugin
import kalita.plugin.api.PluginDescriptor
import kalita.plugin.api.PluginVersion

/**
 * Deliberately *not* registered via `src/test/resources/META-INF/services` — only its compiled
 * `.class` file is reused. [kalita.plugin.api.PluginLoaderTest] packages it into a throwaway jar
 * with its own service-registration file to stand in for a real external plugin under
 * `plugins/`, so [kalita.plugin.api.loadExternalPlugins] is exercised against an actual jar and
 * an actual `URLClassLoader`, not just the in-process `ServiceLoader` call it wraps.
 */
class ExternalFakePlugin : Plugin {
    override val descriptor = PluginDescriptor(
        id = "external-fake",
        displayName = "External Fake",
        version = PluginVersion(1, 0, 0),
        requiredApiVersion = PluginVersion(1, 0, 0),
    )
}
