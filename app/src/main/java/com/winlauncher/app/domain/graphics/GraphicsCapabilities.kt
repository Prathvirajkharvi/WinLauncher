package com.winlauncher.app.domain.graphics

enum class GpuVendor { ARM, QUALCOMM, IMAGINATION, OTHER, UNKNOWN }

data class VulkanCapabilities(
    val available: Boolean,
    val error: String?,
    val vendorIdHex: String,
    val deviceName: String,
    val apiVersion: String,
    val driverVersion: String,
    val extensions: List<String>,
    val hasSwapchainExt: Boolean,
    val samplerAnisotropy: Boolean,
    val shaderClipDistance: Boolean,
    val maxDescriptorSetSamplers: Int,
)

data class OpenGlEsCapabilities(
    val available: Boolean,
    val error: String?,
    val vendor: String,
    val renderer: String,
    val version: String,
    val extensions: List<String>,
    val maxTextureSize: Int,
)

/**
 * The single object every downstream decision (GraphicsManager, UI badges,
 * compatibility DB lookups) should read from. Built once per app session by
 * GpuDetector and cached -- GPU capabilities don't change mid-session.
 */
data class GraphicsCapabilities(
    val vendor: GpuVendor,
    val vulkan: VulkanCapabilities,
    val gles: OpenGlEsCapabilities,
    val maliTier: MaliCapabilityTier,
) {
    val vulkanSupported: Boolean get() = vulkan.available
    val glesSupported: Boolean get() = gles.available

    /** DXVK needs Vulkan + swapchain presentation + basic sampler features. */
    val dxvkPossible: Boolean
        get() = vulkan.available && vulkan.hasSwapchainExt && vulkan.samplerAnisotropy

    /**
     * VKD3D (DX12) support is far less predictable on mobile Vulkan drivers than
     * DXVK. We only claim "possible" when Vulkan 1.1+ and swapchain are present;
     * everything else is reported UNKNOWN and must be verified per-device.
     */
    val vkd3dPossible: Tristate
        get() = when {
            !vulkan.available -> Tristate.NO
            !vulkan.hasSwapchainExt -> Tristate.NO
            vulkan.apiVersion.startsWith("1.0") -> Tristate.NO
            else -> Tristate.UNKNOWN
        }
}

enum class Tristate { YES, NO, UNKNOWN }
