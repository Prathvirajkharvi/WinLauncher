package com.winlauncher.app.domain.graphics

enum class MaliCapabilityTier { BASIC, MODERN, HIGH_END, UNKNOWN }

/**
 * Turnip is a Qualcomm Adreno Vulkan driver and must never be treated as a Mali
 * solution -- this object only ever looks at ARM/Mali signals.
 */
object MaliProfile {

    fun isMali(vendor: GpuVendor, renderer: String): Boolean {
        return vendor == GpuVendor.ARM || renderer.contains("Mali", ignoreCase = true)
    }

    /**
     * Tiering is derived from actual queried capabilities (extensions, features,
     * descriptor limits) -- never from a hardcoded "Mali-Gxx = tier Y" table,
     * since driver quality varies by Android version and OEM even on the same chip.
     */
    fun computeTier(vulkan: VulkanCapabilities): MaliCapabilityTier {
        if (!vulkan.available) return MaliCapabilityTier.UNKNOWN

        val hasCoreFeatures = vulkan.hasSwapchainExt && vulkan.samplerAnisotropy
        val hasHighEndSignal = vulkan.maxDescriptorSetSamplers >= 96 &&
            vulkan.shaderClipDistance &&
            !vulkan.apiVersion.startsWith("1.0")

        return when {
            !hasCoreFeatures -> MaliCapabilityTier.BASIC
            hasHighEndSignal -> MaliCapabilityTier.HIGH_END
            else -> MaliCapabilityTier.MODERN
        }
    }
}
