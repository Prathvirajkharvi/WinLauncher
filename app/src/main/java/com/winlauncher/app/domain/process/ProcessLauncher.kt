package com.winlauncher.app.domain.process

import java.io.File

/**
 * Generic OS-process launcher. Today this only ever runs the harmless dummy
 * command built by DummyRuntimeEngine. The real integration point for
 * Wine + Box64 + DXVK/VKD3D is exactly here: `command` becomes something like
 * [wineloaderPath, "box64", winePrefixEnv..., exePath, ...launchArgs], with
 * environment carrying WINEPREFIX, BOX64_*, DXVK_* variables. No other layer
 * needs to change when that happens.
 */
class ProcessLauncher {

    fun launch(
        command: List<String>,
        workingDirectory: File?,
        environment: Map<String, String>,
    ): ProcessMonitor {
        require(command.isNotEmpty()) { "command must not be empty" }

        val builder = ProcessBuilder(command)
        workingDirectory?.let { builder.directory(it) }
        builder.environment().putAll(environment)
        builder.redirectErrorStream(false)

        val process = builder.start()
        return ProcessMonitor(process, LogRingBuffer())
    }
}
