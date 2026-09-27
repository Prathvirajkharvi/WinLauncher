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
    // Real Winlator "-phat" Wine packages (e.g. wine-10.0-rc2-phat.wcp) ship a nested
    // "wine": { "binPath": ..., "libPath": ..., "prefixPack": ... } manifest declaring
    // exactly where the package's own binaries/libraries/prefix payload live. Null for
    // any .wcp that doesn't declare one (older/simpler packages) -- WineWcpValidator
    // still falls back to a full recursive scan in that case, see its doc.
    val wineBinPath: String? = null,
    val wineLibPath: String? = null,
    val winePrefixPack: String? = null,
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
            // "type" (as in a real wine-*-phat.wcp's profile.json: {"type": "Wine", ...}) is
            // checked as a category source alongside the older "category"/"contentsType"/
            // "componentType" names -- profile.json's declared type is the authoritative
            // signal for what this package IS and must take priority over any name-based
            // guessing done later (see RuntimeInstallationManager.commitStagedImport).
            val wine = json.optJSONObject("wine")
            WcpProfile(
                category = firstStringField(json, "category", "type", "contentsType", "componentType"),
                name = firstStringField(json, "name", "displayName"),
                versionCode = if (json.has("versionCode")) json.optInt("versionCode") else null,
                wineBinPath = wine?.optString("binPath")?.takeIf { it.isNotBlank() },
                wineLibPath = wine?.optString("libPath")?.takeIf { it.isNotBlank() },
                winePrefixPack = wine?.optString("prefixPack")?.takeIf { it.isNotBlank() },
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
 *     imported into the Wine slot before any file scanning happens. This is
 *     the AUTHORITATIVE signal when present: a package whose profile.json
 *     declares {"type": "Wine", ...} (as real wine-*-phat.wcp packages do)
 *     must not later be second-guessed and rejected as DXVK/VKD3D just
 *     because it also happens to bundle DLLs or libraries with matching
 *     name substrings -- see the "wcpConfirmedWine" handling in
 *     RuntimeInstallationManager.commitStagedImport, which skips exactly
 *     that generic re-guess once this validator has already said yes.
 *  2. The package must contain a real ELF binary named like an actual Wine
 *     entry point (wine/wine64/wineserver/wineboot/...). When profile.json
 *     declares a "wine": {"binPath": ...} manifest, that declared directory
 *     is checked first (see [locateWineBinaries]); either way the search
 *     also falls back to the full package tree RECURSIVELY, since real
 *     Wine builds nest these under bin/ or deeper. Unlike Box64/
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
     * Locates candidate Wine runtime binaries under [root]: files whose name matches a
     * known Wine entry point AND that are a genuine, recognized ELF executable (see
     * [ACCEPTED_WINE_ARCHES]) -- a same-named text file or script does not count, but a
     * real x86_64 wine64 built to run under Box64 does.
     *
     * When [profile] declares a "wine.binPath" manifest entry (real wine-*-phat.wcp
     * packages, e.g. wine-10.0-rc2-phat.wcp, do this -- see [WcpProfile]), that declared
     * directory is searched first, since it's the package's own authoritative claim of
     * where its binaries live. Either way, a full recursive walk of [root] still runs
     * too and is merged in (deduplicated) -- a package with no manifest, or one whose
     * manifest is wrong/incomplete, still gets the same recursive fallback behavior a
     * .wcp with no profile.json at all has always had.
     */
    fun locateWineBinaries(root: File, profile: WcpProfile? = null): List<File> {
        fun scan(dir: File): List<File> =
            dir.walkTopDown()
                .filter { it.isFile && it.name.lowercase() in WINE_BINARY_NAMES }
                .filter { ElfInspector.detect(it) in ACCEPTED_WINE_ARCHES }
                .toList()

        val fromManifest = profile?.wineBinPath
            ?.let { File(root, it) }
            ?.takeIf { it.isDirectory }
            ?.let(::scan)
            .orEmpty()
        return (fromManifest + scan(root)).distinct()
    }

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

        if (locateWineBinaries(root, profile).isEmpty()) {
            return "No Wine runtime binary (wine/wine64/wineserver) was found in this " +
                ".wcp package. Is this really a Wine component package?"
        }

        return null
    }
}
