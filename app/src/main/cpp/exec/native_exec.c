/*
 * Box64/Wine execution shim.
 *
 * THE PROBLEM: Android 10+ (API 29+) denies `execute_no_trans` on
 * `app_data_file` for the `untrusted_app` SELinux domain -- a binary an app
 * writes to its own private storage at runtime (exactly what
 * RuntimeInstallationManager's SAF import produces) cannot be exec()'d, even
 * after chmod +x. That is the exact "error=13, Permission denied" this file
 * exists to work around. It does NOT deny plain `execute` (mmap/dlopen) of
 * app-private files -- only spawning a *new process image* from an
 * app-private path is blocked.
 *
 * THE FIX: memfd_create() returns an anonymous, unlinked, tmpfs-backed fd
 * with no on-disk `app_data_file` path for that policy to key off. Copying
 * the already-staged, already-validated ELF's bytes into that fd and then
 * exec'ing *the fd* (via execveat(fd, "", ..., AT_EMPTY_PATH), i.e. what
 * fexecve() does under the hood) sidesteps the path-based denial entirely.
 *
 * SCOPE: this file only ever launches the ONE top-level ARM64 process a
 * runtime profile needs (Box64, or Wine directly for a NATIVE_ARM backend).
 * Everything Box64 itself subsequently loads -- the x86_64 Wine build, the
 * Windows PE .exe -- is read by Box64/Wine as data and JIT-translated into
 * anonymous executable memory Box64 allocates itself; none of that ever asks
 * the OS to exec() an on-disk file, so none of it needs this treatment.
 *
 * VERIFIED (against AOSP bionic's own sources, see the URLs in each comment
 * below -- this is deliberately NOT assumed from glibc behavior):
 *   - memfd_create() is a real bionic libc wrapper only since API 30
 *     (__INTRODUCED_IN(30), libc/include/sys/mman.h). Below that -- which
 *     includes API 29, where the W^X restriction we're working around
 *     already applies -- there is no libc wrapper, only the raw syscall.
 *   - fexecve() IS a real bionic libc wrapper since API 28
 *     (__INTRODUCED_IN(28), libc/include/unistd.h).
 *   - Both of the above are ordinary versioned libc symbols: calling them
 *     directly from code that also has to run on this project's minSdk (26)
 *     risks a weak-symbol/runtime-linker failure on devices below their
 *     __INTRODUCED_IN level. To avoid that class of bug entirely, this file
 *     never calls either wrapper -- it goes straight to syscall() for both
 *     memfd_create and execveat, which is available at every API level this
 *     project supports, and branches on the Android version (passed in from
 *     Kotlin, which already knows Build.VERSION.SDK_INT trivially) instead
 *     of relying on the dynamic linker to resolve a newer symbol.
 *   - __NR_memfd_create == 279 and __NR_execveat == 281 for arm64: verified
 *     directly against the exact kernel uapi header bionic itself vendors
 *     and regenerates its <sys/syscall.h> from --
 *     platform/external/kernel-headers's original/uapi/asm-generic/unistd.h
 *     -- which matches upstream torvalds/linux
 *     include/uapi/asm-generic/unistd.h. arm64 has no architecture-specific
 *     syscall table of its own (unlike 32-bit arm, which numbers these
 *     differently) -- it uses this generic table directly, which is why a
 *     single pair of numbers is correct for the arm64-v8a-only ABI this
 *     project targets. The #ifndef/#error guards below fail the BUILD
 *     loudly rather than silently falling back to these numbers if some
 *     future NDK sysroot ever stops defining them.
 *   - AT_EMPTY_PATH == 0x1000: present in the NDK's bundled linux/fcntl.h
 *     since at least the platform-21 headers, long before this project's
 *     minSdk (26).
 */

/* pipe2() is unconditionally exposed by bionic, but defining this before any
 * system header keeps the file portable to a plain glibc syntax check too
 * (see the host-side check this project's implementation notes describe). */
#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

/* Kernel ABI constants that are considerably older than this project's
 * minSdk but are guarded defensively in case an unusual sysroot omits them
 * from <fcntl.h>. These are stable, versioned kernel UAPI values, not
 * something this project is guessing at. */
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif
#ifndef AT_EMPTY_PATH
#define AT_EMPTY_PATH 0x1000
#endif
#ifndef F_ADD_SEALS
#define F_ADD_SEALS 1033
#endif
#ifndef F_SEAL_SHRINK
#define F_SEAL_SHRINK 0x0002
#endif
#ifndef F_SEAL_GROW
#define F_SEAL_GROW 0x0004
#endif
#ifndef F_SEAL_WRITE
#define F_SEAL_WRITE 0x0008
#endif

/* See the big comment above: verified against bionic's own vendored kernel
 * uapi headers for arm64, NOT hardcoded on assumption. Fail the build,
 * loudly, rather than ever silently falling back to a guessed number. */
#ifndef __NR_memfd_create
#error "This NDK's <sys/syscall.h> does not define __NR_memfd_create for arm64 (expected 279 per bionic's own kernel uapi headers). Do not hardcode a fallback -- check the NDK version being used."
#endif
#ifndef __NR_execveat
#error "This NDK's <sys/syscall.h> does not define __NR_execveat for arm64 (expected 281 per bionic's own kernel uapi headers). Do not hardcode a fallback -- check the NDK version being used."
#endif

static void throwNativeExecException(JNIEnv *env, const char *message) {
    jclass cls = (*env)->FindClass(env, "com/winlauncher/app/domain/process/NativeExecException");
    if (cls == NULL) {
        return; /* FindClass already threw NoClassDefFoundError if this happens */
    }
    (*env)->ThrowNew(env, cls, message);
    (*env)->DeleteLocalRef(env, cls);
}

JNIEXPORT jintArray JNICALL
Java_com_winlauncher_app_domain_process_NativeProcessLauncher_nativeExec(
        JNIEnv *env, jobject thiz,
        jstring jElfPath, jobjectArray jArgv, jobjectArray jEnvp,
        jstring jWorkingDir, jint sdkInt) {
    (void) thiz;

    /* Every resource this function might need to clean up on an error path
     * is declared and given a safe "not yet acquired" sentinel value up
     * front, and released by a single cleanup path at the bottom. This
     * avoids any ambiguity about jumping over a C variable declaration. */
    jintArray resultOut = NULL;
    char errMsg[384];
    errMsg[0] = '\0';

    const char *elfPath = NULL;
    const char *workingDir = NULL;
    jsize argc = 0;
    jsize envc = 0;
    char **argv = NULL;
    char **envp = NULL;
    jstring *argvRefs = NULL;
    jstring *envpRefs = NULL;
    int execFd = -1;
    int usingMemfd = 0;
    int outPipe[2] = {-1, -1};
    int errStreamPipe[2] = {-1, -1};
    int errPipe[2] = {-1, -1};
    pid_t pid = -1;

    elfPath = (*env)->GetStringUTFChars(env, jElfPath, NULL);
    workingDir = (*env)->GetStringUTFChars(env, jWorkingDir, NULL);
    if (elfPath == NULL || workingDir == NULL) {
        throwNativeExecException(env, "Out of memory reading exec path or working directory");
        goto cleanup;
    }

    argc = (*env)->GetArrayLength(env, jArgv);
    envc = (*env)->GetArrayLength(env, jEnvp);

    argv = (char **) calloc((size_t) argc + 1, sizeof(char *));
    envp = (char **) calloc((size_t) envc + 1, sizeof(char *));
    argvRefs = (jstring *) calloc((size_t) (argc > 0 ? argc : 1), sizeof(jstring));
    envpRefs = (jstring *) calloc((size_t) (envc > 0 ? envc : 1), sizeof(jstring));
    if (argv == NULL || envp == NULL || argvRefs == NULL || envpRefs == NULL) {
        throwNativeExecException(env, "Out of memory building argv/envp");
        goto cleanup;
    }

    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, jArgv, i);
        argvRefs[i] = s;
        argv[i] = (char *) (*env)->GetStringUTFChars(env, s, NULL);
    }
    for (jsize i = 0; i < envc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, jEnvp, i);
        envpRefs[i] = s;
        envp[i] = (char *) (*env)->GetStringUTFChars(env, s, NULL);
    }

    /* --- Stage the fd that will actually be exec'd ------------------------- */
    if (sdkInt >= 29) {
        int elfFd = open(elfPath, O_RDONLY | O_CLOEXEC);
        if (elfFd < 0) {
            snprintf(errMsg, sizeof(errMsg), "Could not open %s to stage for exec: %s (errno=%d)",
                      elfPath, strerror(errno), errno);
            throwNativeExecException(env, errMsg);
            goto cleanup;
        }

        int memfd = (int) syscall(__NR_memfd_create, "winlauncher-exec", MFD_CLOEXEC);
        if (memfd < 0) {
            int savedErrno = errno;
            close(elfFd);
            snprintf(errMsg, sizeof(errMsg), "memfd_create failed: %s (errno=%d)", strerror(savedErrno), savedErrno);
            throwNativeExecException(env, errMsg);
            goto cleanup;
        }

        char buf[65536];
        ssize_t n;
        int copyFailed = 0;
        while ((n = read(elfFd, buf, sizeof(buf))) > 0) {
            ssize_t off = 0;
            while (off < n) {
                ssize_t w = write(memfd, buf + off, (size_t) (n - off));
                if (w < 0) {
                    if (errno == EINTR) continue;
                    copyFailed = 1;
                    break;
                }
                off += w;
            }
            if (copyFailed) break;
        }
        int copyErrno = errno;
        close(elfFd);
        if (n < 0 || copyFailed) {
            close(memfd);
            snprintf(errMsg, sizeof(errMsg), "Copying %s into memfd failed: %s (errno=%d)",
                      elfPath, strerror(copyErrno), copyErrno);
            throwNativeExecException(env, errMsg);
            goto cleanup;
        }

        /* Best-effort hardening: seal the copy read-only. Not fatal if the
         * running kernel refuses -- it's defense in depth, not correctness. */
        fcntl(memfd, F_ADD_SEALS, F_SEAL_WRITE | F_SEAL_SHRINK | F_SEAL_GROW);

        execFd = memfd;
        usingMemfd = 1;
    }

    /* --- pipes: stdout, stderr, and a child->parent exec-error channel ---- */
    if (pipe2(outPipe, O_CLOEXEC) != 0 || pipe2(errStreamPipe, O_CLOEXEC) != 0 || pipe2(errPipe, O_CLOEXEC) != 0) {
        snprintf(errMsg, sizeof(errMsg), "pipe2 failed: %s (errno=%d)", strerror(errno), errno);
        throwNativeExecException(env, errMsg);
        goto cleanup;
    }

    pid = fork();
    if (pid < 0) {
        int savedErrno = errno;
        snprintf(errMsg, sizeof(errMsg), "fork failed: %s (errno=%d)", strerror(savedErrno), savedErrno);
        throwNativeExecException(env, errMsg);
        goto cleanup;
    }

    if (pid == 0) {
        /* ---- CHILD ----------------------------------------------------
         * From here to exec/_exit: async-signal-safe calls ONLY. This
         * process was just fork()'d from a multi-threaded ART process --
         * no malloc, no JNI, no logging. Any of those could deadlock on a
         * lock some other thread held at the instant of fork(). */
        close(errPipe[0]);
        close(outPipe[0]);
        close(errStreamPipe[0]);
        dup2(outPipe[1], STDOUT_FILENO);
        dup2(errStreamPipe[1], STDERR_FILENO);
        close(outPipe[1]);
        close(errStreamPipe[1]);
        chdir(workingDir);

        if (usingMemfd) {
            syscall(__NR_execveat, execFd, "", argv, envp, AT_EMPTY_PATH);
        } else {
            /* Below API 29 the W^X restriction doesn't exist yet -- exec the
             * real on-disk path directly, no memfd needed. */
            execve(elfPath, argv, envp);
        }
        /* Only reached if exec failed. */
        int execErrno = errno;
        ssize_t ignored = write(errPipe[1], &execErrno, sizeof(execErrno));
        (void) ignored;
        _exit(126);
    }

    /* ---- PARENT ---------------------------------------------------------- */
    close(errPipe[1]);
    errPipe[1] = -1;
    close(outPipe[1]);
    outPipe[1] = -1;
    close(errStreamPipe[1]);
    errStreamPipe[1] = -1;
    if (usingMemfd) {
        close(execFd);
        execFd = -1;
    }

    {
        int childErrno = 0;
        ssize_t got = read(errPipe[0], &childErrno, sizeof(childErrno));
        close(errPipe[0]);
        errPipe[0] = -1;

        if (got == (ssize_t) sizeof(childErrno)) {
            int status;
            waitpid(pid, &status, 0); /* reap the zombie */
            snprintf(errMsg, sizeof(errMsg), "exec of %s failed: %s (errno=%d)%s",
                      elfPath, strerror(childErrno), childErrno,
                      usingMemfd ? " [memfd+execveat]" : " [execve]");
            throwNativeExecException(env, errMsg);
            goto cleanup;
        }
    }

    /* Success: outPipe[0]/errStreamPipe[0] are handed to Kotlin (which
     * adopts them via ParcelFileDescriptor.adoptFd) -- do not close them
     * here. Set the sentinels to -1 so the cleanup path below leaves them
     * alone. */
    resultOut = (*env)->NewIntArray(env, 3);
    if (resultOut != NULL) {
        jint values[3] = {(jint) pid, (jint) outPipe[0], (jint) errStreamPipe[0]};
        (*env)->SetIntArrayRegion(env, resultOut, 0, 3, values);
    }
    outPipe[0] = -1;
    errStreamPipe[0] = -1;

cleanup:
    if (argv != NULL) {
        for (jsize i = 0; i < argc; i++) {
            if (argv[i] != NULL) (*env)->ReleaseStringUTFChars(env, argvRefs[i], argv[i]);
        }
    }
    if (envp != NULL) {
        for (jsize i = 0; i < envc; i++) {
            if (envp[i] != NULL) (*env)->ReleaseStringUTFChars(env, envpRefs[i], envp[i]);
        }
    }
    free(argv);
    free(envp);
    free(argvRefs);
    free(envpRefs);
    if (elfPath != NULL) (*env)->ReleaseStringUTFChars(env, jElfPath, elfPath);
    if (workingDir != NULL) (*env)->ReleaseStringUTFChars(env, jWorkingDir, workingDir);
    if (execFd >= 0) close(execFd);
    if (outPipe[0] >= 0) close(outPipe[0]);
    if (outPipe[1] >= 0) close(outPipe[1]);
    if (errStreamPipe[0] >= 0) close(errStreamPipe[0]);
    if (errStreamPipe[1] >= 0) close(errStreamPipe[1]);
    if (errPipe[0] >= 0) close(errPipe[0]);
    if (errPipe[1] >= 0) close(errPipe[1]);

    return resultOut;
}

JNIEXPORT jint JNICALL
Java_com_winlauncher_app_domain_process_NativeProcessLauncher_nativeWaitFor(
        JNIEnv *env, jobject thiz, jint pid) {
    (void) env;
    (void) thiz;
    int status = 0;
    pid_t result;
    do {
        result = waitpid((pid_t) pid, &status, 0);
    } while (result < 0 && errno == EINTR);

    if (result < 0) {
        return -1;
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return -1;
}

JNIEXPORT void JNICALL
Java_com_winlauncher_app_domain_process_NativeProcessLauncher_nativeKill(
        JNIEnv *env, jobject thiz, jint pid, jint signalNumber) {
    (void) env;
    (void) thiz;
    kill((pid_t) pid, (int) signalNumber);
}
