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
    // "arm64-v8a" once a real ELF probe confirms it; "n/a" for DXVK/VKD3D (not executables).
    val architecture: String = "n/a",
    // Box64 packages commonly bundle x86/x86_64 guest libraries alongside the ARM64 binary --
    // these stay associated with this same component/version rather than becoming a separate
    // "component" of their own.
    val guestLibraryCount: Int = 0,
)

data class RuntimeInstallationStatus(
    val components: List<RuntimeComponentStatus>,
    val runtimeRootPath: String,
) {
    // Deliberately no `readyForLaunch` here: which components are actually required
    // depends on a runtime profile's CpuBackend (Wine+Box64, Wine+Box86, or Wine alone
    // for NATIVE_ARM), which this type has no knowledge of. That decision belongs to
    // LaunchPreflight, which takes a CpuBackend and this status together -- see
    // LaunchPreflight.isLaunchable/report. A previous `readyForLaunch = wineReady &&
    // box64Ready` here hardcoded the BOX64 case and misreported NATIVE_ARM/BOX86 profiles.
}
