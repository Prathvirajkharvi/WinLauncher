package com.winlauncher.app.domain.runtime

import java.io.File
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/**
 * profile.json as read from an imported Winlator-style .wcp (Wine/Winlator
 * Component Package). Every field is nullable: a .wcp with no profile.json at
 * all, or one with a malformed/unreadable profile.json, is still handled -- see
 * [WcpProfileReader.read] -- because [WineWcpValidator] never trusts
 * profile.json alone either way.
 */
data class WcpProfile(
    val category: String?,
    val name: String?,
    val versionCode: Int?,
)

/** Reads profile.json out of an extracted .wcp staging directory, when present. */
object WcpProfileReader {

    private const val PROFILE_FILE_NAME = "profile.json"

    /**
     * Looks for profile.json at the staging root, or one directory down (some .wcp
     * builds wrap their payload in a single top-level folder). Returns null if the
     * file is absent, or unreadable/malformed -- a missing or broken profile.json
     * is never by itself a reason to reject the package; see [WineWcpValidator].
     */
    fun read(root: File): WcpProfile? {
        val file = findProfileFile(root) ?: return null
        return try {
            val json = JSONObject(file.readText())
            WcpProfile(
                category = firstStringField(json, "category", "type", "contentsType", "componentType"),
                name = firstStringField(json, "name", "displayName"),
                versionCode = if (json.has("versionCode")) json.optInt("versionCode") else null,
            )
        } catch (e: JSONException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    private fun findProfileFile(root: File): File? {
        val direct = File(root, PROFILE_FILE_NAME)
        if (direct.isFile) return direct
        // One level down, in case the archive wraps its payload in a single folder
        // (e.g. "wine-9.0-x86_64/profile.json" instead of a bare root-level file).
        return root.listFiles()
            ?.firstOrNull { it.isDirectory }
            ?.let { File(it, PROFILE_FILE_NAME) }
            ?.takeIf { it.isFile }
    }

    private fun firstStringField(json: JSONObject, vararg keys: String): String? =
        keys.asSequence()
            .map { key -> json.optString(key, "") }
            .firstOrNull { it.isNotBlank() }
}

/**
 * Validates that a staged, already-extracted .wcp package is actually a Wine
 * runtime BEFORE RuntimeInstallationManager ever commits it as the installed
 * Wine component. Two independent signals are checked; either one being
 * unambiguously wrong is enough to reject:
 *
 *  1. profile.json's category/type (when present) must not name something
 *     other than Wine -- catches a DXVK/VKD3D/Box64/Box86 .wcp mistakenly
 *     imported into the Wine slot before any file scanning happens.
 *  2. The package must contain a real ELF binary named like an actual Wine
 *     entry point (wine/wine64/wineserver/wineboot/...), located RECURSIVELY
 *     since real Wine builds nest these under bin/ or deeper. Unlike Box64/
 *     Box86 -- which run directly on the device CPU and so must be arm64-v8a
 *     -- a Wine binary's own architecture is NOT required to match the
 *     Android host ABI: real Winlator Wine builds commonly ship x86_64 (or
 *     x86) Linux binaries that Box64 translates to ARM64 at runtime, and a
 *     genuine ARM64-native build is accepted too. See [ACCEPTED_WINE_ARCHES].
 *     A profile.json that merely CLAIMS "wine" is never enough on its own --
 *     a package must actually contain a working Wine binary.
 *
 * profile.json is optional: a .wcp with none, or an unreadable one, falls
 * through to signal 2 alone -- see [WcpProfileReader.read].
 */
object WineWcpValidator {

    /** Real Wine release entry-point binary names this checks for, recursively. */
    private val WINE_BINARY_NAMES = setOf(
        "wine", "wine64", "wineserver", "wineboot",
        "wine-preloader", "wine64-preloader",
    )

    /**
     * Architectures a genuine Wine binary may actually be built for. Unlike Box64/Box86
     * (which run directly on the device CPU and so MUST be arm64-v8a), Wine's own
     * wine/wine64/wineserver binaries are commonly x86_64 -- or even 32-bit x86 --
     * Linux ELFs that Box64 translates to ARM64 at runtime; a genuine ARM64-native Wine
     * build (used with CpuBackend.NATIVE_ARM) is accepted too. The Android host ABI
     * (arm64-v8a) and the Wine GUEST binary's architecture are simply different things.
     * OTHER_ELF (an unrecognized machine type) and NOT_ELF are never accepted -- this
     * still has to be a real, working ELF binary, not just any file with the right name.
     */
    private val ACCEPTED_WINE_ARCHES = setOf(
        ElfInspector.Arch.ARM64,
        ElfInspector.Arch.ARM32,
        ElfInspector.Arch.X86,
        ElfInspector.Arch.X86_64,
    )

    /**
     * Recursively locates candidate Wine runtime binaries under [root]: files whose
     * name matches a known Wine entry point AND that are a genuine, recognized ELF
     * executable (see [ACCEPTED_WINE_ARCHES]) -- a same-named text file or script does
     * not count, but a real x86_64 wine64 built to run under Box64 does.
     */
    fun locateWineBinaries(root: File): List<File> =
        root.walkTopDown()
            .filter { it.isFile && it.name.lowercase() in WINE_BINARY_NAMES }
            .filter { ElfInspector.detect(it) in ACCEPTED_WINE_ARCHES }
            .toList()

    /**
     * Returns a failure reason the user can act on, or null if [root] (a staged,
     * already-extracted .wcp) is acceptable as a Wine import.
     */
    fun validate(root: File): String? {
        val profile = WcpProfileReader.read(root)
        val category = profile?.category
        if (category != null && !category.contains("wine", ignoreCase = true)) {
            return "This .wcp package's profile.json reports its contents as " +
                "\"$category\", not Wine. Pick the correct .wcp file before importing."
        }

        if (locateWineBinaries(root).isEmpty()) {
            return "No Wine runtime binary (wine/wine64/wineserver) was found in this " +
                ".wcp package. Is this really a Wine component package?"
        }

        return null
    }
}
