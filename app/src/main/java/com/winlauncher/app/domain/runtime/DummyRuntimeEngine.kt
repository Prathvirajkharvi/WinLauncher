package com.winlauncher.app.domain.runtime

import com.winlauncher.app.data.db.entity.GameProfile
import com.winlauncher.app.data.db.entity.RuntimeProfile
import com.winlauncher.app.domain.error.AppError
import com.winlauncher.app.domain.process.ProcessLauncher
import com.winlauncher.app.domain.process.ProcessMonitor
import com.winlauncher.app.domain.process.ProcessState
import java.io.File

/**
 * THIS IS NOT WINE. It launches `/system/bin/sh` running a short, harmless
 * script so the rest of the app (process lifecycle, log capture, crash
 * detection, UI status) can be built and tested against something real.
 *
 * Replacing this with the actual Wine + Box64 + DXVK/VKD3D chain means writing
 * a new class that implements RuntimeEngine and building `command`/`environment`
 * from the real runtime's requirements -- everything else (GameManager,
 * RuntimeManager UI, ProcessMonitor, logs screen) stays unchanged.
 */
class DummyRuntimeEngine(private val filesDir: File) : RuntimeEngine {

    private val launcher = ProcessLauncher()
    private var monitor: ProcessMonitor? = null
    private var status: RuntimeStatus = RuntimeStatus.Idle

    override suspend fun initialize(runtimeProfile: RuntimeProfile): ValidationResult {
        val prefixDir = File(filesDir, runtimeProfile.winePrefixRelativePath)
        if (!prefixDir.exists() && !prefixDir.mkdirs()) {
            return ValidationResult(false, "Could not create prefix directory: ${prefixDir.path}")
        }
        return ValidationResult(true, "Prefix directory ready at ${prefixDir.path} (dummy engine: no real Wine prefix)")
    }

    override suspend fun validate(game: GameProfile, runtimeProfile: RuntimeProfile): ValidationResult {
        if (game.executableUri.isBlank()) {
            return ValidationResult(false, "Game has no executable URI configured")
        }
        return ValidationResult(true, "Dummy engine can 'launch' any configured game (no real EXE execution yet)")
    }

    override suspend fun launch(game: GameProfile, runtimeProfile: RuntimeProfile): RuntimeStatus {
        status = RuntimeStatus.Initializing

        val script = "echo '[dummy-runtime] would exec: ${game.executableUri}'; " +
            "echo '[dummy-runtime] wine prefix: ${runtimeProfile.winePrefixRelativePath}'; " +
            "for i in 1 2 3; do echo \"[dummy-runtime] tick \$i\"; sleep 1; done; " +
            "echo '[dummy-runtime] exiting cleanly'"

        val env = parseEnv(game.environmentVariables) + parseEnv(runtimeProfile.environmentVariables)

        return try {
            val newMonitor = launcher.launch(
                command = listOf("/system/bin/sh", "-c", script),
                workingDirectory = filesDir,
                environment = env,
            )
            monitor = newMonitor
            status = RuntimeStatus.Running
            status
        } catch (e: Exception) {
            status = RuntimeStatus.Failed(AppError.ProcessLaunchFailed(e.message ?: "unknown error"))
            status
        }
    }

    override suspend fun stop() {
        monitor?.stop()
    }

    override fun getStatus(): RuntimeStatus {
        val currentMonitor = monitor ?: return status
        return when (val state = currentMonitor.state.value) {
            is ProcessState.Running -> RuntimeStatus.Running
            is ProcessState.Exited -> RuntimeStatus.Stopped(state.exitCode)
        }
    }

    fun currentLogs(): List<String> = monitor?.logBuffer?.snapshot() ?: emptyList()

    private fun parseEnv(raw: String): Map<String, String> {
        return raw.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()
    }
}
