package com.winlauncher.app.domain.runtime

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Classifies a file on disk by its REAL ELF machine type, read from the ELF header
 * itself -- never guessed from a file name or extension. This is what lets
 * RuntimeInstallationManager/RuntimePackageValidator (and, for .wcp imports,
 * WineWcpValidator) tell a genuine arm64-v8a Android build apart from a
 * same-named desktop x86_64 Linux binary -- which looks identical by name but
 * cannot run on this device -- and tell any binary apart from a non-ELF file
 * (text, config, a .dll, etc.).
 */
object ElfInspector {

    enum class Arch {
        ARM64, ARM32, X86, X86_64, OTHER_ELF, NOT_ELF;

        /** Human-readable label surfaced in RuntimeComponentStatus.architecture. */
        fun label(): String = when (this) {
            ARM64 -> "arm64-v8a"
            ARM32 -> "armeabi-v7a"
            X86 -> "x86"
            X86_64 -> "x86_64"
            OTHER_ELF -> "unsupported ELF machine type"
            NOT_ELF -> "not an ELF binary"
        }

        /** Box64 packages bundle x86/x86_64 guest libraries alongside the ARM64 binary. */
        fun isX86Guest(): Boolean = this == X86 || this == X86_64
    }

    private const val EI_DATA_LSB: Byte = 1

    // e_machine values from the ELF spec -- only the ones this project cares about.
    private const val EM_386 = 3
    private const val EM_ARM = 40
    private const val EM_X86_64 = 62
    private const val EM_AARCH64 = 183

    /** Reads just enough of [file]'s header to classify it; never loads the whole file. */
    fun detect(file: File): Arch {
        if (!file.isFile || file.length() < 20) return Arch.NOT_ELF
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(20)
                raf.readFully(header)
                val isElf = header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
                if (!isElf) return Arch.NOT_ELF

                val littleEndian = header[5] == EI_DATA_LSB
                val machine = if (littleEndian) {
                    (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
                } else {
                    (header[19].toInt() and 0xFF) or ((header[18].toInt() and 0xFF) shl 8)
                }
                when (machine) {
                    EM_AARCH64 -> Arch.ARM64
                    EM_ARM -> Arch.ARM32
                    EM_386 -> Arch.X86
                    EM_X86_64 -> Arch.X86_64
                    else -> Arch.OTHER_ELF
                }
            }
        } catch (e: IOException) {
            Arch.NOT_ELF
        }
    }
}
