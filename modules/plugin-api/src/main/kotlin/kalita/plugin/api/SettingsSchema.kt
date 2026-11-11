// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api

/**
 * One field of a plugin's settings form (dev_plan.md §9.11: "Обе оболочки строят форму настроек
 * по схеме — плагину не нужен свой UI-код"). [id] is the key under which the value is stored and
 * passed back to the plugin; [label] is shown to the user.
 *
 * [Secret] values are never round-tripped back to the plugin as plain text in [PluginDescriptor]
 * — a loaded plugin reads them at runtime through [PluginHost.secretStore] instead, keyed by
 * [id]. The shell only needs the field to draw the right widget and to know where to store what
 * the user types.
 */
sealed interface SettingsField {
    val id: String
    val label: String
    val required: Boolean

    data class Text(
        override val id: String,
        override val label: String,
        override val required: Boolean = false,
        val default: String? = null,
    ) : SettingsField

    data class Secret(
        override val id: String,
        override val label: String,
        override val required: Boolean = false,
    ) : SettingsField

    data class Number(
        override val id: String,
        override val label: String,
        override val required: Boolean = false,
        val default: Double? = null,
    ) : SettingsField

    data class Toggle(
        override val id: String,
        override val label: String,
        override val required: Boolean = false,
        val default: Boolean = false,
    ) : SettingsField

    data class Choice(
        override val id: String,
        override val label: String,
        val options: List<String>,
        override val required: Boolean = false,
        val default: String? = null,
    ) : SettingsField

    /**
     * A file-system path the shell lets the user pick with its own file/folder dialog instead of
     * typing. Stored as plain text like [Text]; [placeholder] is what an empty field means (e.g.
     * «рядом с базой») so a plugin can keep "unset" as a meaningful default without inventing a
     * sentinel value.
     */
    data class Path(
        override val id: String,
        override val label: String,
        override val required: Boolean = false,
        val kind: Kind = Kind.Directory,
        val placeholder: String? = null,
    ) : SettingsField {
        enum class Kind { Directory, File }
    }
}

/** One violation found by [SettingsSchema.validate], identifying the field by [fieldId]. */
data class SettingsError(val fieldId: String, val message: String)

/**
 * A plugin's whole settings form. [fields] must have unique [SettingsField.id]s — the shell uses
 * the id both as the form-state key and as the key under which the value is persisted per
 * connection/installation.
 */
data class SettingsSchema(val fields: List<SettingsField>) {
    init {
        val duplicates = fields.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate settings field id(s): $duplicates" }
    }

    /**
     * Checks [values] (as the shell's generic settings form would hand them back — secrets
     * included as plain text here, before they are moved into [PluginHost.secretStore]) against
     * this schema: every [SettingsField.required] field present and non-blank, every
     * [SettingsField.Choice] value one of its [SettingsField.Choice.options].
     */
    fun validate(values: Map<String, Any?>): List<SettingsError> =
        fields.mapNotNull { field ->
            val value = values[field.id]
            when {
                field.required && (value == null || value == "") -> SettingsError(field.id, "Required")
                field is SettingsField.Choice && value != null && value !in field.options ->
                    SettingsError(field.id, "'$value' is not one of ${field.options}")
                field is SettingsField.Number && value != null && value != "" && value.asDoubleOrNull() == null ->
                    SettingsError(field.id, "'$value' is not a number")
                else -> null
            }
        }

    /**
     * The values a plugin sees when the user has saved nothing yet (or cleared a field): every
     * field's declared default, [values] laid over it. Shells and plugins both read settings
     * through this so "default" means one thing.
     */
    fun withDefaults(values: Map<String, Any?>): Map<String, Any?> = buildMap {
        for (field in fields) {
            val default: Any? = when (field) {
                is SettingsField.Text -> field.default
                is SettingsField.Number -> field.default
                is SettingsField.Toggle -> field.default
                is SettingsField.Choice -> field.default
                is SettingsField.Secret, is SettingsField.Path -> null
            }
            if (default != null) put(field.id, default)
        }
        for ((key, value) in values) if (value != null && value != "") put(key, value)
    }

    companion object {
        val EMPTY = SettingsSchema(emptyList())
    }
}

/** A settings value as either shell hands it back: already a number, or the text the user typed. */
fun Any?.asDoubleOrNull(): Double? = when (this) {
    is Number -> toDouble()
    is String -> trim().replace(',', '.').toDoubleOrNull()
    else -> null
}

fun Any?.asBoolean(): Boolean = when (this) {
    is Boolean -> this
    is String -> equals("true", ignoreCase = true)
    else -> false
}
