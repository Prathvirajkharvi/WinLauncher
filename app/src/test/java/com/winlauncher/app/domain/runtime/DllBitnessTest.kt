package com.winlauncher.app.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DllBitnessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `dll under an x64 directory is classified as 64-bit`() {
        val root = tmp.newFolder("dxvk")
        val dll = File(root, "dxvk-2.4/x64/d3d11.dll").apply { parentFile.mkdirs(); writeText("x") }
        assertEquals(DllBitness.BIT64, classifyDllBitness(dll, root))
    }

    @Test
    fun `dxvk-style x32 directory is classified as 32-bit`() {
        val root = tmp.newFolder("dxvk")
        val dll = File(root, "dxvk-2.4/x32/d3d11.dll").apply { parentFile.mkdirs(); writeText("x") }
        assertEquals(DllBitness.BIT32, classifyDllBitness(dll, root))
    }

    @Test
    fun `vkd3d-proton-style x86 directory is classified as 32-bit`() {
        val root = tmp.newFolder("vkd3d")
        val dll = File(root, "vkd3d-proton-2.13/x86/d3d12.dll").apply { parentFile.mkdirs(); writeText("x") }
        assertEquals(DllBitness.BIT32, classifyDllBitness(dll, root))
    }

    @Test
    fun `a flat dll with no arch subdirectory is unspecified`() {
        val root = tmp.newFolder("flat")
        val dll = File(root, "d3d11.dll").apply { writeText("x") }
        assertEquals(DllBitness.UNSPECIFIED, classifyDllBitness(dll, root))
    }
}
