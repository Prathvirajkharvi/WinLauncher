#include "gpu_common.h"
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <sstream>
#include <android/log.h>

#define LOG_TAG "GlesDetector"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
std::vector<std::string> splitExtensions(const char *raw) {
    std::vector<std::string> result;
    if (!raw) return result;
    std::istringstream stream(raw);
    std::string token;
    while (stream >> token) {
        result.push_back(token);
    }
    return result;
}
} // namespace

// Detection happens on a 1x1 offscreen pbuffer surface. This process/thread must
// not already hold a current EGL context on this display, or eglMakeCurrent here
// will simply reassign it -- callers should invoke this before any other GL usage.
bool detect_gles(GpuCapabilitiesRaw &out) {
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) {
        out.glesAvailable = false;
        out.glesError = "eglGetDisplay returned EGL_NO_DISPLAY";
        return false;
    }

    EGLint majorVer, minorVer;
    if (!eglInitialize(display, &majorVer, &minorVer)) {
        out.glesAvailable = false;
        out.glesError = "eglInitialize failed";
        return false;
    }

    const EGLint configAttribs[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };

    EGLConfig config;
    EGLint numConfigs = 0;
    if (!eglChooseConfig(display, configAttribs, &config, 1, &numConfigs) || numConfigs == 0) {
        out.glesAvailable = false;
        out.glesError = "eglChooseConfig found no matching config";
        eglTerminate(display);
        return false;
    }

    const EGLint pbufferAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    EGLSurface surface = eglCreatePbufferSurface(display, config, pbufferAttribs);
    if (surface == EGL_NO_SURFACE) {
        out.glesAvailable = false;
        out.glesError = "eglCreatePbufferSurface failed";
        eglTerminate(display);
        return false;
    }

    const EGLint contextAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
    if (context == EGL_NO_CONTEXT) {
        out.glesAvailable = false;
        out.glesError = "eglCreateContext failed";
        eglDestroySurface(display, surface);
        eglTerminate(display);
        return false;
    }

    if (!eglMakeCurrent(display, surface, surface, context)) {
        out.glesAvailable = false;
        out.glesError = "eglMakeCurrent failed";
        eglDestroyContext(display, context);
        eglDestroySurface(display, surface);
        eglTerminate(display);
        return false;
    }

    const char *vendor = reinterpret_cast<const char *>(glGetString(GL_VENDOR));
    const char *renderer = reinterpret_cast<const char *>(glGetString(GL_RENDERER));
    const char *version = reinterpret_cast<const char *>(glGetString(GL_VERSION));
    const char *extensions = reinterpret_cast<const char *>(glGetString(GL_EXTENSIONS));

    out.glVendor = vendor ? vendor : "";
    out.glRenderer = renderer ? renderer : "";
    out.glVersion = version ? version : "";
    out.glExtensions = splitExtensions(extensions);

    GLint maxTextureSize = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTextureSize);
    out.glMaxTextureSize = static_cast<int32_t>(maxTextureSize);

    out.glesAvailable = true;

    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(display, context);
    eglDestroySurface(display, surface);
    eglTerminate(display);
    return true;
}
