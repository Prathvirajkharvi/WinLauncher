package com.winlauncher.app.domain.process

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers only [buildNativeEnvArray] -- the one piece of NativeProcessLauncher
 * that's pure Kotlin logic. Everything else in that class is an `external`
 * JNI declaration or a thin wrapper around one, which needs a device/emulator
 * (see the new androidTest source set) rather than a plain JVM unit test.
 * `buildNativeEnvArray` is deliberately a top-level function rather than a
 * member of NativeProcessLauncher's companion specifically so this test can
 * exercise it without ever touching that companion's `System.loadLibrary`
 * call -- see its doc.
 */
class NativeProcessLauncherTest {

    @Test
    fun `flattens an environment map into KEY=VALUE strings`() {
        val result = buildNativeEnvArray(mapOf("WINEPREFIX" to "/data/x", "WINEARCH" to "win64"))
        assertEquals(2, result.size)
        assertEquals(setOf("WINEPREFIX=/data/x", "WINEARCH=win64"), result.toSet())
    }

    @Test
    fun `empty map flattens to an empty array`() {
        assertArrayEquals(emptyArray<String>(), buildNativeEnvArray(emptyMap()))
    }

    @Test
    fun `a value that itself contains an equals sign is preserved after the first`() {
        // e.g. a DXVK config string like "VKD3D_CONFIG=dxr=0" as the VALUE half
        // of one env entry -- splitting/parsing that back out is native_exec.c's
        // problem only in reverse (it never needs to, argv/envp are passed as
        // real arrays, not re-parsed), this just confirms building doesn't
        // mangle it.
        val result = buildNativeEnvArray(mapOf("SOME_VAR" to "a=b=c"))
        assertEquals(listOf("SOME_VAR=a=b=c"), result.toList())
    }
}
