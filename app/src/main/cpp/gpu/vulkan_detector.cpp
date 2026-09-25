#include "gpu_common.h"
#include <vulkan/vulkan.h>
#include <cstdio>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "VulkanDetector"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::string formatHex(uint32_t v) {
    char buf[16];
    snprintf(buf, sizeof(buf), "0x%04x", v);
    return std::string(buf);
}

std::string formatVersion(uint32_t v) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%u.%u.%u",
              VK_VERSION_MAJOR(v), VK_VERSION_MINOR(v), VK_VERSION_PATCH(v));
    return std::string(buf);
}

} // namespace

bool detect_vulkan(GpuCapabilitiesRaw &out) {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "WinLauncherGpuProbe";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "none";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult result = vkCreateInstance(&instanceInfo, nullptr, &instance);
    if (result != VK_SUCCESS) {
        out.vulkanAvailable = false;
        out.vulkanError = "vkCreateInstance failed, VkResult=" + std::to_string(result);
        LOGE("%s", out.vulkanError.c_str());
        return false;
    }

    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
    if (deviceCount == 0) {
        out.vulkanAvailable = false;
        out.vulkanError = "No Vulkan-capable physical devices reported";
        vkDestroyInstance(instance, nullptr);
        return false;
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());
    // MVP: probe the first reported device. Multi-GPU Android devices are rare;
    // a device picker can be added later without changing this interface.
    VkPhysicalDevice physicalDevice = devices[0];

    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(physicalDevice, &props);

    VkPhysicalDeviceFeatures features{};
    vkGetPhysicalDeviceFeatures(physicalDevice, &features);

    out.vkVendorId = formatHex(props.vendorID);
    out.vkDeviceName = std::string(props.deviceName);
    out.vkApiVersion = formatVersion(props.apiVersion);
    out.vkDriverVersion = formatVersion(props.driverVersion);
    out.vkSamplerAnisotropy = features.samplerAnisotropy == VK_TRUE;
    out.vkShaderClipDistance = features.shaderClipDistance == VK_TRUE;
    out.vkMaxDescriptorSetSamplers = static_cast<int32_t>(props.limits.maxDescriptorSetSamplers);

    uint32_t extCount = 0;
    vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr, &extCount, nullptr);
    if (extCount > 0) {
        std::vector<VkExtensionProperties> extensions(extCount);
        vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr, &extCount, extensions.data());
        out.vkExtensions.reserve(extCount);
        for (const auto &ext : extensions) {
            std::string name(ext.extensionName);
            out.vkExtensions.push_back(name);
            if (name == "VK_KHR_swapchain") {
                out.vkHasSwapchainExt = true;
            }
        }
    }

    vkDestroyInstance(instance, nullptr);
    out.vulkanAvailable = true;
    return true;
}
