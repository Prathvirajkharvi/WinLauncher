package com.winlauncher.app.domain.process

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the memfd+execveat mechanism itself works end to end on a real
 * device/emulator, independent of whether a real Box64/Wine build is
 * available to test with (it never is in CI, and wasn't in the sandbox this
 * was implemented in -- see the implementation report's VERIFIED/NOT VERIFIED
 * breakdown). `/system/bin/id` is used as the "known-good ARM64 ELF" stand-in:
 * it's already on every device, already executable from its real system
 * location, and its output is trivial to assert on -- this test only cares
 * that NativeProcessLauncher can stage an arbitrary ELF and exec it, not that
 * `id` specifically does anything interesting.
 *
 * This is the one piece of this change that genuinely cannot be verified
 * without a device -- there is no JVM-only equivalent (`System.loadLibrary`
 * needs a real native lib on a real ABI, and fork()/exec() need a real Linux
 * process). Run via Android Studio's "Run 'NativeProcessLauncherInstrumentedTest'"
 * or `./gradlew connectedDebugAndroidTest` against a connected device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class NativeProcessLauncherInstrumentedTest {

    @Test
    fun execsARealOnDeviceBinaryAndCapturesItsOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launcher = NativeProcessLauncher()

        val idBinary = "/system/bin/id"
        assertTrue("expected $idBinary to exist on this device", File(idBinary).exists())

        val monitor = launcher.launch(
            elfPath = idBinary,
            argv = listOf(idBinary),
            environment = emptyMap(),
            workingDirectory = context.filesDir,
        )

        val deadline = System.currentTimeMillis() + 5_000
        while (monitor.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        assertTrue("process should have exited within 5s", !monitor.isRunning())
        val state = monitor.state.value
        assertTrue("expected an Exited state, got $state", state is ProcessState.Exited)
        assertEquals(0, (state as ProcessState.Exited).exitCode)

        val logs = monitor.logBuffer.snapshot()
        assertTrue(
            "expected id's real stdout (containing 'uid=') to have been captured, got: $logs",
            logs.any { it.contains("uid=") },
        )
    }

    @Test
    fun reportsAPreciseErrorForANonExecutableOrMissingFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launcher = NativeProcessLauncher()
        val missing = File(context.filesDir, "definitely-does-not-exist-${System.nanoTime()}")

        try {
            launcher.launch(
                elfPath = missing.absolutePath,
                argv = listOf(missing.absolutePath),
                environment = emptyMap(),
                workingDirectory = context.filesDir,
            )
            org.junit.Assert.fail("expected NativeExecException for a missing file")
        } catch (e: NativeExecException) {
            // Precise and errno-carrying, per the implementation report -- not
            // a generic/opaque "Permission denied" with no further detail.
            assertTrue(e.message.orEmpty().contains("errno="))
        }
    }
}
