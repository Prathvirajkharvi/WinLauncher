package com.winlauncher.app.domain.process

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.InputStreamReader

sealed class ProcessState {
    object Running : ProcessState()
    data class Exited(val exitCode: Int, val crashed: Boolean) : ProcessState()
}

/**
 * Supervises one launched process: captures stdout/stderr into a ring buffer,
 * reports PID and running state, and detects the exit code once the process
 * dies (a non-zero/unexpected exit is flagged as `crashed`).
 *
 * This class knows nothing about Wine/Box64 -- it supervises whatever
 * ProcessBuilder command it was handed, which today is DummyRuntimeEngine's
 * harmless dummy script and nothing else. RealRuntimeEngine's real Box64/Wine
 * launch uses NativeProcessMonitor instead (see ManagedProcess's doc for why).
 */
class ProcessMonitor(
    private val process: Process,
    override val logBuffer: LogRingBuffer,
) : ManagedProcess {
    private val _state = MutableStateFlow<ProcessState>(ProcessState.Running)
    override val state: StateFlow<ProcessState> = _state

    // Process.pid() isn't resolvable through this Android-compatible abstraction
    // in the current toolchain/compileSdk setup. PID is informational only for
    // the MVP, so it's stubbed out here rather than blocking compilation --
    // revisit with a proper API-level-gated implementation if PID is ever needed.
    override val pid: Long get() = -1L

    init {
        readStreamAsync(process.inputStream, "stdout")
        readStreamAsync(process.errorStream, "stderr")
        waitForExitAsync()
    }

    private fun readStreamAsync(stream: java.io.InputStream, label: String) {
        Thread({
            try {
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        logBuffer.add("[$label] $line")
                    }
                }
            } catch (e: Exception) {
                logBuffer.add("[$label] stream closed: ${e.message}")
            }
        }, "proc-$label-reader").apply { isDaemon = true }.start()
    }

    private fun waitForExitAsync() {
        Thread({
            val exitCode = try {
                process.waitFor()
            } catch (e: InterruptedException) {
                -1
            }
            val crashed = exitCode != 0
            logBuffer.add("[monitor] process exited with code $exitCode")
            _state.value = ProcessState.Exited(exitCode, crashed)
        }, "proc-waiter").apply { isDaemon = true }.start()
    }

    override fun stop() {
        if (process.isAlive) {
            process.destroy()
        }
    }

    override fun isRunning(): Boolean = process.isAlive
}
