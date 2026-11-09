// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api

/**
 * SemVer triple used both for a plugin's own [PluginDescriptor.version] and for the host's
 * [PLUGIN_API_VERSION] (dev_plan.md §9.11: "Версия API — SemVer; несовместимый плагин не
 * загружается").
 */
data class PluginVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<PluginVersion> {
    override fun compareTo(other: PluginVersion): Int =
        compareValuesBy(this, other, PluginVersion::major, PluginVersion::minor, PluginVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /**
         * Parses a plain `major.minor.patch` string. Plugin manifests carry this as text
         * (e.g. in [PluginDescriptor]), not as the typed value, so a loader needs this at the
         * boundary before any [isCompatibleWith] check.
         */
        fun parse(text: String): PluginVersion {
            val parts = text.trim().split('.')
            require(parts.size == 3) { "Expected major.minor.patch, got '$text'" }
            return PluginVersion(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }
    }
}

/**
 * The plugin-api version this build of the host implements. Bump the major component on any
 * breaking change to a contract in this module (extension point interfaces, [PluginHost],
 * [SettingsSchema], the declarative output types) — minor/patch are for additive changes.
 */
val PLUGIN_API_VERSION = PluginVersion(1, 0, 0)

/**
 * Whether a plugin requiring [required] may load against a host implementing [actual]:
 * same major (breaking changes gate loading entirely), and the host must be at least as new
 * as what the plugin asks for within that major.
 */
fun isCompatible(required: PluginVersion, actual: PluginVersion): Boolean =
    required.major == actual.major && actual >= required
