package com.winlauncher.app.domain.graphics

import org.json.JSONArray
import org.json.JSONObject

object GpuDetector {

    @Volatile
    private var cached: GraphicsCapabilities? = null

    /**
     * Runs the real native Vulkan/EGL probe the first time it's called and caches
     * the result for the process lifetime. Safe to call from a background thread;
     * callers on the UI thread should collect this from a ViewModel, not directly.
     */
    fun detect(forceRefresh: Boolean = false): GraphicsCapabilities {
        cached?.let { if (!forceRefresh) return it }

        val json = JSONObject(GpuNative.nativeDetectGpu())

        val vulkan = VulkanCapabilities(
            available = json.getBoolean("vulkanAvailable"),
            error = json.optString("vulkanError").ifBlank { null },
            vendorIdHex = json.optString("vkVendorId"),
            deviceName = json.optString("vkDeviceName"),
            apiVersion = json.optString("vkApiVersion"),
            driverVersion = json.optString("vkDriverVersion"),
            extensions = json.optJSONArray("vkExtensions").toStringList(),
            hasSwapchainExt = json.optBoolean("vkHasSwapchainExt"),
            samplerAnisotropy = json.optBoolean("vkSamplerAnisotropy"),
            shaderClipDistance = json.optBoolean("vkShaderClipDistance"),
            maxDescriptorSetSamplers = json.optInt("vkMaxDescriptorSetSamplers"),
        )

        val gles = OpenGlEsCapabilities(
            available = json.getBoolean("glesAvailable"),
            error = json.optString("glesError").ifBlank { null },
            vendor = json.optString("glVendor"),
            renderer = json.optString("glRenderer"),
            version = json.optString("glVersion"),
            extensions = json.optJSONArray("glExtensions").toStringList(),
            maxTextureSize = json.optInt("glMaxTextureSize"),
        )

        // Vendor comes from GL_VENDOR/GL_RENDERER strings, never inferred from the
        // Android device model -- an "ARM" substring or a "Mali" renderer name.
        val vendor = when {
            gles.vendor.contains("ARM", ignoreCase = true) ||
                gles.renderer.contains("Mali", ignoreCase = true) -> GpuVendor.ARM
            gles.vendor.contains("Qualcomm", ignoreCase = true) ||
                gles.renderer.contains("Adreno", ignoreCase = true) -> GpuVendor.QUALCOMM
            gles.vendor.contains("Imagination", ignoreCase = true) ||
                gles.renderer.contains("PowerVR", ignoreCase = true) -> GpuVendor.IMAGINATION
            gles.vendor.isBlank() && gles.renderer.isBlank() -> GpuVendor.UNKNOWN
            else -> GpuVendor.OTHER
        }

        val tier = if (MaliProfile.isMali(vendor, gles.renderer)) {
            MaliProfile.computeTier(vulkan)
        } else {
            MaliCapabilityTier.UNKNOWN
        }

        return GraphicsCapabilities(vendor, vulkan, gles, tier).also { cached = it }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { getString(it) }
    }
}
