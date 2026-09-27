package com.winlauncher.app.domain.runtime

import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimePackageValidatorTest {

    private fun writeElf(dir: File, name: String, machine: Int): File {
        val header = ByteArray(20)
        header[0] = 0x7F.toByte(); header[1] = 'E'.code.toByte(); header[2] = 'L'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = 2; header[5] = 1
        header[18] = (machine and 0xFF).toByte()
        header[19] = ((machine shr 8) and 0xFF).toByte()
        val file = File(dir, name)
        file.writeBytes(header)
        return file
    }

    // --- guessComponentType ---

    @Test
    fun `guesses box64 from entry names`() {
        assertEquals(RuntimeComponent.BOX64, RuntimePackageValidator.guessComponentType(listOf("box64-0.3.1/box64")))
    }

    @Test
    fun `guesses box86 from entry names`() {
        assertEquals(RuntimeComponent.BOX86, RuntimePackageValidator.guessComponentType(listOf("box86")))
    }

    @Test
    fun `guesses dxvk from characteristic dll set`() {
        val entries = listOf("x64/d3d11.dll", "x64/dxgi.dll", "x32/d3d9.dll")
        assertEquals(RuntimeComponent.DXVK, RuntimePackageValidator.guessComponentType(entries))
    }

    @Test
    fun `guesses vkd3d from entry names`() {
        assertEquals(RuntimeComponent.VKD3D, RuntimePackageValidator.guessComponentType(listOf("vkd3d-proton/d3d12.dll")))
    }

    @Test
    fun `guesses wine from entry names`() {
        assertEquals(RuntimeComponent.WINE, RuntimePackageValidator.guessComponentType(listOf("bin/wine", "bin/wineserver")))
    }

    @Test
    fun `unrecognized entries guess null rather than a wrong component`() {
        assertNull(RuntimePackageValidator.guessComponentType(listOf("readme.txt", "license.md")))
    }

    @Test
    fun `a full wine build bundling its own built-in d3d and dxgi dlls is still guessed as wine, not dxvk`() {
        // Regression for the real-world "wine-10.0-rc2-phat.wcp misclassified as DXVK" report:
        // a genuine, complete Wine build's lib/ tree legitimately ships its OWN built-in
        // Direct3D DLL overrides (Wine's "fake DLL" implementations) using the exact same file
        // name substrings DXVK's real release ships as its own top-level payload. The real Wine
        // binary name (bin/wine64) must win the guess, not lose to the DLL name match.
        val entries = listOf(
            "profile.json",
            "bin/wine64",
            "bin/wineserver",
            "lib/wine/x86_64-windows/d3d9.dll",
            "lib/wine/x86_64-windows/d3d10.dll",
            "lib/wine/x86_64-windows/d3d11.dll",
            "lib/wine/x86_64-windows/dxgi.dll",
            "share/wine/wine.desktop",
            "prefixPack.txz",
        )
        assertEquals(RuntimeComponent.WINE, RuntimePackageValidator.guessComponentType(entries))
    }

    @Test
    fun `a full wine build vendoring its own libvkd3d is still guessed as wine, not vkd3d`() {
        val entries = listOf("bin/wine64", "lib/wine/x86_64-unix/libvkd3d-1.so")
        assertEquals(RuntimeComponent.WINE, RuntimePackageValidator.guessComponentType(entries))
    }

    @Test
    fun `a genuine dxvk package with no wine binary present is still guessed as dxvk`() {
        // The reordering above must not weaken real DXVK detection -- only a package that
        // ALSO contains an actual wine binary name should ever prefer WINE over DXVK/VKD3D.
        val entries = listOf("x64/d3d11.dll", "x64/dxgi.dll", "x32/d3d9.dll")
        assertEquals(RuntimeComponent.DXVK, RuntimePackageValidator.guessComponentType(entries))
    }

    // --- scan + validate ---

    @Test
    fun `package with only x86_64 elf is rejected for an executable component`() {
        val dir = createTempDirectory().toFile()
        writeElf(dir, "box64", machine = 62) // EM_X86_64 -- a desktop Linux build
        val findings = RuntimePackageValidator.scan(dir)
        val error = RuntimePackageValidator.validate(RuntimeComponent.BOX64, findings)
        assertNotNull(error)
        assertTrue(error!!.contains("ARM64"))
    }

    @Test
    fun `package with a genuine arm64 binary is accepted for an executable component`() {
        val dir = createTempDirectory().toFile()
        writeElf(dir, "box64", machine = 183) // EM_AARCH64
        val findings = RuntimePackageValidator.scan(dir)
        assertNull(RuntimePackageValidator.validate(RuntimeComponent.BOX64, findings))
    }

    @Test
    fun `box64 package may bundle arm64 binary plus x86_64 guest libraries and still be accepted`() {
        val dir = createTempDirectory().toFile()
        writeElf(dir, "box64", machine = 183)
        writeElf(dir, "libc.so.6", machine = 62)
        writeElf(dir, "libm.so.6", machine = 62)
        val findings = RuntimePackageValidator.scan(dir)
        assertNull(RuntimePackageValidator.validate(RuntimeComponent.BOX64, findings))
        assertEquals(1, findings.armExecutables.size)
        assertEquals(2, findings.x86Guests.size)
    }

    @Test
    fun `dll-only package is accepted for a non-executable component`() {
        val dir = createTempDirectory().toFile()
        File(dir, "d3d11.dll").writeBytes(ByteArray(10))
        val findings = RuntimePackageValidator.scan(dir)
        assertNull(RuntimePackageValidator.validate(RuntimeComponent.DXVK, findings))
    }

    @Test
    fun `empty package is rejected`() {
        val dir = createTempDirectory().toFile()
        val findings = RuntimePackageValidator.scan(dir)
        assertNotNull(RuntimePackageValidator.validate(RuntimeComponent.WINE, findings))
    }

    @Test
    fun `non-executable component with no dlls is rejected`() {
        val dir = createTempDirectory().toFile()
        File(dir, "readme.txt").writeText("hello")
        val findings = RuntimePackageValidator.scan(dir)
        val error = RuntimePackageValidator.validate(RuntimeComponent.DXVK, findings)
        assertNotNull(error)
        assertTrue(error!!.contains(".dll"))
    }

    @Test
    fun `nested dxvk dlls under x64 and x32 are found recursively and accepted`() {
        val dir = createTempDirectory().toFile()
        File(dir, "dxvk-3.1.1/x64").apply { mkdirs() }
        File(dir, "dxvk-3.1.1/x32").apply { mkdirs() }
        listOf("d3d9", "d3d10core", "d3d11", "dxgi").forEach {
            File(dir, "dxvk-3.1.1/x64/$it.dll").writeBytes(ByteArray(4))
            File(dir, "dxvk-3.1.1/x32/$it.dll").writeBytes(ByteArray(4))
        }
        val findings = RuntimePackageValidator.scan(dir)
        assertEquals(8, findings.dllFiles.size)
        assertNull(RuntimePackageValidator.validate(RuntimeComponent.DXVK, findings))
    }

    @Test
    fun `nested vkd3d-proton dlls under x64 and x86 are found recursively and accepted`() {
        val dir = createTempDirectory().toFile()
        File(dir, "vkd3d-proton-3.0.1/x64").apply { mkdirs() }
        File(dir, "vkd3d-proton-3.0.1/x86").apply { mkdirs() }
        listOf("d3d12", "d3d12core").forEach {
            File(dir, "vkd3d-proton-3.0.1/x64/$it.dll").writeBytes(ByteArray(4))
            File(dir, "vkd3d-proton-3.0.1/x86/$it.dll").writeBytes(ByteArray(4))
        }
        val findings = RuntimePackageValidator.scan(dir)
        assertEquals(4, findings.dllFiles.size)
        assertNull(RuntimePackageValidator.validate(RuntimeComponent.VKD3D, findings))
    }

    @Test
    fun `a dll that does not match what dxvk actually ships is rejected, not just any dll`() {
        val dir = createTempDirectory().toFile()
        File(dir, "some-unrelated-thing.dll").writeBytes(ByteArray(4))
        val findings = RuntimePackageValidator.scan(dir)
        val error = RuntimePackageValidator.validate(RuntimeComponent.DXVK, findings)
        assertNotNull(error)
        assertTrue(error!!.contains("DXVK"))
    }

    @Test
    fun `a dll that does not match what vkd3d-proton actually ships is rejected, not just any dll`() {
        val dir = createTempDirectory().toFile()
        File(dir, "some-unrelated-thing.dll").writeBytes(ByteArray(4))
        val findings = RuntimePackageValidator.scan(dir)
        val error = RuntimePackageValidator.validate(RuntimeComponent.VKD3D, findings)
        assertNotNull(error)
        assertTrue(error!!.contains("VKD3D"))
    }

    @Test
    fun `a wine or box64 arm64 executable is never accepted as a dxvk or vkd3d package`() {
        val dir = createTempDirectory().toFile()
        writeElf(dir, "box64", machine = 183) // EM_AARCH64 -- a real, valid ARM64 binary
        val findings = RuntimePackageValidator.scan(dir)
        // It's a perfectly good executable -- just not a DLL set, so it must not satisfy
        // a DXVK or VKD3D-Proton import no matter how "installed"-looking the file is.
        assertNotNull(RuntimePackageValidator.validate(RuntimeComponent.DXVK, findings))
        assertNotNull(RuntimePackageValidator.validate(RuntimeComponent.VKD3D, findings))
        assertTrue(findings.dllFiles.isEmpty())
    }

    @Test
    fun `unrelated non-dll non-elf files alongside real dxvk dlls do not block acceptance`() {
        val dir = createTempDirectory().toFile()
        File(dir, "dxvk-3.1.1").mkdirs()
        File(dir, "dxvk-3.1.1/x64").mkdirs()
        File(dir, "dxvk-3.1.1/x64/d3d11.dll").writeBytes(ByteArray(4))
        File(dir, "dxvk-3.1.1/setup_dxvk.sh").writeText("#!/bin/sh\necho hi\n")
        File(dir, "dxvk-3.1.1/README.md").writeText("# DXVK")

        val findings = RuntimePackageValidator.scan(dir)
        assertEquals(3, findings.totalFiles)
        assertEquals(1, findings.dllFiles.size)
        assertNull(RuntimePackageValidator.validate(RuntimeComponent.DXVK, findings))
    }

    // --- component-specific architecture rule (Wine's x86_64 guest binaries vs Box64/Box86) ---

    @Test
    fun `box64 with only an x86_64 binary is still rejected, even though wine now accepts x86_64`() {
        // Regression guard for the Wine-specific relaxation below: Box64/Box86 run DIRECTLY on
        // the device CPU and must stay arm64-v8a-only. Only RuntimeComponent.WINE gets the
        // host-vs-guest exception -- this must not have been weakened globally.
        val dir = createTempDirectory().toFile()
        writeElf(dir, "box64", machine = 62) // EM_X86_64 -- would run under itself, which is nonsense
        val findings = RuntimePackageValidator.scan(dir)

        val box64Error = RuntimePackageValidator.validate(RuntimeComponent.BOX64, findings)
        assertNotNull(box64Error)
        assertTrue(box64Error!!.contains("ARM64"))

        val box86Error = RuntimePackageValidator.validate(RuntimeComponent.BOX86, findings)
        assertNotNull(box86Error)
        assertTrue(box86Error!!.contains("ARM64"))
    }

    @Test
    fun `wine with only an x86_64 binary is accepted, unlike box64 with the same finding`() {
        val dir = createTempDirectory().toFile()
        writeElf(dir, "wine64", machine = 62) // EM_X86_64 -- Box64-translated, this is the normal case
        val findings = RuntimePackageValidator.scan(dir)
        assertNull(RuntimePackageValidator.validate(RuntimeComponent.WINE, findings))
    }

    @Test
    fun `wine with a genuine arm64-native binary is still accepted too`() {
        val dir = createTempDirectory().toFile()
        writeElf(dir, "wine64", machine = 183) // EM_AARCH64 -- CpuBackend.NATIVE_ARM case
        val findings = RuntimePackageValidator.scan(dir)
        assertNull(RuntimePackageValidator.validate(RuntimeComponent.WINE, findings))
    }
}
