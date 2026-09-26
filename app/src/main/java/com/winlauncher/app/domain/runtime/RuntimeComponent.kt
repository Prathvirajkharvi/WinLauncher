package com.winlauncher.app.domain.runtime

/**
 * Wine and Box64/Box86 are real Android-executable binaries (native ARM64 ELF).
 * DXVK and VKD3D-Proton are NOT executables -- they're sets of Windows .dll files
 * that get copied into the Wine prefix's drive_c/windows/system32 so Wine loads
 * them instead of its built-in d3d stubs. `folderName` is where each is expected
 * under the app's private runtime root; `isExecutable` distinguishes the two
 * kinds for RuntimeInstallationManager.
 */
enum class RuntimeComponent(val folderName: String, val displayName: String, val isExecutable: Boolean) {
    WINE("wine", "Wine", isExecutable = true),
    BOX64("box64", "Box64", isExecutable = true),
    BOX86("box86", "Box86 (optional, 32-bit x86 support)", isExecutable = true),
    DXVK("dxvk", "DXVK", isExecutable = false),
    VKD3D("vkd3d", "VKD3D-Proton", isExecutable = false),
}

data class RuntimeComponentStatus(
    val component: RuntimeComponent,
    val installed: Boolean,
    val version: String,
    val path: String,
)

data class RuntimeInstallationStatus(
    val components: List<RuntimeComponentStatus>,
    val runtimeRootPath: String,
) {
    val wineReady: Boolean get() = components.any { it.component == RuntimeComponent.WINE && it.installed }
    val box64Ready: Boolean get() = components.any { it.component == RuntimeComponent.BOX64 && it.installed }

    /** The minimum needed to attempt a real launch: Wine + Box64. Box86/DXVK/VKD3D are optional. */
    val readyForLaunch: Boolean get() = wineReady && box64Ready
}
