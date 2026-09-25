#pragma once
#include <string>
#include <vector>

// Raw, ungrouped facts pulled straight from the Vulkan and EGL/GLES APIs.
// No interpretation (Mali tiering, "supported/unsupported" verdicts) happens
// here on purpose -- that logic lives in Kotlin (GraphicsCapabilities.kt /
// MaliProfile.kt) so it stays easy to tune without touching native code.
struct GpuCapabilitiesRaw {
    // Vulkan
    bool vulkanAvailable = false;
    std::string vulkanError;
    std::string vkVendorId;          // hex string, e.g. "0x13b5" (ARM)
    std::string vkDeviceName;        // e.g. "Mali-G715"
    std::string vkApiVersion;        // e.g. "1.3.0"
    std::string vkDriverVersion;
    std::vector<std::string> vkExtensions;
    bool vkHasSwapchainExt = false;
    bool vkSamplerAnisotropy = false;
    bool vkShaderClipDistance = false;
    int32_t vkMaxDescriptorSetSamplers = 0;

    // OpenGL ES (via a throwaway EGL pbuffer context)
    bool glesAvailable = false;
    std::string glesError;
    std::string glVendor;
    std::string glRenderer;
    std::string glVersion;
    std::vector<std::string> glExtensions;
    int32_t glMaxTextureSize = 0;
};

bool detect_vulkan(GpuCapabilitiesRaw &out);
bool detect_gles(GpuCapabilitiesRaw &out);
