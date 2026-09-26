package com.winlauncher.app.domain.runtime

import android.content.Context
import android.net.Uri
import com.winlauncher.app.data.db.entity.CpuBackend
import com.winlauncher.app.data.db.entity.GameProfile
import com.winlauncher.app.data.db.entity.RuntimeProfile
import com.winlauncher.app.domain.error.AppError
import com.winlauncher.app.domain.graphics.DirectXTarget
import com.winlauncher.app.domain.graphics.GpuDetector
import com.winlauncher.app.domain.graphics.GraphicsBackendPreference
import com.winlauncher.app.domain.graphics.GraphicsManager
import com.winlauncher.app.domain.graphics.GraphicsResolution
import com.winlauncher.app.domain.process.ProcessLauncher
import com.winlauncher.app.domain.process.ProcessMonitor
import com.winlauncher.app.domain.process.ProcessState
import java.io.File
import java.io.FileOutputStream

/**
 * Real Wine + Box64 integration behind the existing RuntimeEngine interface.
 *
 * WHAT'S REAL HERE: binary/prefix verification, SAF-to-local-file staging
 * (Wine/Box64 are native Linux processes and can't resolve `content://` URIs),
 * Mali-aware DXVK/VKD3D deployment (reusing GpuDetector/GraphicsManager
 * completely unchanged), environment construction, and process supervision via
 * the same ProcessLauncher/ProcessMonitor DummyRuntimeEngine uses -- genuine
 * stdout/stderr/exit-code capture, not simulated.
 *
 * WHAT'S NOT VERIFIED: the exact Wine+Box64 command line is fork-specific (see
 * buildLaunchCommand) and has not been exercised against real binaries in this
 * environment -- there are none here to test with. A "Running" status means
 * the configured OS process was started, not that a Windows game is actually
 * working. See README "Known limitations", including the Android 10+ W^X
 * exec restriction that affects binaries imported at runtime.
 */
class RealRuntimeEngine(
    private val context: Context,
    private val installationManager: RuntimeInstallationManager,
) : RuntimeEngine, LogSource {

    private val launcher = ProcessLauncher()
    private var monitor: ProcessMonitor? = null
    private var status: RuntimeStatus = RuntimeStatus.Idle

    override suspend fun initialize(runtimeProfile: RuntimeProfile): ValidationResult {
        val installStatus = installationManager.status()
        if (!installStatus.readyForLaunch) {
            return ValidationResult(false, AppError.RuntimeBinariesMissing.userMessage)
        }

        val prefixDir = prefixDir(runtimeProfile)
        if (!prefixDir.exists() && !prefixDir.mkdirs()) {
            return ValidationResult(false, "Could not create Wine prefix directory: ${prefixDir.path}")
        }
        // A real Wine prefix needs its drive_c tree bootstrapped by running
        // `wineboot` once before first use -- that bootstrap isn't automated
        // yet (see README "Known limitations").
        return ValidationResult(true, "Prefix ready at ${prefixDir.path}")
    }

    override suspend fun validate(game: GameProfile, runtimeProfile: RuntimeProfile): ValidationResult {
        if (CpuBackend.valueOf(runtimeProfile.cpuBackend) != CpuBackend.BOX64) {
            return ValidationResult(false, "RealRuntimeEngine currently only drives the BOX64 CPU backend")
        }
        val exeUri = try {
            Uri.parse(game.executableUri)
        } catch (e: Exception) {
            return ValidationResult(false, "Executable URI is malformed: ${e.message}")
        }
        return try {
            context.contentResolver.openFileDescriptor(exeUri, "r")?.use { }
                ?: return ValidationResult(false, "Executable is not accessible through SAF (no file descriptor)")
            ValidationResult(true, "Executable is accessible through SAF")
        } catch (e: Exception) {
            ValidationResult(false, "Lost SAF access to the executable -- re-select it in Add Game: ${e.message}")
        }
    }

    override suspend fun launch(game: GameProfile, runtimeProfile: RuntimeProfile): RuntimeStatus {
        status = RuntimeStatus.Initializing
        return try {
            val wineBin = installationManager.binaryFile(RuntimeComponent.WINE)
                ?: return failed(AppError.RuntimeBinariesMissing)
            val box64Bin = installationManager.binaryFile(RuntimeComponent.BOX64)
                ?: return failed(AppError.RuntimeBinariesMissing)

            val prefixDir = prefixDir(runtimeProfile)
            val localExe = stageExecutableLocally(game)

            val resolution = resolveGraphics(game)
            applyGraphicsComponents(resolution, prefixDir)

            val env = buildEnvironment(game, runtimeProfile, prefixDir, resolution)
            val command = buildLaunchCommand(box64Bin, wineBin, localExe, game.launchArguments)

            val newMonitor = launcher.launch(
                command = command,
                workingDirectory = localExe.parentFile,
                environment = env,
            )
            monitor = newMonitor
            status = RuntimeStatus.Running
            status
        } catch (e: SecurityException) {
            // The most likely real-world failure on Android 10+: W^X blocks exec
            // of a binary written to app storage at runtime (see class doc).
            failed(
                AppError.ProcessLaunchFailed(
                    "Exec was blocked by the OS (${e.message}). On Android 10+, binaries " +
                        "imported at runtime generally can't be executed -- they need to ship " +
                        "in the APK's jniLibs at build time. See README.",
                ),
            )
        } catch (e: Exception) {
            failed(AppError.ProcessLaunchFailed(e.message ?: "unknown error"))
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

    override fun currentLogs(): List<String> = monitor?.logBuffer?.snapshot() ?: emptyList()

    private fun prefixDir(runtimeProfile: RuntimeProfile): File =
        File(context.filesDir, runtimeProfile.winePrefixRelativePath)

    /**
     * Wine and Box64 run as native Linux processes and cannot resolve Android's
     * `content://` SAF URIs -- the bytes must be staged onto a real filesystem
     * path first. This copies only the .exe itself; sibling game files under a
     * chosen working directory are not yet copied (see README "Known limitations").
     */
    private fun stageExecutableLocally(game: GameProfile): File {
        val gameDir = File(context.filesDir, "gamefiles/${game.id}").apply { mkdirs() }
        val fileName = game.executableUri.substringAfterLast('/').ifBlank { "game.exe" }
        val localFile = File(gameDir, fileName)

        val uri = Uri.parse(game.executableUri)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(localFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not open executable via SAF: ${game.executableUri}")

        return localFile
    }

    private fun resolveGraphics(game: GameProfile): GraphicsResolution {
        val caps = GpuDetector.detect()
        return GraphicsManager.resolve(
            caps = caps,
            directXTarget = DirectXTarget.valueOf(game.directXTarget),
            preference = GraphicsBackendPreference.valueOf(game.graphicsBackendPreference),
        )
    }

    /** Deploys DXVK or VKD3D dlls into the prefix based on what GraphicsManager actually picked. */
    private fun applyGraphicsComponents(resolution: GraphicsResolution, prefixDir: File) {
        when (resolution) {
            is GraphicsResolution.VulkanDxvk -> installationManager.installIntoPrefix(RuntimeComponent.DXVK, prefixDir)
            is GraphicsResolution.VulkanVkd3d -> installationManager.installIntoPrefix(RuntimeComponent.VKD3D, prefixDir)
            else -> Unit // GLES fallback / unsupported: no DirectX translation DLLs needed
        }
    }

    private fun buildEnvironment(
        game: GameProfile,
        runtimeProfile: RuntimeProfile,
        prefixDir: File,
        resolution: GraphicsResolution,
    ): Map<String, String> {
        val env = mutableMapOf(
            "WINEPREFIX" to prefixDir.absolutePath,
            "WINEARCH" to "win64",
            "BOX64_LOG" to "0",
        )
        when (resolution) {
            is GraphicsResolution.VulkanDxvk -> env["DXVK_HUD"] = "0"
            is GraphicsResolution.VulkanVkd3d -> env["VKD3D_CONFIG"] = ""
            else -> Unit
        }
        env.putAll(parseEnvLines(runtimeProfile.environmentVariables))
        env.putAll(parseEnvLines(game.environmentVariables))
        return env
    }

    private fun parseEnvLines(raw: String): Map<String, String> = raw.lineSequence()
        .mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
        .toMap()

    /**
     * FORK-SPECIFIC AND UNVERIFIED. The correct invocation for running a
     * Windows PE binary under Wine-on-ARM64 with Box64 handling the x86/x64
     * machine code depends entirely on how the specific Wine-Android build was
     * compiled -- a native-ARM64 Wine binary with Box64 translating the game's
     * own x86/x64 code is the common community pattern assumed below. Confirm
     * and adjust this against whichever Wine build is actually installed.
     */
    private fun buildLaunchCommand(box64Bin: File, wineBin: File, exeFile: File, launchArgs: String): List<String> {
        val args = launchArgs.split(" ").filter { it.isNotBlank() }
        return listOf(box64Bin.absolutePath, wineBin.absolutePath, exeFile.absolutePath) + args
    }

    private fun failed(error: AppError): RuntimeStatus {
        status = RuntimeStatus.Failed(error)
        return status
    }
}
