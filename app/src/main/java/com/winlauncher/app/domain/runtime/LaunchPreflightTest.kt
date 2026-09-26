package com.winlauncher.app.domain.runtime

import com.winlauncher.app.data.db.entity.CpuBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchPreflightTest {

    private fun statusOf(installed: Set<RuntimeComponent>): RuntimeInstallationStatus {
        val components = RuntimeComponent.entries.map {
            RuntimeComponentStatus(it, installed = it in installed, version = "1.0", path = "/x")
        }
        return RuntimeInstallationStatus(components, runtimeRootPath = "/runtime")
    }

    @Test
    fun `box64 backend requires wine and box64 only`() {
        assertEquals(
            listOf(RuntimeComponent.WINE, RuntimeComponent.BOX64),
            LaunchPreflight.requiredComponents(CpuBackend.BOX64),
        )
    }

    @Test
    fun `box86 backend requires wine and box86, not box64`() {
        assertEquals(
            listOf(RuntimeComponent.WINE, RuntimeComponent.BOX86),
            LaunchPreflight.requiredComponents(CpuBackend.BOX86),
        )
    }

    @Test
    fun `native arm backend requires only wine`() {
        assertEquals(listOf(RuntimeComponent.WINE), LaunchPreflight.requiredComponents(CpuBackend.NATIVE_ARM))
    }

    @Test
    fun `launchable when all required components for the backend are installed`() {
        val status = statusOf(setOf(RuntimeComponent.WINE, RuntimeComponent.BOX64))
        assertTrue(LaunchPreflight.isLaunchable(status, CpuBackend.BOX64))
    }

    @Test
    fun `box64 backend with nothing installed reports both wine and box64 missing`() {
        val status = statusOf(emptySet())
        assertFalse(LaunchPreflight.isLaunchable(status, CpuBackend.BOX64))
        assertEquals(
            listOf(RuntimeComponent.WINE, RuntimeComponent.BOX64),
            LaunchPreflight.missingComponents(status, LaunchPreflight.requiredComponents(CpuBackend.BOX64)),
        )
    }

    @Test
    fun `not launchable when box64 missing even if wine installed`() {
        val status = statusOf(setOf(RuntimeComponent.WINE))
        assertFalse(LaunchPreflight.isLaunchable(status, CpuBackend.BOX64))
        assertEquals(listOf(RuntimeComponent.BOX64), LaunchPreflight.missingComponents(status, LaunchPreflight.requiredComponents(CpuBackend.BOX64)))
    }

    @Test
    fun `box64 backend launchable once wine and box64 are both installed`() {
        val status = statusOf(setOf(RuntimeComponent.WINE, RuntimeComponent.BOX64))
        assertTrue(LaunchPreflight.isLaunchable(status, CpuBackend.BOX64))
    }

    @Test
    fun `box64-only install does not satisfy a box86 profile`() {
        val status = statusOf(setOf(RuntimeComponent.WINE, RuntimeComponent.BOX64))
        assertFalse(LaunchPreflight.isLaunchable(status, CpuBackend.BOX86))
    }

    @Test
    fun `box86 backend with nothing installed reports both wine and box86 missing`() {
        val status = statusOf(emptySet())
        assertFalse(LaunchPreflight.isLaunchable(status, CpuBackend.BOX86))
        assertEquals(
            listOf(RuntimeComponent.WINE, RuntimeComponent.BOX86),
            LaunchPreflight.missingComponents(status, LaunchPreflight.requiredComponents(CpuBackend.BOX86)),
        )
    }

    @Test
    fun `box86 backend launchable once wine and box86 are both installed`() {
        val status = statusOf(setOf(RuntimeComponent.WINE, RuntimeComponent.BOX86))
        assertTrue(LaunchPreflight.isLaunchable(status, CpuBackend.BOX86))
    }

    @Test
    fun `native arm not launchable when wine missing`() {
        val status = statusOf(emptySet())
        assertFalse(LaunchPreflight.isLaunchable(status, CpuBackend.NATIVE_ARM))
        assertEquals(listOf(RuntimeComponent.WINE), LaunchPreflight.missingComponents(status, LaunchPreflight.requiredComponents(CpuBackend.NATIVE_ARM)))
    }

    @Test
    fun `native arm is launchable with wine alone, no box64 or box86 needed`() {
        val status = statusOf(setOf(RuntimeComponent.WINE))
        assertTrue(LaunchPreflight.isLaunchable(status, CpuBackend.NATIVE_ARM))
    }

    @Test
    fun `native arm stays launchable and never reports box64 missing even with other components absent`() {
        // Wine installed, everything else (including Box64) explicitly absent.
        val status = statusOf(setOf(RuntimeComponent.WINE))
        assertTrue(LaunchPreflight.isLaunchable(status, CpuBackend.NATIVE_ARM))
        assertTrue(LaunchPreflight.missingComponents(status, LaunchPreflight.requiredComponents(CpuBackend.NATIVE_ARM)).isEmpty())
        val report = LaunchPreflight.report(status, CpuBackend.NATIVE_ARM)
        assertFalse(report.contains("Box64: missing"))
        assertFalse(report.contains("Box86: missing"))
    }

    @Test
    fun `same install state yields different requirements when the profile backend changes from box64 to native_arm`() {
        // Simulates switching a game's assigned runtime profile from a BOX64 profile to a
        // NATIVE_ARM one without touching installed components: Box64 missing should stop
        // blocking launch the moment the active backend no longer needs it.
        val status = statusOf(setOf(RuntimeComponent.WINE))
        assertFalse(LaunchPreflight.isLaunchable(status, CpuBackend.BOX64))
        assertTrue(LaunchPreflight.isLaunchable(status, CpuBackend.NATIVE_ARM))
    }

    @Test
    fun `same install state yields different requirements when the profile backend changes from native_arm to box86`() {
        val status = statusOf(setOf(RuntimeComponent.WINE))
        assertTrue(LaunchPreflight.isLaunchable(status, CpuBackend.NATIVE_ARM))
        assertFalse(LaunchPreflight.isLaunchable(status, CpuBackend.BOX86))
        assertEquals(listOf(RuntimeComponent.BOX86), LaunchPreflight.missingComponents(status, LaunchPreflight.requiredComponents(CpuBackend.BOX86)))
    }

    @Test
    fun `report names exactly which required component is missing`() {
        val status = statusOf(setOf(RuntimeComponent.BOX64)) // Wine missing
        val report = LaunchPreflight.report(status, CpuBackend.BOX64)
        assertTrue(report.contains("Wine: missing"))
        assertTrue(report.contains("Box64: installed"))
        assertTrue(report.startsWith("Cannot launch:"))
    }

    @Test
    fun `report omits box86 line for a box64 profile`() {
        val status = statusOf(setOf(RuntimeComponent.WINE, RuntimeComponent.BOX64))
        val report = LaunchPreflight.report(status, CpuBackend.BOX64)
        assertFalse(report.contains("Box86"))
    }

    @Test
    fun `report omits box64 line for a box86 profile`() {
        val status = statusOf(setOf(RuntimeComponent.WINE, RuntimeComponent.BOX86))
        val report = LaunchPreflight.report(status, CpuBackend.BOX86)
        assertFalse(report.contains("Box64"))
    }

    @Test
    fun `report omits both box64 and box86 lines for a native_arm profile`() {
        val status = statusOf(setOf(RuntimeComponent.WINE))
        val report = LaunchPreflight.report(status, CpuBackend.NATIVE_ARM)
        assertFalse(report.contains("Box64"))
        assertFalse(report.contains("Box86"))
    }

    @Test
    fun `report always shows dxvk and vkd3d for visibility even though they are optional`() {
        val status = statusOf(setOf(RuntimeComponent.WINE, RuntimeComponent.BOX64))
        val report = LaunchPreflight.report(status, CpuBackend.BOX64)
        assertTrue(report.contains("DXVK"))
        assertTrue(report.contains("VKD3D"))
    }
}
