package com.winlauncher.app.domain.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.view.Choreographer
import java.io.RandomAccessFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PerformanceSnapshot(
    val ramUsedMb: Long,
    val ramTotalMb: Long,
    val cpuUsagePercent: Float?,   // null if a sample pair hasn't been collected yet
    val fps: Float?,
    val frameTimeMs: Float?,
    // Android has no public, reliably-supported per-app/per-process GPU utilization
    // API. This stays null rather than fabricating a number -- surface it as
    // "unavailable" in the UI, don't hide the field.
    val gpuUsagePercent: Float? = null,
)

class PerformanceManager(private val context: Context) {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private var lastCpuSample: CpuSample? = null

    fun sampleMemory(): Pair<Long, Long> {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalMb = memInfo.totalMem / (1024 * 1024)

        val procMemInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(procMemInfo)
        val usedMb = (procMemInfo.totalPss.toLong()) / 1024 // totalPss is in KB

        return usedMb to totalMb
    }

    /**
     * CPU usage requires two samples of /proc/[pid]/stat separated in time
     * (Android provides no instantaneous per-app CPU% API). Returns null on the
     * first call for a given process, since there's nothing to diff against yet.
     */
    fun sampleCpuPercent(pid: Int = android.os.Process.myPid()): Float? {
        val current = readCpuTicks(pid) ?: return null
        val previous = lastCpuSample
        lastCpuSample = current

        if (previous == null || current.uptimeMs <= previous.uptimeMs) return null

        val ticksDelta = (current.utimeTicks + current.stimeTicks) -
            (previous.utimeTicks + previous.stimeTicks)
        val elapsedMs = current.uptimeMs - previous.uptimeMs
        val clockTicksPerSec = 100.0 // sysconf(_SC_CLK_TCK) is 100 on virtually all Android kernels
        val cpuTimeMs = (ticksDelta / clockTicksPerSec) * 1000.0

        return ((cpuTimeMs / elapsedMs) * 100.0).toFloat().coerceIn(0f, 100f * Runtime.getRuntime().availableProcessors())
    }

    private data class CpuSample(val utimeTicks: Long, val stimeTicks: Long, val uptimeMs: Long)

    private fun readCpuTicks(pid: Int): CpuSample? {
        return try {
            RandomAccessFile("/proc/$pid/stat", "r").use { file ->
                val line = file.readLine() ?: return null
                // Field 2 (comm) may contain spaces inside parentheses; split after ')'.
                val afterComm = line.substringAfter(") ")
                val fields = afterComm.split(" ")
                // Fields here are 0-indexed starting at field 3 (state) of the original stat line.
                // utime is field 14, stime is field 15 (1-indexed) => index 11 and 12 here.
                val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
                val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
                CpuSample(utime, stime, android.os.SystemClock.elapsedRealtime())
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** Rolling-window FPS/frame-time counter driven by the Choreographer vsync signal. */
class FpsCounter {
    private val _fps = MutableStateFlow<Float?>(null)
    val fps: StateFlow<Float?> = _fps

    private val _frameTimeMs = MutableStateFlow<Float?>(null)
    val frameTimeMs: StateFlow<Float?> = _frameTimeMs

    private var lastFrameNanos = 0L
    private var running = false

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastFrameNanos != 0L) {
                val deltaNanos = frameTimeNanos - lastFrameNanos
                val deltaMs = deltaNanos / 1_000_000f
                _frameTimeMs.value = deltaMs
                _fps.value = if (deltaMs > 0f) 1000f / deltaMs else null
            }
            lastFrameNanos = frameTimeNanos
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(callback)
    }
}
