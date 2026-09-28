package com.winlauncher.app.domain.process

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * [ManagedProcess] for a process launched via [NativeProcessLauncher]'s
 * memfd+execveat loader. Deliberately mirrors [ProcessMonitor]'s log-pumping
 * and exit-watching logic line for line -- reusing a proven pattern rather
 * than inventing a new one for the one thing that's actually different here
 * (a native pid + raw fds instead of a `java.lang.Process`).
 */
class NativeProcessMonitor(
    pid: Int,
    stdout: ParcelFileDescriptor,
    stderr: ParcelFileDescriptor,
    private val launcher: NativeProcessLauncher,
) : ManagedProcess {

    override val logBuffer: LogRingBuffer = LogRingBuffer()
    private val _state = MutableStateFlow<ProcessState>(ProcessState.Running)
    override val state: StateFlow<ProcessState> = _state
    override val pid: Long = pid.toLong()

    private val nativePid = pid
    @Volatile private var alive = true

    init {
        readStreamAsync(ParcelFileDescriptor.AutoCloseInputStream(stdout), "stdout")
        readStreamAsync(ParcelFileDescriptor.AutoCloseInputStream(stderr), "stderr")
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
        }, "native-proc-$label-reader").apply { isDaemon = true }.start()
    }

    private fun waitForExitAsync() {
        Thread({
            val exitCode = launcher.waitFor(nativePid)
            alive = false
            val crashed = exitCode != 0
            logBuffer.add("[monitor] process exited with code $exitCode")
            _state.value = ProcessState.Exited(exitCode, crashed)
        }, "native-proc-waiter").apply { isDaemon = true }.start()
    }

    override fun stop() {
        if (alive) {
            launcher.kill(nativePid)
        }
    }

    override fun isRunning(): Boolean = alive
}
