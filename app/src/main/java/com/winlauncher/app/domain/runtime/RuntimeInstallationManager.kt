package com.winlauncher.app.domain.runtime

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Owns the app-private "runtime root" where user-supplied Wine/Box64/Box86/DXVK/
 * VKD3D packages live, and the import mechanism for getting them there via SAF.
 *
 * IMPORTANT PLATFORM LIMITATION: on Android 10+ (API 29+), the OS enforces W^X
 * (write XOR execute) on files an app writes to its own private storage at
 * runtime -- a binary copied here via SAF import generally CANNOT be exec'd via
 * ProcessBuilder on a stock, non-rooted device. The only reliable way to ship an
 * executable Android will actually let you run is to package it under
 * app/src/main/jniLibs/<abi>/ at APK BUILD TIME (as e.g. libwine.so /
 * libbox64.so), which PackageManager extracts into
 * ApplicationInfo.nativeLibraryDir with execute permission already granted.
 * This class still implements real import/discovery/version probing -- useful
 * for DXVK/VKD3D (plain data files, no exec restriction) and for staging
 * binaries during development -- but REAL EXECUTION of an imported Wine/Box64
 * binary is not guaranteed on a modern, non-rooted device until that
 * build-time packaging step exists. See README "Known limitations".
 *
 * IMPORT SAFETY: every import is staged into a scratch directory first and
 * validated there (component-type guess, ELF/ABI check via ElfInspector +
 * RuntimePackageValidator) before anything touches the component's real
 * directory. A previously installed version is never silently overwritten --
 * callers must pass replaceExisting = true, which is only reachable in the UI
 * after an explicit user confirmation.
 */
class RuntimeInstallationManager(context: Context) {

    private val runtimeRoot: File = File(context.filesDir, "runtime").apply { mkdirs() }
    private val stagingRoot: File = File(runtimeRoot, ".staging").apply { mkdirs() }

    fun status(): RuntimeInstallationStatus {
        val statuses = RuntimeComponent.entries.map { componentStatus(it) }
        return RuntimeInstallationStatus(statuses, runtimeRoot.absolutePath)
    }

    fun componentDir(component: RuntimeComponent): File =
        File(runtimeRoot, component.folderName).apply { mkdirs() }

    fun binaryFile(component: RuntimeComponent): File? {
        require(component.isExecutable) { "${component.displayName} is not an executable component" }
        val dir = componentDir(component)
        // Import places the canonical binary at <dir>/<folderName> (see importComponent);
        // for extracted archives, fall back to a search for an ARM64-executable file, then
        // to a shallow search for a matching file name.
        val canonical = File(dir, component.folderName)
        if (canonical.isFile) return canonical
        val armMatch = dir.walkTopDown().maxDepth(4)
            .firstOrNull { it.isFile && ElfInspector.detect(it) == ElfInspector.Arch.ARM64 }
        if (armMatch != null) return armMatch
        return dir.walkTopDown().maxDepth(3).firstOrNull { it.isFile && it.name == component.folderName }
    }

    private fun versionFile(component: RuntimeComponent) = File(componentDir(component), "VERSION.txt")

    private fun componentStatus(component: RuntimeComponent): RuntimeComponentStatus {
        val dir = componentDir(component)
        val installed = if (component.isExecutable) {
            binaryFile(component)?.exists() == true
        } else {
            // Recursive: official DXVK/VKD3D-Proton archives extract into nested
            // <componentDir>/<pkg-name>/x64/*.dll (and x32 or x86) trees, not flat
            // directories, so a direct-children-only listFiles() here would report
            // "not installed" even for a correctly imported package.
            dir.walkTopDown().any { it.isFile && it.extension.equals("dll", ignoreCase = true) }
        }
        val version = versionFile(component).takeIf { it.isFile }?.readText()?.trim()
            ?: if (installed) "unknown (no version recorded)" else "not installed"

        val architecture = if (installed && component.isExecutable) {
            binaryFile(component)?.let { ElfInspector.detect(it).label() } ?: "unknown"
        } else {
            "n/a"
        }

        val guestLibraryCount = if (installed && component == RuntimeComponent.BOX64) {
            countGuestLibraries(dir)
        } else {
            0
        }

        return RuntimeComponentStatus(component, installed, version, dir.absolutePath, architecture, guestLibraryCount)
    }

    private fun countGuestLibraries(dir: File): Int =
        dir.walkTopDown().maxDepth(4)
            .count { it.isFile && ElfInspector.detect(it).isX86Guest() }

    /**
     * Imports a user-picked file for [component]. A `.zip`, `.tar.gz`/`.tgz`, or
     * `.tar.zst`/`.tzst` is extracted in full via [ArchiveExtractor] (used for
     * DXVK/VKD3D dll sets, or a full Wine/Box64/Box86 distribution archive --
     * Box64 archives that bundle x86/x86_64 guest libraries alongside the ARM64
     * binary keep them together under this same component/version, see
     * [countGuestLibraries]). Any other file is copied as-is and, for executable
     * components, named canonically so [binaryFile] finds it.
     *
     * The import is staged and validated (see class doc) before it ever
     * touches the real component directory, and refuses to overwrite an
     * existing installed version unless [replaceExisting] is true.
     */
    fun importComponent(
        component: RuntimeComponent,
        sourceUri: Uri,
        displayFileName: String?,
        versionLabel: String?,
        contentResolver: ContentResolver,
        replaceExisting: Boolean = false,
    ): Result<RuntimeComponentStatus> {
        val staging = File(stagingRoot, "${component.folderName}-${System.currentTimeMillis()}")
        return try {
            val existing = componentStatus(component)
            if (existing.installed && !replaceExisting) {
                return Result.failure(
                    IllegalStateException(
                        "${component.displayName} is already installed (version ${existing.version}). " +
                            "Remove it or confirm replacement before importing another version.",
                    ),
                )
            }

            staging.mkdirs()
            val archiveKind = ArchiveKind.fromFileName(displayFileName)

            val opened = contentResolver.openInputStream(sourceUri)
                ?: return Result.failure(IllegalStateException("Could not open the selected file"))

            val archiveEntryNames = mutableListOf<String>()
            opened.use { input ->
                if (archiveKind != ArchiveKind.UNKNOWN) {
                    // .zip, .tar.gz/.tgz, and .tar.zst/.tzst are all extracted for real here --
                    // never just relabeled as one another. See ArchiveExtractor's doc for why
                    // each format needs its own decoder.
                    try {
                        archiveEntryNames += ArchiveExtractor.extract(archiveKind, input, staging)
                    } catch (e: java.util.zip.ZipException) {
                        throw IllegalStateException("Corrupted or unreadable archive: ${e.message}")
                    } catch (e: java.io.IOException) {
                        throw IllegalStateException("Corrupted or unreadable archive: ${e.message}")
                    }
                } else {
                    val target = if (component.isExecutable) {
                        File(staging, component.folderName)
                    } else {
                        File(staging, displayFileName ?: "${component.folderName}.dll")
                    }
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                    // Use the ORIGINAL picked file name for the component-type guess below, not
                    // the canonical target name it was just copied to -- otherwise a wine binary
                    // imported (by mistake) under the Box64 slot would always look like a Box64
                    // match, since we'd only ever see the name we ourselves chose for it.
                    archiveEntryNames += (displayFileName ?: target.name)
                }
            }

            // Component-type sanity check from file/entry names. This only catches confident,
            // unambiguous mismatches (guessComponentType returns null for anything it isn't sure
            // about) -- the ELF/DLL scan below is the authoritative gate that runs regardless.
            val guessed = RuntimePackageValidator.guessComponentType(archiveEntryNames)
            if (guessed != null && guessed != component) {
                staging.deleteRecursively()
                return Result.failure(
                    IllegalStateException(
                        "This looks like a ${guessed.displayName} package, not ${component.displayName}. " +
                            "Pick the correct component before importing, or use the right file.",
                    ),
                )
            }

            val findings = RuntimePackageValidator.scan(staging)
            val validationError = RuntimePackageValidator.validate(component, findings)
            if (validationError != null) {
                staging.deleteRecursively()
                return Result.failure(IllegalStateException(validationError))
            }

            if (component.isExecutable) {
                findings.armExecutables.forEach { it.setExecutable(true, false) }
                // Only safe to pick a canonical entry point automatically when the package has
                // exactly one top-level ARM64 binary (e.g. a bare `box64` file). A multi-binary
                // tree (a full Wine build with wine/wine64/wineserver/...) is left as-is for
                // binaryFile()'s own resolution at launch time -- guessing wrong here would
                // silently point every launch at the wrong executable.
                val onlyCandidate = findings.armExecutables.singleOrNull()
                if (onlyCandidate != null && onlyCandidate.parentFile == staging &&
                    onlyCandidate.name != component.folderName
                ) {
                    onlyCandidate.renameTo(File(staging, component.folderName))
                }
            }

            // Commit: clear the real directory and move the validated staging directory into place.
            val finalDir = componentDir(component)
            finalDir.deleteRecursively()
            if (!staging.renameTo(finalDir)) {
                staging.copyRecursively(finalDir, overwrite = true)
                staging.deleteRecursively()
            }

            if (versionLabel != null) versionFile(component).writeText(versionLabel)
            Result.success(componentStatus(component))
        } catch (e: Exception) {
            staging.deleteRecursively()
            Result.failure(e)
        }
    }

    /**
     * Removes an installed component's files and version metadata. Safe in
     * the sense that it only ever touches this one component's own directory
     * -- it does not affect Wine prefixes, other components, or in-flight
     * game processes (callers should stop any running game/runtime first;
     * RuntimeManagerScreen's confirmation dialog reminds the user of that).
     */
    fun removeComponent(component: RuntimeComponent): Result<Unit> {
        return try {
            val dir = componentDir(component)
            dir.deleteRecursively()
            dir.mkdirs()
            val vf = versionFile(component)
            if (vf.exists()) vf.delete()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Best-effort `<binary> --version` probe. Not every binary supports this
     * flag, and this can only succeed where the exec-permission limitation
     * documented on this class doesn't apply.
     */
    fun probeVersion(binary: File, timeoutMs: Long = 3000): String {
        return try {
            val process = ProcessBuilder(binary.absolutePath, "--version").redirectErrorStream(true).start()
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return "unknown (version probe timed out)"
            }
            process.inputStream.bufferedReader().readLine()?.trim() ?: "unknown (empty output)"
        } catch (e: Exception) {
            "unknown (${e.message ?: "probe failed"})"
        }
    }

    /**
     * Copies DXVK/VKD3D DLLs into a Wine prefix's system32 so Wine's DLL
     * override picks them up instead of its built-in stubs -- the same
     * mechanism DXVK's own desktop `setup_dxvk.sh` uses. No-op if the
     * component isn't installed. Assumes the prefix's drive_c tree already
     * exists (see RealRuntimeEngine's initialize() "first-run bootstrap" note).
     */
    fun installIntoPrefix(component: RuntimeComponent, prefixDir: File): Boolean {
        if (component.isExecutable) return false
        val dir = componentDir(component)
        // Recursive: see the recursive-DLL-scan note on componentStatus() above -- both
        // "is this installed" and "which files do we actually copy" need to look past
        // the top level of the component directory.
        val dlls = dir.walkTopDown().filter { it.isFile && it.extension.equals("dll", ignoreCase = true) }.toList()
        if (dlls.isEmpty()) return false

        val system32 = File(prefixDir, "drive_c/windows/system32").apply { mkdirs() }
        dlls.forEach { dll ->
            val targetDir = when (classifyDllBitness(dll, dir)) {
                DllBitness.BIT32 -> File(prefixDir, "drive_c/windows/syswow64").apply { mkdirs() }
                else -> system32
            }
            dll.copyTo(File(targetDir, dll.name), overwrite = true)
        }
        return true
    }
}

/**
 * Official DXVK ("x32"/"x64") and VKD3D-Proton ("x86"/"x64") release archives each
 * ship two DLL sets under those exact subdirectory names, and both sets use the SAME
 * file names (d3d11.dll, d3d12.dll, ...) -- so which subdirectory a DLL was found
 * under has to be preserved all the way to installIntoPrefix(), or the 32-bit and
 * 64-bit sets would silently overwrite each other in system32 instead of one going to
 * system32 and the other to syswow64. A flat package with no such subdirectory (e.g.
 * a hand-curated single-arch DLL set) keeps the previous behavior of going straight
 * to system32 -- UNSPECIFIED and BIT64 are handled identically in installIntoPrefix().
 */
internal enum class DllBitness { BIT64, BIT32, UNSPECIFIED }

internal fun classifyDllBitness(dllFile: File, componentRoot: File): DllBitness {
    val ancestorNames = generateSequence(dllFile.parentFile) { it.parentFile }
        .takeWhile { it != componentRoot }
        .map { it.name.lowercase() }
        .toList()
    val is64 = ancestorNames.any { it == "x64" }
    val is32 = ancestorNames.any { it == "x32" || it == "x86" }
    return when {
        is32 && !is64 -> DllBitness.BIT32
        is64 -> DllBitness.BIT64
        else -> DllBitness.UNSPECIFIED
    }
}
