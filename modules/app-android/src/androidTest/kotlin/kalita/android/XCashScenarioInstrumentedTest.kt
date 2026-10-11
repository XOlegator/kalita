// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import kalita.storage.mdb.countOperations
import kalita.storage.mdb.runXCashWriteScenario
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Stage 0.4 PoC (dev_plan.md §15 stage 0, 0.4; §8.2): the only thing that genuinely needs a real
// device/emulator rather than the JVM unit test in storage-mdb — proving Jackcess's file I/O,
// multi-page LVAL (MEMO) handling, and index maintenance all work unmodified under Android's
// ART runtime and sandboxed filesystem. The CI `android` job pulls both files this test writes
// and hands them to the `oracle-jet` job, which opens the result with the real Microsoft engine
// and checks it against the sidecar counts.
@RunWith(AndroidJUnit4::class)
class XCashScenarioInstrumentedTest {
    @Test
    fun runsScenario82OnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outputDir = context.getExternalFilesDir(null) ?: context.filesDir
        val mdbFile = File(outputDir, "scenario82.mdb")
        mdbFile.delete()

        val result = runXCashWriteScenario(mdbFile)

        result.operations shouldBe 600
        result.groupBudget shouldBe 5
        result.accounts shouldBe 1
        result.courses shouldBe 370
        countOperations(mdbFile) shouldBe (result.operations - result.softDeletedOperations)

        val sidecarFile = File(outputDir, "scenario82.expected.txt")
        result.writeSidecar(sidecarFile)

        // AGP's connectedAndroidTest uninstalls the test APK right after the run, which wipes the
        // app's own external files dir before CI gets a chance to `adb pull` from it. /data/local/
        // tmp isn't tied to any app's lifecycle (the classic AOSP/CTS pattern for getting test
        // artifacts off a device) — copying there via the shell survives that cleanup.
        copyToSharedTmp(mdbFile)
        copyToSharedTmp(sidecarFile)
    }

    private fun copyToSharedTmp(file: File) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd = automation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/${file.name}")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }
}
