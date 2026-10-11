// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

// Stage 0.4 finding (dev_plan.md §8.1/§8.3): every current Jackcess release — both the spannm fork
// and upstream — logs through java.lang.System.Logger (JEP 264, added in Java 9). Android's ART
// core library (core-oj.jar) never implements that nested type at all, on any API level (confirmed
// on a real emulator at API 26 and 36 alike) — not an API-level gap core library desugaring could
// close, since desugaring rewrites *call sites*, not java.lang.* classes on the boot classpath
// itself. This rewrites every reference to java.lang.System$Logger/$Level in Jackcess's own
// bytecode to kalita.androidshim.Logger/Level (in app-android's androidTest sources, backed by
// android.util.Log), and the one `System.getLogger(String)` call site to
// kalita.androidshim.SystemLoggerShim.getLogger — leaving every other class/method name alone.
// Standard "jar relocation" technique (ASM's ClassRemapper/SimpleRemapper), scoped to two types.
@CacheableTask
abstract class PatchJackcessForAndroidTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun patch() {
        val remapper = SimpleRemapper(
            mapOf(
                "java/lang/System\$Logger" to "kalita/androidshim/Logger",
                "java/lang/System\$Logger\$Level" to "kalita/androidshim/Level",
            ),
        )

        val output = outputJar.get().asFile
        output.parentFile.mkdirs()
        JarFile(inputJar.get().asFile).use { jar ->
            JarOutputStream(output.outputStream()).use { out ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val bytes = jar.getInputStream(entry).use { it.readBytes() }
                    out.putNextEntry(JarEntry(entry.name))
                    out.write(if (entry.name.endsWith(".class")) patchClass(bytes, remapper) else bytes)
                    out.closeEntry()
                }
            }
        }
    }

    private fun patchClass(bytes: ByteArray, remapper: SimpleRemapper): ByteArray {
        val writer = ClassWriter(0)
        val remapVisitor = ClassRemapper(writer, remapper)
        val redirectGetLoggerVisitor = object : ClassVisitor(Opcodes.ASM9, remapVisitor) {
            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodVisitor(Opcodes.ASM9, delegate) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String?,
                        methodName: String?,
                        methodDescriptor: String?,
                        isInterface: Boolean,
                    ) {
                        val isSystemGetLogger = opcode == Opcodes.INVOKESTATIC &&
                            owner == "java/lang/System" &&
                            methodName == "getLogger" &&
                            methodDescriptor == "(Ljava/lang/String;)Ljava/lang/System\$Logger;"
                        super.visitMethodInsn(
                            opcode,
                            if (isSystemGetLogger) "kalita/androidshim/SystemLoggerShim" else owner,
                            methodName,
                            methodDescriptor,
                            isInterface,
                        )
                    }
                }
            }
        }
        ClassReader(bytes).accept(redirectGetLoggerVisitor, 0)
        return writer.toByteArray()
    }
}
