package com.winlauncher.app.domain.runtime

import java.io.File

/**
 * Inspects an extracted (or single-file) runtime package on disk and decides
 * whether it's actually usable for the [RuntimeComponent] the user selected in
 * Runtime Manager, BEFORE RuntimeInstallationManager commits it over any
 * previously installed version. This is where the "do not assume a random
 * Linux x86_64 archive is directly executable on Android" requirement is
 * enforced: every ELF file found is classified by real machine-type, not by
 * file name.
 */
object RuntimePackageValidator {

    data class Findings(
        val armExecutables: List<File>,
        val x86Guests: List<File>,
        val otherElf: List<File>,
        val dllFiles: List<File>,
        val totalFiles: Int,
    ) {
        val isEmpty: Boolean get() = totalFiles == 0
    }

    /** Walks [root] and classifies every regular file it contains. */
    fun scan(root: File): Findings {
        val arm = mutableListOf<File>()
        val x86 = mutableListOf<File>()
        val otherElf = mutableListOf<File>()
        val dlls = mutableListOf<File>()
        var total = 0

        root.walkTopDown().filter { it.isFile }.forEach { file ->
            total++
            if (file.extension.equals("dll", ignoreCase = true)) {
                dlls += file
                return@forEach
            }
            when (val arch = ElfInspector.detect(file)) {
                ElfInspector.Arch.ARM64 -> arm += file
                ElfInspector.Arch.X86, ElfInspector.Arch.X86_64 -> x86 += file
                ElfInspector.Arch.ARM32, ElfInspector.Arch.OTHER_ELF -> otherElf += file
                ElfInspector.Arch.NOT_ELF -> Unit // config files, text, readmes, etc. -- not an error by itself
                else -> Unit
            }
        }
        return Findings(arm, x86, otherElf, dlls, total)
    }

    /**
     * The real DLL file names (without extension) each non-executable component's
     * OFFICIAL release actually ships. Used by [validate] so an import isn't accepted
     * just because *some* .dll is present -- e.g. a stray/unrelated .dll sitting next
     * to a Wine or Box64 binary must not be enough to call a DXVK or VKD3D-Proton
     * import "installed".
     */
    private val EXPECTED_DLL_STEMS: Map<RuntimeComponent, List<String>> = mapOf(
        RuntimeComponent.DXVK to listOf("d3d9", "d3d10core", "d3d11", "dxgi"),
        RuntimeComponent.VKD3D to listOf("d3d12", "d3d12core"),
    )

    /** Cheap guess of a package's component type from entry names alone, used to catch obvious mismatches. */
    fun guessComponentType(entryNames: List<String>): RuntimeComponent? {
        val lower = entryNames.map { it.lowercase() }
        return when {
            lower.any { it.contains("box64") } -> RuntimeComponent.BOX64
            lower.any { it.contains("box86") } -> RuntimeComponent.BOX86
            lower.any { it.contains("vkd3d") } -> RuntimeComponent.VKD3D
            lower.any {
                it.endsWith(".dll") && listOf("d3d9", "d3d10", "d3d11", "dxgi").any(it::contains)
            } -> RuntimeComponent.DXVK
            lower.any { it == "wine" || it.endsWith("/wine") || it.contains("wineserver") || it.contains("wine64") } ->
                RuntimeComponent.WINE
            else -> null
        }
    }

    /**
     * The core acceptance check for a staged import. Returns a failure reason
     * the user can act on, or null if the package is acceptable for
     * [component].
     */
    fun validate(component: RuntimeComponent, findings: Findings): String? {
        if (findings.isEmpty) {
            return "Archive contains no files."
        }
        if (component.isExecutable) {
            if (findings.armExecutables.isEmpty()) {
                return when {
                    findings.x86Guests.isNotEmpty() ->
                        "No ARM64 (AArch64) binary found for ${component.displayName} -- only x86/x86_64 " +
                            "Linux files were found (${findings.x86Guests.size} file(s)). A desktop Linux " +
                            "build cannot run on this device; an Android/arm64-v8a build is required."
                    findings.otherElf.isNotEmpty() ->
                        "No ARM64 (AArch64) binary found for ${component.displayName} -- found " +
                            "${findings.otherElf.size} ELF file(s) of an unsupported machine type instead."
                    else ->
                        "No ELF binary found for ${component.displayName} in this package -- is this the right file?"
                }
            }
        } else {
            if (findings.dllFiles.isEmpty()) {
                return "No .dll files found in this package -- expected a ${component.displayName} DLL set."
            }
            // Presence of *a* .dll isn't enough -- a Wine/Box64/Box86 build sitting in the
            // wrong slot, or one that happens to carry an unrelated stray .dll, must not be
            // reported as an installed DXVK/VKD3D-Proton package just because dllFiles is
            // non-empty. Require at least one file matching what the real release ships.
            val expectedStems = EXPECTED_DLL_STEMS[component]
            if (expectedStems != null) {
                val foundStems = findings.dllFiles.map { it.nameWithoutExtension.lowercase() }
                if (foundStems.none { it in expectedStems }) {
                    return "Found ${findings.dllFiles.size} .dll file(s), but none match the DLLs " +
                        "${component.displayName} actually ships (expected one of: " +
                        "${expectedStems.joinToString(", ")}). Is this really a ${component.displayName} package?"
                }
            }
        }
        return null
    }
}
