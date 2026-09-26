package com.winlauncher.app.domain.runtime

import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Which archive format an imported runtime package file is, decided from its file
 * name (SAF hands us a display name, not a content-type we can trust to sniff).
 *
 * .wcp is the one exception: real Winlator "Wine/Winlator Component Package"
 * files have shipped as BOTH zstd- and XZ-compressed tars in the wild -- the
 * .wcp extension alone never tells you which. ArchiveKind only records "this is
 * a .wcp"; extract() below sniffs the actual compression from the file's own
 * magic bytes before picking a decoder. See extractWcp's doc.
 */
enum class ArchiveKind {
    ZIP, TAR_GZ, TAR_ZST, WCP, UNKNOWN;

    companion object {
        fun fromFileName(name: String?): ArchiveKind {
            val n = name?.lowercase() ?: return UNKNOWN
            return when {
                n.endsWith(".zip") -> ZIP
                n.endsWith(".tar.gz") || n.endsWith(".tgz") -> TAR_GZ
                n.endsWith(".tar.zst") || n.endsWith(".tzst") -> TAR_ZST
                // Winlator-style .wcp ("Wine/Winlator Component Package") -- a compressed tar
                // under the hood, carrying an optional profile.json plus the actual runtime
                // payload. See WineWcpValidator for the extra, more specific acceptance check
                // imports of this format go through for the Wine slot, and extractWcp for why
                // the compression itself is detected from magic bytes, not assumed.
                n.endsWith(".wcp") -> WCP
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Extracts a runtime package archive into [targetDir] -- the staging directory
 * RuntimeInstallationManager validates before committing an import. Handles the
 * formats official runtime projects actually ship, each decoded for real:
 *
 *  - .zip           -- java.util.zip (JDK built-in).
 *  - .tar.gz/.tgz    -- java.util.zip.GZIPInputStream (JDK built-in) unwraps the
 *                        gzip layer; Apache Commons Compress reads the tar layer
 *                        (it correctly handles ustar/GNU/PAX long-name entries,
 *                        which real Wine/Box64 release tarballs can contain and a
 *                        hand-rolled 512-byte-header reader would likely mishandle).
 *  - .tar.zst        -- com.github.luben:zstd-jni's ZstdInputStream unwraps the zstd
 *                        layer, same Commons Compress tar reader after that. Uses the
 *                        official "@aar" artifact (implementation("com.github.luben:
 *                        zstd-jni:<version>@aar")), NOT the plain jar: the plain jar
 *                        auto-detects the desktop OS/arch and bundles glibc-Linux/macOS/
 *                        Windows native binaries, which fail to load under Android's
 *                        Bionic libc (confirmed by zstd-jni's own issue tracker -- a
 *                        plain-jar import throws "Unsupported OS/arch, cannot find
 *                        /linux/aarch64/libzstd-jni.so" on a real device). The "@aar"
 *                        classifier is zstd-jni's own officially published, separately
 *                        cross-compiled Android build (Android 5.0+, real arm64-v8a/
 *                        armeabi-v7a/x86/x86_64 .so files in the AAR's jniLibs layout),
 *                        so this project's existing `ndk { abiFilters += "arm64-v8a" }`
 *                        makes the Android Gradle Plugin keep only the arm64-v8a .so in
 *                        the APK and drop the others -- no extra ABIs actually ship.
 *                        A plain-jar `testImplementation("com.github.luben:zstd-jni:
 *                        <version>")` is added test-only (never in the APK) purely so
 *                        JVM unit tests -- which run on a desktop JVM, not an Android
 *                        device, and so can't load the @aar's Android-only .so -- can
 *                        still exercise this code for real; this is zstd-jni's own
 *                        documented pattern for Android projects.
 *  - .wcp            -- NOT assumed to be zstd just because .tar.zst is. Real Winlator
 *                        .wcp files have shipped compressed with BOTH zstd and XZ --
 *                        extractWcp() reads the first few bytes of the actual file and
 *                        checks them against the real zstd magic (28 B5 2F FD) and XZ
 *                        magic (FD 37 7A 58 5A 00) before picking a decoder, exactly
 *                        the same way `file`/`xz`/`zstd` themselves identify a stream.
 *                        XZ decoding reuses Commons Compress's XZCompressorInputStream,
 *                        backed by the org.tukaani:xz dependency (pure Java, no native
 *                        component -- unlike zstd-jni, no @aar/testImplementation split
 *                        needed). Once unwrapped, the same Commons Compress tar reader
 *                        and the same tar-slip guard as every other format here apply.
 *
 * Nothing here is ever relabeled as a different format than it is. Every entry name
 * is checked against a zip-slip/tar-slip path-traversal guard before anything is
 * written, regardless of format. A .wcp additionally goes through an extra,
 * more specific acceptance check afterward when imported into the Wine slot --
 * see WineWcpValidator.
 */
object ArchiveExtractor {

    fun extract(kind: ArchiveKind, input: InputStream, targetDir: File): List<String> {
        val names = mutableListOf<String>()
        when (kind) {
            ArchiveKind.ZIP -> extractZip(input, targetDir, names)
            ArchiveKind.TAR_GZ ->
                TarArchiveInputStream(GZIPInputStream(input)).use { extractTar(it, targetDir, names) }
            ArchiveKind.TAR_ZST ->
                TarArchiveInputStream(ZstdInputStream(input)).use { extractTar(it, targetDir, names) }
            // .wcp's actual compression is detected from its magic bytes -- see extractWcp.
            ArchiveKind.WCP -> extractWcp(input, targetDir, names)
            ArchiveKind.UNKNOWN -> throw IllegalArgumentException("Unsupported archive format")
        }
        if (names.isEmpty()) throw IllegalStateException("Archive contains no files.")
        return names
    }

    private fun extractZip(input: InputStream, targetDir: File, namesOut: MutableList<String>) {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                namesOut += entry.name
                val outFile = safeTarget(targetDir, entry.name)
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

    private fun extractTar(tarInput: TarArchiveInputStream, targetDir: File, namesOut: MutableList<String>) {
        var entry = tarInput.nextEntry
        while (entry != null) {
            namesOut += entry.name
            val outFile = safeTarget(targetDir, entry.name)
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { output -> tarInput.copyTo(output) }
            }
            entry = tarInput.nextEntry
        }
    }

    // Real magic-number prefixes -- not a guess, not derived from the .wcp extension.
    // Zstandard: https://datatracker.ietf.org/doc/html/rfc8878#section-3.1.1
    // XZ:        https://tukaani.org/xz/xz-file-format.txt section 2.1.1.1
    private val ZSTD_MAGIC = byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte())
    private val XZ_MAGIC = byteArrayOf(0xFD.toByte(), 0x37.toByte(), 0x7A.toByte(), 0x58.toByte(), 0x5A.toByte(), 0x00.toByte())

    /**
     * Extracts a .wcp by first reading its actual compression from its own magic bytes
     * -- never assumed from the .wcp extension, and never just reusing whatever
     * .tar.zst happens to use. Real Winlator .wcp packages have shipped both ways:
     * zstd-compressed (same as .tar.zst) and XZ-compressed. Once the real codec is
     * identified and unwrapped, extraction is identical to every other tar-based
     * format here: same Commons Compress tar reader, same tar-slip guard in
     * [extractTar]/[safeTarget] -- nothing about path-traversal protection changes
     * for this format.
     *
     * Deliberately does NOT let zstd-jni's or Commons Compress's own low-level
     * exception message (e.g. "Unknown frame descriptor") reach the caller: those
     * assume the reader already knows which codec they're looking at, which is
     * exactly what a misidentified .wcp means the caller doesn't. Every failure here
     * is rethrown as a plain "Invalid WCP: ..." IllegalStateException instead --
     * distinct from the generic "Corrupted or unreadable archive: ..." message
     * RuntimeInstallationManager produces for a truly unreadable .zip/.tar.gz/
     * .tar.zst, and specific enough to tell a user "wrong codec entirely" (magic
     * bytes matched neither zstd nor XZ) apart from "right codec, but the stream
     * itself is broken" (magic matched, decoding failed partway through).
     */
    private fun extractWcp(input: InputStream, targetDir: File, namesOut: MutableList<String>) {
        val buffered = BufferedInputStream(input, 8)
        buffered.mark(XZ_MAGIC.size)
        val header = ByteArray(XZ_MAGIC.size)
        val headerLen = readFully(buffered, header)
        buffered.reset()

        val decompressed: InputStream = when {
            headerLen >= ZSTD_MAGIC.size && header.regionMatches(ZSTD_MAGIC) ->
                try {
                    ZstdInputStream(buffered)
                } catch (e: Exception) {
                    throw IllegalStateException("Invalid WCP: corrupted compressed stream")
                }
            headerLen >= XZ_MAGIC.size && header.regionMatches(XZ_MAGIC) ->
                try {
                    XZCompressorInputStream(buffered)
                } catch (e: Exception) {
                    throw IllegalStateException("Invalid WCP: corrupted compressed stream")
                }
            else -> throw IllegalStateException("Invalid WCP: unsupported compression format")
        }

        try {
            TarArchiveInputStream(decompressed).use { extractTar(it, targetDir, namesOut) }
        } catch (e: SecurityException) {
            throw e // zip-slip/tar-slip guard: a real threat, never relabeled as "corrupted".
        } catch (e: Exception) {
            throw IllegalStateException("Invalid WCP: corrupted compressed stream")
        }
    }

    /** Reads up to [buffer].size bytes, returning how many were actually available. */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun ByteArray.regionMatches(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /** Shared zip-slip / tar-slip guard: refuse entries that would land outside [targetDir]. */
    private fun safeTarget(targetDir: File, entryName: String): File {
        val outFile = File(targetDir, entryName)
        val safePrefix = targetDir.canonicalPath + File.separator
        if (!outFile.canonicalPath.startsWith(safePrefix) && outFile.canonicalPath != targetDir.canonicalPath) {
            throw SecurityException("Archive entry escapes target directory: $entryName")
        }
        return outFile
    }
}
