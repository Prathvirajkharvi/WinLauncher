package com.winlauncher.app.domain.runtime

import com.github.luben.zstd.ZstdOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.io.path.createTempDirectory

/**
 * Covers the .wcp ("Wine/Winlator Component Package") import path added to
 * ArchiveExtractor/RuntimePackageValidator/RuntimeInstallationManager: profile.json
 * reading, the Wine-specific acceptance gate in [WineWcpValidator], and end-to-end
 * install/replace behavior via [RuntimeInstallationManager.commitStagedImport] (the
 * Android-Context-free half of importComponent -- see its doc).
 *
 * Real Winlator .wcp files have shipped as both zstd- and XZ-compressed tars --
 * ArchiveExtractor detects which from the file's own magic bytes (see its
 * extractWcp doc), never from the .wcp extension. [extractWcp] below defaults to
 * building zstd bytes; [extractXzWcp] builds real XZ bytes for the tests that
 * specifically need to confirm the XZ path works end-to-end too.
 */
class WineComponentPackageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // --- helpers -----------------------------------------------------------------

    /** Minimal valid ELF header matching the layout ElfInspector.detect actually parses. */
    private fun elfBytes(machine: Int): ByteArray {
        val header = ByteArray(20)
        header[0] = 0x7F.toByte(); header[1] = 'E'.code.toByte(); header[2] = 'L'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = 2; header[5] = 1 // 64-bit, little-endian
        header[18] = (machine and 0xFF).toByte()
        header[19] = ((machine shr 8) and 0xFF).toByte()
        return header
    }

    private fun writeElf(dir: File, relativePath: String, machine: Int): File {
        val file = File(dir, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(elfBytes(machine))
        return file
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

    private fun buildTarZst(entries: Map<String, ByteArray>): ByteArray {
        val tarBytes = buildTar(entries)
        val zst = ByteArrayOutputStream()
        ZstdOutputStream(zst).use { it.write(tarBytes) }
        return zst.toByteArray()
    }

    private fun buildTarXz(entries: Map<String, ByteArray>): ByteArray {
        val tarBytes = buildTar(entries)
        val xz = ByteArrayOutputStream()
        XZCompressorOutputStream(xz).use { it.write(tarBytes) }
        return xz.toByteArray()
    }

    /** Default WCP test data: zstd-compressed, matching real Winlator packages seen so far. */
    private fun extractWcp(entries: Map<String, ByteArray>, into: File) {
        val wcp = buildTarZst(entries)
        ArchiveExtractor.extract(ArchiveKind.WCP, wcp.inputStream(), into)
    }

    /** XZ-compressed variant, for tests confirming that codec works end-to-end too. */
    private fun extractXzWcp(entries: Map<String, ByteArray>, into: File) {
        val wcp = buildTarXz(entries)
        ArchiveExtractor.extract(ArchiveKind.WCP, wcp.inputStream(), into)
    }

    // --- 1. valid Wine .wcp ---------------------------------------------------------

    @Test
    fun `valid wine wcp with matching profile json and real arm64 wine binary is accepted`() {
        val dir = tmp.newFolder("valid-wine-wcp")
        extractWcp(
            mapOf(
                "profile.json" to """{"category":"wine","name":"Wine 9.0 arm64"}""".toByteArray(),
                "wine-9.0/bin/wine64" to elfBytes(183), // EM_AARCH64
            ),
            dir,
        )

        assertNull(WineWcpValidator.validate(dir))
        assertEquals(1, WineWcpValidator.locateWineBinaries(dir).size)
    }

    @Test
    fun `wine wcp containing x86_64 wine64 and wineserver, run under Box64, is accepted`() {
        // The real-world case this fixes: a Winlator-style Wine build whose own
        // wine64/wineserver binaries are x86_64 Linux ELFs, translated to ARM64 by Box64 at
        // runtime -- NOT an arm64-v8a-native build. The Android host ABI (arm64-v8a) and the
        // Wine guest binary's architecture are different things; this must still be accepted.
        val dir = tmp.newFolder("valid-wine-wcp-x86_64")
        extractWcp(
            mapOf(
                "profile.json" to """{"category":"wine","name":"Wine 10.0-rc2 (x86_64, Box64)"}""".toByteArray(),
                "wine-10.0-rc2/bin/wine64" to elfBytes(62), // EM_X86_64
                "wine-10.0-rc2/bin/wineserver" to elfBytes(62), // EM_X86_64
            ),
            dir,
        )

        assertNull(WineWcpValidator.validate(dir))
        assertEquals(2, WineWcpValidator.locateWineBinaries(dir).size)
    }

    @Test
    fun `wine wcp containing a 32-bit x86 wine binary alongside x86_64 wine64 is accepted`() {
        val dir = tmp.newFolder("valid-wine-wcp-x86-and-x86_64")
        extractWcp(
            mapOf(
                "profile.json" to """{"category":"wine"}""".toByteArray(),
                "bin/wine" to elfBytes(3), // EM_386 -- 32-bit wine
                "bin/wine64" to elfBytes(62), // EM_X86_64 -- 64-bit wine64
            ),
            dir,
        )

        assertNull(WineWcpValidator.validate(dir))
        assertEquals(2, WineWcpValidator.locateWineBinaries(dir).size)
    }

    @Test
    fun `valid wine wcp compressed with xz instead of zstd is accepted the same way`() {
        // Real Winlator .wcp packages (e.g. wine-10.0-rc2-phat.wcp) have shipped XZ-compressed
        // rather than zstd-compressed -- extraction must detect that from magic bytes (see
        // ArchiveExtractor.extractWcp), and everything downstream of extraction (this
        // validator) must not care which codec was actually used.
        val dir = tmp.newFolder("valid-wine-wcp-xz")
        extractXzWcp(
            mapOf(
                "profile.json" to """{"category":"wine","name":"Wine 10.0-rc2 arm64"}""".toByteArray(),
                "wine-10.0-rc2/bin/wine64" to elfBytes(183),
            ),
            dir,
        )

        assertNull(WineWcpValidator.validate(dir))
        assertEquals(1, WineWcpValidator.locateWineBinaries(dir).size)
    }

    // --- 2. invalid / non-Wine .wcp ---------------------------------------------------

    @Test
    fun `wcp whose profile json reports a non-wine category is rejected`() {
        val dir = tmp.newFolder("non-wine-wcp")
        extractWcp(
            mapOf(
                "profile.json" to """{"category":"dxvk","name":"DXVK 2.4"}""".toByteArray(),
                "x64/d3d11.dll" to "not-wine".toByteArray(),
            ),
            dir,
        )

        val error = WineWcpValidator.validate(dir)
        assertNotNull(error)
        assertTrue(error!!.contains("dxvk", ignoreCase = true))
        assertTrue(error.contains("not Wine"))
    }

    @Test
    fun `wcp with no profile json and no real wine binaries is rejected rather than guessed at`() {
        val dir = tmp.newFolder("random-wcp")
        extractWcp(
            mapOf("readme.txt" to "just some random package".toByteArray()),
            dir,
        )

        val error = WineWcpValidator.validate(dir)
        assertNotNull(error)
        assertTrue(error!!.contains("Wine runtime binary"))
    }

    // --- 3. corrupted .wcp -------------------------------------------------------------

    @Test
    fun `corrupted wcp fails extraction instead of producing a fake install`() {
        val garbage = "definitely not a zstd-compressed tar".toByteArray()
        val target = tmp.newFolder("corrupted-wcp")
        assertThrows(Exception::class.java) {
            ArchiveExtractor.extract(ArchiveKind.WCP, garbage.inputStream(), target)
        }
    }

    // --- 4. path traversal ---------------------------------------------------------------

    @Test
    fun `wcp entry attempting path traversal is rejected before anything is written outside target`() {
        val evilWcp = buildTarZst(
            mapOf(
                "profile.json" to """{"category":"wine"}""".toByteArray(),
                "../../../evil-payload.so" to "malicious".toByteArray(),
            ),
        )
        val target = tmp.newFolder("traversal-wcp")
        assertThrows(SecurityException::class.java) {
            ArchiveExtractor.extract(ArchiveKind.WCP, evilWcp.inputStream(), target)
        }
    }

    // --- 5. missing profile.json ------------------------------------------------------

    @Test
    fun `missing profile json still accepts a package with a genuine wine binary`() {
        val dir = tmp.newFolder("no-profile-wcp")
        extractWcp(
            mapOf("wine-9.0/bin/wineserver" to elfBytes(183)),
            dir,
        )

        assertNull(WcpProfileReader.read(dir))
        assertNull(WineWcpValidator.validate(dir))
    }

    @Test
    fun `missing profile json plus no real wine binary is still rejected`() {
        val dir = tmp.newFolder("no-profile-no-binary-wcp")
        extractWcp(
            mapOf("readme.txt" to "hello".toByteArray()),
            dir,
        )

        assertNull(WcpProfileReader.read(dir))
        assertNotNull(WineWcpValidator.validate(dir))
    }

    // --- 6. valid profile.json ---------------------------------------------------------

    @Test
    fun `valid profile json is parsed into name, category and version code`() {
        val dir = tmp.newFolder("profile-parse-wcp")
        extractWcp(
            mapOf(
                "profile.json" to """{"category":"WINE","name":"Wine 9.0 (arm64)","versionCode":90}""".toByteArray(),
                "bin/wine" to elfBytes(183),
            ),
            dir,
        )

        val profile = WcpProfileReader.read(dir)
        assertNotNull(profile)
        assertEquals("WINE", profile!!.category)
        assertEquals("Wine 9.0 (arm64)", profile.name)
        assertEquals(90, profile.versionCode)
    }

    @Test
    fun `malformed profile json is treated like a missing one, not a hard error`() {
        val dir = tmp.newFolder("malformed-profile-wcp")
        extractWcp(
            mapOf(
                "profile.json" to "{ this is not valid json".toByteArray(),
                "bin/wine64" to elfBytes(183),
            ),
            dir,
        )

        assertNull(WcpProfileReader.read(dir))
        // Falls through to the recursive binary check, which still finds a real wine64 -- a
        // broken profile.json must not turn a perfectly good Wine package into a rejection.
        assertNull(WineWcpValidator.validate(dir))
    }

    // --- 7. already-installed Wine replacement -----------------------------------------

    @Test
    fun `import is refused when wine is already installed and replaceExisting is false`() {
        val runtimeRoot = createTempDirectory().toFile()
        val manager = RuntimeInstallationManager(runtimeRoot)
        writeElf(manager.componentDir(RuntimeComponent.WINE), "wine", machine = 183)

        val staging = tmp.newFolder("new-wine-staging")
        extractWcp(
            mapOf(
                "profile.json" to """{"category":"wine"}""".toByteArray(),
                "bin/wine64" to elfBytes(183),
            ),
            staging,
        )

        val result = manager.commitStagedImport(
            component = RuntimeComponent.WINE,
            staging = staging,
            archiveEntryNames = listOf("profile.json", "bin/wine64"),
            archiveKind = ArchiveKind.WCP,
            versionLabel = "9.5",
            replaceExisting = false,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("already installed"))
        // The rejected re-import must not have touched the existing install.
        assertTrue(manager.status().components.first { it.component == RuntimeComponent.WINE }.installed)
    }

    @Test
    fun `import replaces a previously installed wine version when replaceExisting is true`() {
        val runtimeRoot = createTempDirectory().toFile()
        val manager = RuntimeInstallationManager(runtimeRoot)
        val wineDir = manager.componentDir(RuntimeComponent.WINE)
        writeElf(wineDir, "wine", machine = 183)
        File(wineDir, "old-marker.txt").writeText("old install")

        val staging = tmp.newFolder("replacement-wine-staging")
        extractWcp(
            mapOf(
                "profile.json" to """{"category":"wine","name":"Wine 9.5 arm64"}""".toByteArray(),
                "bin/wine64" to elfBytes(183),
            ),
            staging,
        )

        val result = manager.commitStagedImport(
            component = RuntimeComponent.WINE,
            staging = staging,
            archiveEntryNames = listOf("profile.json", "bin/wine64"),
            archiveKind = ArchiveKind.WCP,
            versionLabel = "9.5",
            replaceExisting = true,
        )

        assertTrue(result.isSuccess)
        val status = manager.status().components.first { it.component == RuntimeComponent.WINE }
        assertTrue(status.installed)
        assertEquals("9.5", status.version)
        // The old install's own files must be gone, not merged with the new one.
        assertFalse(File(wineDir, "old-marker.txt").exists())
    }

    @Test
    fun `a wcp validation failure never leaves wine reported as installed`() {
        val runtimeRoot = createTempDirectory().toFile()
        val manager = RuntimeInstallationManager(runtimeRoot)

        val staging = tmp.newFolder("bad-wine-staging")
        extractWcp(
            mapOf("profile.json" to """{"category":"box64"}""".toByteArray()),
            staging,
        )

        val result = manager.commitStagedImport(
            component = RuntimeComponent.WINE,
            staging = staging,
            archiveEntryNames = listOf("profile.json"),
            archiveKind = ArchiveKind.WCP,
            versionLabel = null,
            replaceExisting = false,
        )

        assertTrue(result.isFailure)
        assertFalse(manager.status().components.first { it.component == RuntimeComponent.WINE }.installed)
    }

    // --- 8. end-to-end install of an x86_64-only Wine build (Box64-translated) --------

    @Test
    fun `an x86_64-only wine wcp is reported as installed with the correct architecture after commit`() {
        // Guards RuntimeInstallationManager.binaryFile()'s fallback search specifically:
        // WineWcpValidator/RuntimePackageValidator accepting x86_64 is not enough on its own
        // if componentStatus() then can't find that same binary and reports "not installed",
        // or if wine64/wineserver never actually get the executable bit set.
        val runtimeRoot = createTempDirectory().toFile()
        val manager = RuntimeInstallationManager(runtimeRoot)

        val staging = tmp.newFolder("x86_64-wine-staging")
        extractWcp(
            mapOf(
                "profile.json" to """{"category":"wine","name":"Wine 10.0-rc2 (x86_64, Box64)"}""".toByteArray(),
                "wine-10.0-rc2/bin/wine64" to elfBytes(62), // EM_X86_64
                "wine-10.0-rc2/bin/wineserver" to elfBytes(62), // EM_X86_64
            ),
            staging,
        )

        val result = manager.commitStagedImport(
            component = RuntimeComponent.WINE,
            staging = staging,
            archiveEntryNames = listOf(
                "profile.json",
                "wine-10.0-rc2/bin/wine64",
                "wine-10.0-rc2/bin/wineserver",
            ),
            archiveKind = ArchiveKind.WCP,
            versionLabel = "10.0-rc2",
            replaceExisting = false,
        )

        assertTrue(result.isSuccess)
        val status = manager.status().components.first { it.component == RuntimeComponent.WINE }
        assertTrue(status.installed)
        assertEquals("x86_64", status.architecture)
        assertNotNull(manager.binaryFile(RuntimeComponent.WINE))

        val wineDir = manager.componentDir(RuntimeComponent.WINE)
        val wine64 = File(wineDir, "wine-10.0-rc2/bin/wine64")
        val wineserver = File(wineDir, "wine-10.0-rc2/bin/wineserver")
        assertTrue("wine64 must exist after commit", wine64.isFile)
        assertTrue("wineserver must exist after commit", wineserver.isFile)
        assertTrue("wine64 must be marked executable after commit", wine64.canExecute())
        assertTrue("wineserver must be marked executable after commit", wineserver.canExecute())
    }

    // --- 9. real-world regression: wine-10.0-rc2-phat.wcp misclassified as DXVK -----------

    /**
     * The exact profile.json structure reported: a real wine-10.0-rc2-phat.wcp declares
     * {"type": "Wine", ...} plus a nested "wine" manifest naming its own binPath/libPath/
     * prefixPack, and its extracted tree is bin/ + lib/ + share/ + prefixPack.txz +
     * profile.json. lib/ legitimately bundles Wine's own built-in Direct3D DLL overrides
     * (d3d9/d3d10/d3d11/dxgi under lib/wine/x86_64-windows/) -- which the OLD generic
     * component-type guess in RuntimeInstallationManager mistook for a DXVK drop-in and
     * rejected with "This looks like a DXVK package, not Wine," even though profile.json
     * unambiguously said Wine and a real wine64/wineserver binary was present under bin/.
     */
    private val phatWineProfileJson =
        """{"type":"Wine","versionName":"10.0-rc2","wine":{"binPath":"bin","libPath":"lib","prefixPack":"prefixPack.txz"}}"""

    private val phatWineEntries: Map<String, ByteArray> = mapOf(
        "profile.json" to phatWineProfileJson.toByteArray(),
        "bin/wine64" to elfBytes(62), // EM_X86_64 -- Box64-translated, the normal real-world case
        "bin/wineserver" to elfBytes(62),
        "lib/wine/x86_64-windows/d3d9.dll" to "fake-dll".toByteArray(),
        "lib/wine/x86_64-windows/d3d10.dll" to "fake-dll".toByteArray(),
        "lib/wine/x86_64-windows/d3d11.dll" to "fake-dll".toByteArray(),
        "lib/wine/x86_64-windows/dxgi.dll" to "fake-dll".toByteArray(),
        "share/wine/wine.desktop" to "placeholder".toByteArray(),
        // Opaque to the extractor -- a nested compressed tar that is never itself unpacked
        // here, exactly like a real .wcp's prefixPack.txz payload.
        "prefixPack.txz" to "opaque-nested-archive-not-further-extracted".toByteArray(),
    )

    @Test
    fun `profile json wine manifest binPath libPath and prefixPack are parsed`() {
        val dir = tmp.newFolder("phat-wine-profile-fields")
        extractXzWcp(phatWineEntries, dir)

        val profile = WcpProfileReader.read(dir)
        assertNotNull(profile)
        assertEquals("Wine", profile!!.category)
        assertEquals("bin", profile.wineBinPath)
        assertEquals("lib", profile.wineLibPath)
        assertEquals("prefixPack.txz", profile.winePrefixPack)
    }

    @Test
    fun `a wine-10 0-rc2-phat style wcp bundling its own d3d and dxgi dlls passes WineWcpValidator`() {
        val dir = tmp.newFolder("phat-wine-wcp-validator")
        extractXzWcp(phatWineEntries, dir)

        assertNull(WineWcpValidator.validate(dir))
        assertEquals(2, WineWcpValidator.locateWineBinaries(dir, WcpProfileReader.read(dir)).size)
    }

    @Test
    fun `a wine-10 0-rc2-phat style wcp is recognized as Wine end-to-end and is never rejected as DXVK`() {
        val staging = tmp.newFolder("phat-wine-wcp-e2e")
        extractXzWcp(phatWineEntries, staging)

        val runtimeRoot = createTempDirectory().toFile()
        val manager = RuntimeInstallationManager(runtimeRoot)
        val result = manager.commitStagedImport(
            component = RuntimeComponent.WINE,
            staging = staging,
            archiveEntryNames = phatWineEntries.keys.toList(),
            archiveKind = ArchiveKind.WCP,
            versionLabel = "10.0-rc2",
            replaceExisting = false,
        )

        assertTrue(
            "a genuine Wine .wcp bundling its own d3d/dxgi dlls must be accepted, not " +
                "rejected as DXVK: ${result.exceptionOrNull()?.message}",
            result.isSuccess,
        )
        val status = manager.status().components.first { it.component == RuntimeComponent.WINE }
        assertTrue(status.installed)
        assertEquals("x86_64", status.architecture)
    }
}
