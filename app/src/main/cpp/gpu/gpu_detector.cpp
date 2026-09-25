#include <jni.h>
#include <sstream>
#include "gpu_common.h"

// Native detection intentionally returns a JSON string rather than a hand-built
// JNI object graph. It keeps this file small and avoids fragile FindClass/
// GetFieldID bookkeeping every time a field is added -- GpuDetector.kt owns
// parsing and mapping into typed Kotlin data classes.

namespace {

std::string jsonEscape(const std::string &in) {
    std::string out;
    out.reserve(in.size());
    for (char c : in) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            default: out += c;
        }
    }
    return out;
}

std::string jsonArray(const std::vector<std::string> &items) {
    std::ostringstream ss;
    ss << "[";
    for (size_t i = 0; i < items.size(); ++i) {
        if (i > 0) ss << ",";
        ss << "\"" << jsonEscape(items[i]) << "\"";
    }
    ss << "]";
    return ss.str();
}

std::string buildJson(const GpuCapabilitiesRaw &caps) {
    std::ostringstream ss;
    ss << "{";
    ss << "\"vulkanAvailable\":" << (caps.vulkanAvailable ? "true" : "false") << ",";
    ss << "\"vulkanError\":\"" << jsonEscape(caps.vulkanError) << "\",";
    ss << "\"vkVendorId\":\"" << jsonEscape(caps.vkVendorId) << "\",";
    ss << "\"vkDeviceName\":\"" << jsonEscape(caps.vkDeviceName) << "\",";
    ss << "\"vkApiVersion\":\"" << jsonEscape(caps.vkApiVersion) << "\",";
    ss << "\"vkDriverVersion\":\"" << jsonEscape(caps.vkDriverVersion) << "\",";
    ss << "\"vkExtensions\":" << jsonArray(caps.vkExtensions) << ",";
    ss << "\"vkHasSwapchainExt\":" << (caps.vkHasSwapchainExt ? "true" : "false") << ",";
    ss << "\"vkSamplerAnisotropy\":" << (caps.vkSamplerAnisotropy ? "true" : "false") << ",";
    ss << "\"vkShaderClipDistance\":" << (caps.vkShaderClipDistance ? "true" : "false") << ",";
    ss << "\"vkMaxDescriptorSetSamplers\":" << caps.vkMaxDescriptorSetSamplers << ",";

    ss << "\"glesAvailable\":" << (caps.glesAvailable ? "true" : "false") << ",";
    ss << "\"glesError\":\"" << jsonEscape(caps.glesError) << "\",";
    ss << "\"glVendor\":\"" << jsonEscape(caps.glVendor) << "\",";
    ss << "\"glRenderer\":\"" << jsonEscape(caps.glRenderer) << "\",";
    ss << "\"glVersion\":\"" << jsonEscape(caps.glVersion) << "\",";
    ss << "\"glExtensions\":" << jsonArray(caps.glExtensions) << ",";
    ss << "\"glMaxTextureSize\":" << caps.glMaxTextureSize;
    ss << "}";
    return ss.str();
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_winlauncher_app_domain_graphics_GpuNative_nativeDetectGpu(JNIEnv *env, jobject /* this */) {
    GpuCapabilitiesRaw caps;
    detect_vulkan(caps);   // failure is recorded in caps.vulkanError, not fatal
    detect_gles(caps);     // failure is recorded in caps.glesError, not fatal

    std::string json = buildJson(caps);
    return env->NewStringUTF(json.c_str());
}
