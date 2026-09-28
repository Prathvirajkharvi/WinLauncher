package com.winlauncher.app.domain.process

import kotlinx.coroutines.flow.StateFlow

/**
 * Common surface both [ProcessMonitor] (wraps a `java.lang.Process` from
 * [ProcessLauncher]/`ProcessBuilder`, used by DummyRuntimeEngine and anything
 * else launching an already-exec-permitted path like `/system/bin/sh`) and
 * [NativeProcessMonitor] (wraps a pid + raw fds from [NativeProcessLauncher],
 * used by RealRuntimeEngine for the Box64/Wine binary) implement. Letting
 * `RealRuntimeEngine` depend on this instead of `ProcessMonitor` directly is
 * the entire reason swapping its launcher was a small change: nothing about
 * `getStatus()`/`currentLogs()`/`stop()` needed to change shape, only which
 * concrete type is behind them.
 */
interface ManagedProcess {
    val state: StateFlow<ProcessState>
    val logBuffer: LogRingBuffer
    val pid: Long
    fun stop()
    fun isRunning(): Boolean
}
