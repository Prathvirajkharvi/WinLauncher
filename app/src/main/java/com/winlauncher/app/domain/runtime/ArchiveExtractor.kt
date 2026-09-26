package com.winlauncher.app.domain.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Which archive format an imported runtime package file is, decided from its file
 * name (SAF hands us a display name, not a content-type we can trust to sniff).
 */
enum class ArchiveKind {
    ZIP, TAR_GZ, TAR_ZST, UNKNOWN;

    companion object {
        fun fromFileName(name: String?): ArchiveKind {
            val n = name?.lowercase() ?: return UNKNOWN
            return when {
                n.endsWith(".zip") -> ZIP
                n.endsWith(".tar.gz") || n.endsWith(".tgz") -> TAR_GZ
                n.endsWith(".tar.zst") || n.endsWith(".tzst") -> TAR_ZST
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Extracts a runtime package archive into [targetDir] -- the staging directory
 * RuntimeInstallationManager validates before committing an import. Handles the
 * three formats official runtime projects actually ship, each decoded for real:
 *
 *  - .zip           -- java.util.zip (JDK built-in).
 *  - .tar.gz/.tgz    -- java.util.zip.GZIPInputStream (JDK built-in) unwraps the
 *                        gzip layer; Apache Commons Compress reads the tar layer
 *                        (it correctly handles ustar/GNU/PAX long-name entries,
 *                        which real Wine/Box64 release tarballs can contain and a
 *                        hand-rolled 512-byte-header reader would likely mishandle).
 *  - .tar.zst        -- io.airlift:aircompressor 0.27's ZstdInputStream unwraps the
 *                        zstd layer, same Commons Compress tar reader after that.
 *                        Deliberately pinned to the pre-2.0 aircompressor line: it
 *                        decodes zstd in pure Java (sun.misc.Unsafe-based), so it
 *                        actually runs under ART. aircompressor 2.x/"v3" rewrote the
 *                        Java path on java.lang.foreign (Project Panama), which needs
 *                        a Java 22+ runtime Android does not provide; the other common
 *                        choice, com.github.luben:zstd-jni, ships a native .so built
 *                        for glibc Linux that will fail to load under Android's Bionic
 *                        libc. NOTE: this .tar.zst path is unit-tested only against
 *                        archives this same library produced (round-trip) -- it has
 *                        NOT been verified on-device against a real, officially
 *                        published vkd3d-proton-*.tar.zst. Please confirm that before
 *                        relying on it.
 *
 * Nothing here is ever relabeled as a different format than it is. Every entry name
 * is checked against a zip-slip/tar-slip path-traversal guard before anything is
 * written, regardless of format.
 */
object ArchiveExtractor {

    fun extract(kind: ArchiveKind, input: InputStream, targetDir: File): List<String> {
        val names = mutableListOf<String>()
        when (kind) {
            ArchiveKind.ZIP -> extractZip(input, targetDir, names)
            ArchiveKind.TAR_GZ ->
                TarArchiveInputStream(GZIPInputStream(input)).use { extractTar(it, targetDir, names) }
            ArchiveKind.TAR_ZST ->
                TarArchiveInputStream(io.airlift.compress.zstd.ZstdInputStream(input)).use {
                    extractTar(it, targetDir, names)
                }
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
