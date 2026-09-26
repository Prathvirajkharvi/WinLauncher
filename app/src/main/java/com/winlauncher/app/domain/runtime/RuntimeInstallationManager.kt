package com.winlauncher.app.domain.runtime

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

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
 */
class RuntimeInstallationManager(context: Context) {

    private val runtimeRoot: File = File(context.filesDir, "runtime").apply { mkdirs() }

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
        // for extracted archives, fall back to a shallow search for a matching file name.
        val canonical = File(dir, component.folderName)
        if (canonical.isFile) return canonical
        return dir.walkTopDown().maxDepth(3).firstOrNull { it.isFile && it.name == component.folderName }
    }

    private fun versionFile(component: RuntimeComponent) = File(componentDir(component), "VERSION.txt")

    private fun componentStatus(component: RuntimeComponent): RuntimeComponentStatus {
        val dir = componentDir(component)
        val installed = if (component.isExecutable) {
            binaryFile(component)?.exists() == true
        } else {
            dir.listFiles()?.any { it.extension.equals("dll", ignoreCase = true) } == true
        }
        val version = versionFile(component).takeIf { it.isFile }?.readText()?.trim()
            ?: if (installed) "unknown (no version recorded)" else "not installed"
        return RuntimeComponentStatus(component, installed, version, dir.absolutePath)
    }

    /**
     * Imports a user-picked file for [component]. A `.zip` is extracted in full
     * into the component's folder (used for DXVK/VKD3D dll sets, or a full
     * Wine/Box64 distribution archive). Any other file is copied as-is and, for
     * executable components, named canonically so [binaryFile] finds it, with
     * the executable bit set (see class doc for why that bit alone isn't
     * sufficient on Android 10+).
     */
    fun importComponent(
        component: RuntimeComponent,
        sourceUri: Uri,
        displayFileName: String?,
        versionLabel: String?,
        contentResolver: ContentResolver,
    ): Result<RuntimeComponentStatus> {
        return try {
            val dir = componentDir(component)
            val looksLikeZip = displayFileName?.endsWith(".zip", ignoreCase = true) == true

            val opened = contentResolver.openInputStream(sourceUri)
                ?: return Result.failure(IllegalStateException("Could not open the selected file"))

            opened.use { input ->
                if (looksLikeZip) {
                    extractZip(input, dir)
                } else {
                    val target = if (component.isExecutable) {
                        File(dir, component.folderName)
                    } else {
                        File(dir, displayFileName ?: "${component.folderName}.dll")
                    }
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                    if (component.isExecutable) target.setExecutable(true, false)
                }
            }

            if (versionLabel != null) versionFile(component).writeText(versionLabel)
            Result.success(componentStatus(component))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractZip(input: InputStream, targetDir: File) {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                // Zip-slip guard: refuse entries that escape the target directory.
                val safePrefix = targetDir.canonicalPath + File.separator
                if (!outFile.canonicalPath.startsWith(safePrefix) && outFile.canonicalPath != targetDir.canonicalPath) {
                    throw SecurityException("Zip entry escapes target directory: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
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
        val dlls = componentDir(component).listFiles { f -> f.extension.equals("dll", ignoreCase = true) }
        if (dlls.isNullOrEmpty()) return false

        val system32 = File(prefixDir, "drive_c/windows/system32").apply { mkdirs() }
        dlls.forEach { dll -> dll.copyTo(File(system32, dll.name), overwrite = true) }
        return true
    }
}
