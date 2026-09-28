package com.winlauncher.app.domain.process

import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException

/**
 * Thrown by the native loader (see native_exec.c) when the exec itself
 * failed -- as opposed to some other, unrelated Kotlin-side error. The
 * message always includes the raw errno and strerror text, and notes which
 * mechanism was used (`[memfd+execveat]` on API 29+, `[execve]` below that),
 * so RealRuntimeEngine's catch block can give an accurate diagnostic instead
 * of a generic one. A single-String constructor is required here: JNI's
 * `ThrowNew` only ever calls a exception class's `(String)` constructor.
 */
class NativeExecException(message: String) : IOException(message)

/**
 * Flattens an environment map into `"KEY=VALUE"` form for the native layer.
 * Deliberately a top-level function, not a member of [NativeProcessLauncher]
 * or its companion: that class's companion loads the native library the
 * moment any of its members is first touched, which would make even a plain
 * JVM unit test of this pure string logic throw `UnsatisfiedLinkError`. See
 * NativeProcessLauncherTest.
 */
internal fun buildNativeEnvArray(environment: Map<String, String>): Array<String> =
    environment.map { (key, value) -> "$key=$value" }.toTypedArray()

/**
 * JNI entry point for exec'ing an ARM64 ELF that was imported into
 * app-private storage at runtime (e.g. via RuntimeInstallationManager's SAF
 * import) -- something a plain `ProcessBuilder`/`Runtime.exec()` generally
 * cannot do on Android 10+ (API 29+), which enforces W^X on app-private
 * files. See native_exec.c's header comment for the full mechanism
 * (memfd_create + execveat) and exactly what was verified against bionic
 * (not assumed from glibc) before writing it.
 *
 * Deliberately separate from [ProcessLauncher]: DummyRuntimeEngine's
 * `/system/bin/sh` script lives at an already-exec-permitted system path and
 * was never subject to this restriction, so it has no reason to route
 * through native code, and keeping it on the old, simpler path means this
 * change carries zero risk for it.
 */
class NativeProcessLauncher {

    private external fun nativeExec(
        elfPath: String,
        argv: Array<String>,
        envp: Array<String>,
        workingDirectory: String,
        sdkInt: Int,
    ): IntArray

    private external fun nativeWaitFor(pid: Int): Int

    private external fun nativeKill(pid: Int, signal: Int)

    /**
     * Execs [elfPath] (which must equal [argv]'s first element -- argv[0] is
     * conventionally the program's own path) with the given arguments and
     * environment, in [workingDirectory]. [argv]/[envp] are passed to the
     * kernel as real arrays, never a shell string, so there is no shell or
     * injection involved regardless of what a game's launch arguments or
     * environment variables contain.
     */
    fun launch(
        elfPath: String,
        argv: List<String>,
        environment: Map<String, String>,
        workingDirectory: File,
    ): NativeProcessMonitor {
        require(argv.isNotEmpty()) { "argv must not be empty" }

        val result = nativeExec(
            elfPath,
            argv.toTypedArray(),
            buildNativeEnvArray(environment),
            workingDirectory.absolutePath,
            Build.VERSION.SDK_INT,
        )
        val pid = result[0]
        val stdout = ParcelFileDescriptor.adoptFd(result[1])
        val stderr = ParcelFileDescriptor.adoptFd(result[2])
        return NativeProcessMonitor(pid, stdout, stderr, this)
    }

    internal fun waitFor(pid: Int): Int = nativeWaitFor(pid)

    /** SIGTERM -- matches the simplicity of ProcessMonitor.stop()'s plain `process.destroy()`. */
    internal fun kill(pid: Int) = nativeKill(pid, 15)

    companion object {
        init {
            System.loadLibrary("execshim")
        }
    }
}
