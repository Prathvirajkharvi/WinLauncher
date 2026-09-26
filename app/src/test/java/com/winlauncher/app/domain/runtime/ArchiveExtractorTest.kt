package com.winlauncher.app.domain.runtime

import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun buildZip(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun buildTar(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(bytes).use { tar ->
            entries.forEach { (name, content) ->
                val entry = TarArchiveEntry(name)
                entry.size = content.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(content)
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
        return bytes.toByteArray()
    }

    private fun buildTarGz(entries: Map<String, ByteArray>): ByteArray {
        val tarBytes = buildTar(entries)
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(tarBytes) }
        return gz.toByteArray()
    }

    private fun buildTarZst(entries: Map<String, ByteArray>): ByteArray {
        val tarBytes = buildTar(entries)
        val zst = ByteArrayOutputStream()
        ZstdOutputStream(zst).use { it.write(tarBytes) }
        return zst.toByteArray()
    }

    @Test
    fun `archive kind is detected from file name, case-insensitively`() {
        assertEquals(ArchiveKind.ZIP, ArchiveKind.fromFileName("box64-v1.zip"))
        assertEquals(ArchiveKind.ZIP, ArchiveKind.fromFileName("BOX64.ZIP"))
        assertEquals(ArchiveKind.TAR_GZ, ArchiveKind.fromFileName("dxvk-2.4.tar.gz"))
        assertEquals(ArchiveKind.TAR_GZ, ArchiveKind.fromFileName("wine-9.0.tgz"))
        assertEquals(ArchiveKind.TAR_ZST, ArchiveKind.fromFileName("vkd3d-proton-2.13.tar.zst"))
        assertEquals(ArchiveKind.UNKNOWN, ArchiveKind.fromFileName("wine.exe"))
        assertEquals(ArchiveKind.UNKNOWN, ArchiveKind.fromFileName(null))
    }

    @Test
    fun `extracts a zip with nested dxvk-style x64 and x32 directories`() {
        val zip = buildZip(
            mapOf(
                "dxvk-2.4/x64/d3d11.dll" to "sixtyfour".toByteArray(),
                "dxvk-2.4/x32/d3d11.dll" to "thirtytwo".toByteArray(),
            ),
        )
        val target = tmp.newFolder("zip-out")
        val names = ArchiveExtractor.extract(ArchiveKind.ZIP, zip.inputStream(), target)

        assertEquals(2, names.size)
        assertEquals("sixtyfour", File(target, "dxvk-2.4/x64/d3d11.dll").readText())
        assertEquals("thirtytwo", File(target, "dxvk-2.4/x32/d3d11.dll").readText())
    }

    @Test
    fun `extracts a real tar-gz stream, not just a renamed zip`() {
        val tarGz = buildTarGz(
            mapOf("dxvk-2.4/x64/dxgi.dll" to "dxvk-payload".toByteArray()),
        )
        val target = tmp.newFolder("targz-out")
        val names = ArchiveExtractor.extract(ArchiveKind.TAR_GZ, tarGz.inputStream(), target)

        assertEquals(listOf("dxvk-2.4/x64/dxgi.dll"), names)
        assertEquals("dxvk-payload", File(target, "dxvk-2.4/x64/dxgi.dll").readText())
    }

    @Test
    fun `extracts a real tar-zst stream, not just a renamed zip`() {
        val tarZst = buildTarZst(
            mapOf(
                "vkd3d-proton-2.13/x64/d3d12.dll" to "vkd3d64".toByteArray(),
                "vkd3d-proton-2.13/x86/d3d12.dll" to "vkd3d32".toByteArray(),
            ),
        )
        val target = tmp.newFolder("tarzst-out")
        val names = ArchiveExtractor.extract(ArchiveKind.TAR_ZST, tarZst.inputStream(), target)

        assertEquals(2, names.size)
        assertEquals("vkd3d64", File(target, "vkd3d-proton-2.13/x64/d3d12.dll").readText())
        assertEquals("vkd3d32", File(target, "vkd3d-proton-2.13/x86/d3d12.dll").readText())
    }

    @Test
    fun `rejects a zip entry that attempts to escape the target directory`() {
        val evilZip = buildZip(mapOf("../../evil.dll" to "x".toByteArray()))
        val target = tmp.newFolder("slip-zip-out")
        assertThrows(SecurityException::class.java) {
            ArchiveExtractor.extract(ArchiveKind.ZIP, evilZip.inputStream(), target)
        }
    }

    @Test
    fun `rejects a tar entry that attempts to escape the target directory`() {
        val evilTarGz = buildTarGz(mapOf("../../evil.dll" to "x".toByteArray()))
        val target = tmp.newFolder("slip-tar-out")
        assertThrows(SecurityException::class.java) {
            ArchiveExtractor.extract(ArchiveKind.TAR_GZ, evilTarGz.inputStream(), target)
        }
    }

    @Test
    fun `empty archive is reported as containing no files`() {
        val emptyZip = buildZip(emptyMap())
        val target = tmp.newFolder("empty-out")
        assertThrows(IllegalStateException::class.java) {
            ArchiveExtractor.extract(ArchiveKind.ZIP, emptyZip.inputStream(), target)
        }
    }

    @Test
    fun `unknown archive kind is rejected explicitly rather than guessed at`() {
        val target = tmp.newFolder("unknown-out")
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveExtractor.extract(ArchiveKind.UNKNOWN, "irrelevant".toByteArray().inputStream(), target)
        }
    }

    @Test
    fun `directory entries are created without requiring a file inside them`() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("wine-9.0/lib/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("wine-9.0/bin/wine"))
            zip.write("binary".toByteArray())
            zip.closeEntry()
        }
        val target = tmp.newFolder("dirs-out")
        ArchiveExtractor.extract(ArchiveKind.ZIP, bytes.toByteArray().inputStream(), target)

        assertTrue(File(target, "wine-9.0/lib").isDirectory)
        assertEquals("binary", File(target, "wine-9.0/bin/wine").readText())
    }
}
