package com.winlauncher.app.domain.graphics

/**
 * Thin JNI boundary. Returns a raw JSON string built in gpu_detector.cpp;
 * all interpretation happens in Kotlin (see GpuDetector.kt / MaliProfile.kt).
 */
object GpuNative {
    init {
        System.loadLibrary("gpudetect")
    }

    external fun nativeDetectGpu(): String
}
