package com.winlauncher.app.domain.runtime

import com.winlauncher.app.data.db.entity.CpuBackend

/**
 * Single source of truth for "is this CpuBackend actually launchable given what's
 * installed right now". Deliberately NOT logic living on RuntimeInstallationStatus
 * itself -- see that type's own doc -- because which components a launch needs
 * depends on the runtime profile's CpuBackend (Wine+Box64, Wine+Box86, or Wine
 * alone for NATIVE_ARM), which RuntimeInstallationStatus has no knowledge of.
 */
object LaunchPreflight {

    /** Which components [backend] actually needs. Wine is required by every backend. */
    fun requiredComponents(backend: CpuBackend): List<RuntimeComponent> = when (backend) {
        CpuBackend.BOX64 -> listOf(RuntimeComponent.WINE, RuntimeComponent.BOX64)
        CpuBackend.BOX86 -> listOf(RuntimeComponent.WINE, RuntimeComponent.BOX86)
        CpuBackend.NATIVE_ARM -> listOf(RuntimeComponent.WINE)
    }

    /** Which of [required] are not currently installed, in the same order as [required]. */
    fun missingComponents(
        status: RuntimeInstallationStatus,
        required: List<RuntimeComponent>,
    ): List<RuntimeComponent> {
        val installed = status.components.filter { it.installed }.map { it.component }.toSet()
        return required.filterNot { it in installed }
    }

    /** True once every component [backend] needs is installed. */
    fun isLaunchable(status: RuntimeInstallationStatus, backend: CpuBackend): Boolean =
        missingComponents(status, requiredComponents(backend)).isEmpty()

    /**
     * Human-readable preflight report for the Runtime Manager UI: one line per
     * component [backend] actually requires (installed/missing), plus DXVK/VKD3D
     * always shown for visibility even though neither is ever required to launch.
     */
    fun report(status: RuntimeInstallationStatus, backend: CpuBackend): String {
        val byComponent = status.components.associateBy { it.component }
        val required = requiredComponents(backend)
        val lines = mutableListOf<String>()

        lines += if (isLaunchable(status, backend)) {
            "Ready to launch."
        } else {
            "Cannot launch: required components missing."
        }

        required.forEach { component ->
            val installed = byComponent[component]?.installed == true
            lines += "${component.displayName}: ${if (installed) "installed" else "missing"}"
        }

        listOf(RuntimeComponent.DXVK, RuntimeComponent.VKD3D).forEach { component ->
            val installed = byComponent[component]?.installed == true
            lines += "${component.displayName}: ${if (installed) "installed" else "not installed (optional)"}"
        }

        return lines.joinToString("\n")
    }
}
