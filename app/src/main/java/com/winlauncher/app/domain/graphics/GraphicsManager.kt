package com.winlauncher.app.domain.graphics

/** What DirectX generation the game is declared to use (set when adding the game). */
enum class DirectXTarget { DX9_10_11, DX12, NONE_OR_OPENGL }

enum class GraphicsBackendPreference { AUTO, FORCE_VULKAN, FORCE_GLES }

sealed class GraphicsResolution(open val reason: String) {
    data class VulkanDxvk(override val reason: String) : GraphicsResolution(reason)
    data class VulkanVkd3d(override val reason: String) : GraphicsResolution(reason)
    data class GlesFallback(override val reason: String) : GraphicsResolution(reason)
    data class Unsupported(override val reason: String) : GraphicsResolution(reason)
}

object GraphicsManager {

    fun resolve(
        caps: GraphicsCapabilities,
        directXTarget: DirectXTarget,
        preference: GraphicsBackendPreference,
    ): GraphicsResolution {

        if (preference == GraphicsBackendPreference.FORCE_GLES) {
            return if (caps.glesSupported) {
                GraphicsResolution.GlesFallback("User forced OpenGL ES backend")
            } else {
                GraphicsResolution.Unsupported("OpenGL ES forced but unavailable on this device")
            }
        }

        return when (directXTarget) {
            DirectXTarget.DX9_10_11 -> resolveDxvk(caps, preference)
            DirectXTarget.DX12 -> resolveVkd3d(caps, preference)
            DirectXTarget.NONE_OR_OPENGL -> resolveNoDirectX(caps)
        }
    }

    private fun resolveDxvk(
        caps: GraphicsCapabilities,
        preference: GraphicsBackendPreference,
    ): GraphicsResolution {
        if (!caps.vulkanSupported) {
            return fallbackOrUnsupported(caps, "Vulkan unavailable: ${caps.vulkan.error ?: "unknown reason"}")
        }
        if (!caps.dxvkPossible) {
            val reason = when {
                !caps.vulkan.hasSwapchainExt -> "Missing required VK_KHR_swapchain extension"
                !caps.vulkan.samplerAnisotropy -> "Missing required samplerAnisotropy feature"
                else -> "DXVK prerequisites not met"
            }
            return fallbackOrUnsupported(caps, reason)
        }
        if (preference == GraphicsBackendPreference.FORCE_GLES) {
            return GraphicsResolution.GlesFallback("User forced OpenGL ES over available DXVK path")
        }
        return GraphicsResolution.VulkanDxvk(
            "Vulkan ${caps.vulkan.apiVersion} + swapchain + sampler anisotropy available " +
                "(Mali tier: ${caps.maliTier})"
        )
    }

    private fun resolveVkd3d(
        caps: GraphicsCapabilities,
        preference: GraphicsBackendPreference,
    ): GraphicsResolution {
        if (!caps.vulkanSupported) {
            return GraphicsResolution.Unsupported(
                "DX12 requires Vulkan and none is available: ${caps.vulkan.error ?: "unknown reason"}"
            )
        }
        return when (caps.vkd3dPossible) {
            Tristate.NO -> GraphicsResolution.Unsupported(
                "Device Vulkan capabilities are below VKD3D's minimum requirements"
            )
            Tristate.UNKNOWN -> GraphicsResolution.VulkanVkd3d(
                "Vulkan ${caps.vulkan.apiVersion} meets minimum bar, but VKD3D/DX12 result is " +
                    "UNKNOWN on this GPU -- treat as experimental until tested"
            )
            Tristate.YES -> GraphicsResolution.VulkanVkd3d(
                "VKD3D prerequisites confirmed on this device"
            )
        }
    }

    private fun resolveNoDirectX(caps: GraphicsCapabilities): GraphicsResolution {
        return if (caps.vulkanSupported) {
            GraphicsResolution.VulkanDxvk("Native Vulkan/OpenGL title, no DXVK/VKD3D translation needed")
        } else if (caps.glesSupported) {
            GraphicsResolution.GlesFallback("Vulkan unavailable, falling back to OpenGL ES")
        } else {
            GraphicsResolution.Unsupported("Neither Vulkan nor OpenGL ES is usable on this device")
        }
    }

    private fun fallbackOrUnsupported(caps: GraphicsCapabilities, reason: String): GraphicsResolution {
        return if (caps.glesSupported) {
            GraphicsResolution.GlesFallback("$reason -- falling back to OpenGL ES (expect reduced compatibility)")
        } else {
            GraphicsResolution.Unsupported(reason)
        }
    }
}
