// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.androidshim

import android.util.Log
import java.util.function.Supplier

// Stand-ins for java.lang.System.Logger/Logger.Level, which Android's ART core library never
// implements (see PatchJackcessForAndroidTask, build-logic). Method/constant names mirror the
// real API exactly — the patch task only rewrites type references in Jackcess's bytecode, it
// does not rename methods, so the signatures here must match what Jackcess actually calls.
enum class Level { ALL, TRACE, DEBUG, INFO, WARNING, ERROR, OFF }

interface Logger {
    fun isLoggable(level: Level): Boolean

    fun log(level: Level, msg: String?)

    fun log(level: Level, msg: String?, thrown: Throwable?)

    fun log(level: Level, msg: String?, vararg params: Any?)

    fun log(level: Level, msgSupplier: Supplier<String>?)

    fun log(level: Level, msgSupplier: Supplier<String>?, thrown: Throwable?)
}

object SystemLoggerShim {
    @JvmStatic
    fun getLogger(name: String): Logger = AndroidLogger(name.takeLast(23))
}

private fun Level.toAndroidPriority(): Int = when (this) {
    Level.ALL, Level.TRACE -> Log.VERBOSE
    Level.DEBUG -> Log.DEBUG
    Level.INFO -> Log.INFO
    Level.WARNING -> Log.WARN
    Level.ERROR -> Log.ERROR
    Level.OFF -> Int.MAX_VALUE
}

private class AndroidLogger(private val tag: String) : Logger {
    override fun isLoggable(level: Level): Boolean =
        level != Level.OFF && Log.isLoggable(tag, level.toAndroidPriority())

    override fun log(level: Level, msg: String?) {
        if (isLoggable(level)) Log.println(level.toAndroidPriority(), tag, msg ?: "null")
    }

    override fun log(level: Level, msg: String?, thrown: Throwable?) {
        if (isLoggable(level)) Log.println(level.toAndroidPriority(), tag, "$msg\n${Log.getStackTraceString(thrown)}")
    }

    override fun log(level: Level, msg: String?, vararg params: Any?) {
        if (isLoggable(level)) Log.println(level.toAndroidPriority(), tag, String.format(msg ?: "null", *params))
    }

    override fun log(level: Level, msgSupplier: Supplier<String>?) {
        if (isLoggable(level)) Log.println(level.toAndroidPriority(), tag, msgSupplier?.get() ?: "null")
    }

    override fun log(level: Level, msgSupplier: Supplier<String>?, thrown: Throwable?) {
        if (isLoggable(level)) {
            Log.println(level.toAndroidPriority(), tag, "${msgSupplier?.get()}\n${Log.getStackTraceString(thrown)}")
        }
    }
}
