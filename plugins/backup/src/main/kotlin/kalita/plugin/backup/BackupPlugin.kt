// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.backup

import kalita.plugin.api.ActionOutcome
import kalita.plugin.api.ActionProvider
import kalita.plugin.api.DatabaseContext
import kalita.plugin.api.LifecycleHook
import kalita.plugin.api.PLUGIN_API_VERSION
import kalita.plugin.api.Plugin
import kalita.plugin.api.PluginAction
import kalita.plugin.api.PluginDescriptor
import kalita.plugin.api.PluginHost
import kalita.plugin.api.PluginLogLevel
import kalita.plugin.api.PluginVersion
import kalita.plugin.api.SettingsField
import kalita.plugin.api.SettingsSchema
import kalita.plugin.api.asBoolean
import kalita.plugin.api.asDoubleOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Snapshot copies of the whole database file, on demand («Создать резервную копию сейчас») and,
 * when enabled, every time the database or the application closes. Distinct from the
 * before-every-write backups `storage-local` keeps under `<name>.kalita-data/backups/` (NFR-4):
 * those guard a single commit and rotate quickly; these are the user's own restore points,
 * placed where the user wants them (by default next to the database) and kept [KEEP] deep.
 *
 * Copies are named `<name>-yyyyMMdd-HHmmss.mdb`; pruning touches only files of that exact shape
 * for the current database's name, never anything else in a folder the user chose.
 */
class BackupPlugin : Plugin, ActionProvider, LifecycleHook {
    override val descriptor = PluginDescriptor(
        id = ID,
        displayName = "Резервные копии базы",
        version = PluginVersion(1, 0, 0),
        requiredApiVersion = PluginVersion(1, 1, 0),
        settings = SettingsSchema(
            listOf(
                SettingsField.Toggle(ON_CLOSE, "Создавать копию перед закрытием программы", default = false),
                SettingsField.Path(DIRECTORY, "Каталог для копий", placeholder = "рядом с базой"),
                SettingsField.Number(KEEP, "Хранить последних копий", default = 5.0),
            ),
        ),
    )

    @Volatile
    private var settings: Map<String, Any?> = descriptor.settings.withDefaults(emptyMap())

    override fun initialize(host: PluginHost, settings: Map<String, Any?>) {
        this.settings = settings
    }

    override val actions = listOf(PluginAction(ACTION_BACKUP_NOW, "Создать резервную копию сейчас"))

    override suspend fun perform(actionId: String, host: PluginHost): ActionOutcome {
        if (actionId != ACTION_BACKUP_NOW) return ActionOutcome.Failed("Неизвестное действие: $actionId")
        val db = host.database() ?: return ActionOutcome.Failed("Нет открытой базы")
        return backup(db, host)
    }

    override suspend fun beforeClose(database: DatabaseContext, host: PluginHost): ActionOutcome {
        if (!settings[ON_CLOSE].asBoolean()) return ActionOutcome.Done()
        return backup(database, host)
    }

    private fun backup(db: DatabaseContext, host: PluginHost): ActionOutcome {
        val directory = settings[DIRECTORY]?.toString()?.takeIf { it.isNotBlank() }?.let(::File) ?: db.file.absoluteFile.parentFile
        val keep = settings[KEEP].asDoubleOrNull()?.toInt()?.coerceAtLeast(1) ?: DEFAULT_KEEP
        return try {
            val copy = copyNow(db.file, directory, LocalDateTime.now(host.clock))
            val removed = prune(directory, db.file.nameWithoutExtension, keep)
            host.logger.log(PluginLogLevel.Info, "backup written: $copy, pruned ${removed.size}")
            ActionOutcome.Done("Копия сохранена: ${copy.path}")
        } catch (e: Exception) {
            host.logger.log(PluginLogLevel.Error, "backup failed: $e")
            ActionOutcome.Failed("Не удалось создать копию в «${directory.path}»: ${e.message ?: e}")
        }
    }

    companion object {
        const val ID = "backup"
        const val ON_CLOSE = "backupOnClose"
        const val DIRECTORY = "directory"
        const val KEEP = "keep"
        const val ACTION_BACKUP_NOW = "backup-now"
        const val DEFAULT_KEEP = 5

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** `<name>-yyyyMMdd-HHmmss[-N].mdb` — the `-N` keeps two copies within one second apart. */
        private fun pattern(name: String) = Regex("^" + Regex.escape(name) + """-(\d{8}-\d{6})(?:-(\d+))?\.mdb$""")

        fun copyNow(database: File, directory: File, now: LocalDateTime): File {
            directory.mkdirs()
            val stamp = now.format(STAMP)
            var target = File(directory, "${database.nameWithoutExtension}-$stamp.mdb")
            var n = 1
            while (target.exists()) target = File(directory, "${database.nameWithoutExtension}-$stamp-${n++}.mdb")
            Files.copy(database.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            return target
        }

        /** Oldest copies beyond [keep] are deleted; returns what was removed. */
        fun prune(directory: File, databaseName: String, keep: Int): List<File> {
            val re = pattern(databaseName)
            val copies = directory.listFiles { f -> f.isFile && re.matches(f.name) }.orEmpty()
                .sortedWith(compareBy({ re.find(it.name)!!.groupValues[1] }, { re.find(it.name)!!.groupValues[2].toIntOrNull() ?: 0 }))
            val victims = copies.dropLast(keep)
            victims.forEach { it.delete() }
            return victims
        }
    }
}
