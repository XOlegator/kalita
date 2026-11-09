// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kalita.plugin.api.fixtures.ExternalFakePlugin
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

class PluginLoaderTest {
    @Test
    fun `discovers a built-in plugin registered on this classloader`() {
        val outcomes = discoverPlugins(javaClass.classLoader)

        val loaded = outcomes.filterIsInstance<PluginLoadOutcome.Loaded>().map { it.plugin.descriptor.id }
        loaded shouldBe listOf("builtin-fake")
    }

    @Test
    fun `an incompatible declared api version is reported, not silently dropped`() {
        val outcomes = discoverPlugins(javaClass.classLoader)

        val incompatible = outcomes.filterIsInstance<PluginLoadOutcome.Incompatible>()
        incompatible shouldHaveSize 1
        incompatible[0].descriptor.id shouldBe "incompatible-fake"
    }

    @Test
    fun `a missing plugins directory yields no plugins, not an error`() {
        loadExternalPlugins(File("/does/not/exist"), javaClass.classLoader) shouldBe emptyList()
    }

    @Test
    fun `loads a plugin that only exists inside the jar, through its own classloader`() {
        val pluginsDir = Files.createTempDirectory("kalita-plugins").toFile()
        writeJar(pluginsDir.resolve("fake.jar"), serviceClassName = "kalita.plugin.api.fixtures.ExternalFakePlugin", includeClassBytesOf = ExternalFakePlugin::class.java)
        // A real external plugin's implementation class is never already on the host's own
        // classpath — only here it is, by construction, since the fixture is compiled right
        // alongside this test module. Hiding just that one name from the parent is what makes
        // the child URLClassLoader's own copy (the one actually packaged in the jar) the one
        // used, instead of silently resolving to the copy already loaded for this test.
        val parent = Blind(javaClass.classLoader, "kalita.plugin.api.fixtures.ExternalFakePlugin")

        val outcomes = loadExternalPlugins(pluginsDir, parent)

        outcomes shouldHaveSize 1
        val loaded = outcomes[0]
        loaded.shouldBeInstanceOf<PluginLoadOutcome.Loaded>()
        loaded.plugin.descriptor.id shouldBe "external-fake"
        loaded.plugin.javaClass.classLoader.shouldBeInstanceOf<URLClassLoader>()
    }

    @Test
    fun `a provider only visible through the parent is not re-reported for an external jar`() {
        val pluginsDir = Files.createTempDirectory("kalita-plugins").toFile()
        // This jar's own META-INF/services names a class the *parent* already has registered
        // (see src/test/resources) — standing in for a jar that happens to list a plugin the
        // host (or an earlier jar) already provides. ServiceLoader's resource lookup walks the
        // whole parent chain, so without loadExternalPlugins's own classloader-identity filter
        // this would resurface the built-in fixture here, once per jar on disk.
        writeJar(pluginsDir.resolve("stale.jar"), serviceClassName = "kalita.plugin.api.fixtures.BuiltinFakePlugin", includeClassBytesOf = null)

        val outcomes = loadExternalPlugins(pluginsDir, javaClass.classLoader)

        outcomes shouldBe emptyList()
    }

    private fun writeJar(jarFile: File, serviceClassName: String, includeClassBytesOf: Class<*>?) {
        JarOutputStream(jarFile.outputStream()).use { jar ->
            if (includeClassBytesOf != null) {
                val resourcePath = includeClassBytesOf.name.replace('.', '/') + ".class"
                val classBytes = includeClassBytesOf.classLoader.getResourceAsStream(resourcePath)
                    ?.use { it.readBytes() }
                    ?: error("Compiled class not found on the test classpath: $resourcePath")
                jar.putNextEntry(ZipEntry(resourcePath))
                jar.write(classBytes)
                jar.closeEntry()
            }

            jar.putNextEntry(ZipEntry("META-INF/services/kalita.plugin.api.Plugin"))
            jar.write("$serviceClassName\n".toByteArray())
            jar.closeEntry()
        }
    }

    /** Delegates to [delegate] for everything except [hiddenClassName], which it pretends not to know — see the one test that needs this. */
    private class Blind(private val delegate: ClassLoader, private val hiddenClassName: String) : ClassLoader(delegate) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name == hiddenClassName) throw ClassNotFoundException(name)
            return delegate.loadClass(name)
        }
    }
}
