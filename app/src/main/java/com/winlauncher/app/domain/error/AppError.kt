package com.winlauncher.app.domain.error

sealed class AppError(val userMessage: String) {
    data class InvalidExecutable(val detail: String) :
        AppError("That doesn't look like a valid Windows executable: $detail")

    data class UriPermissionLost(val detail: String) :
        AppError("Lost access to the selected file/folder. Please re-select it: $detail")

    data class VulkanUnavailable(val detail: String) :
        AppError("Vulkan isn't available on this device: $detail")

    data class GlesUnavailable(val detail: String) :
        AppError("OpenGL ES isn't available on this device: $detail")

    data class UnsupportedGraphicsConfig(val detail: String) :
        AppError("This game's graphics requirements aren't supported here: $detail")

    data class ProcessLaunchFailed(val detail: String) :
        AppError("Couldn't start the runtime process: $detail")

    object MissingRuntime :
        AppError("This game has no runtime profile assigned. Create or select one first.")

    object RuntimeBinariesMissing :
        AppError("Wine runtime is not installed. Install/download a compatible runtime first.")

    data class PermissionDenied(val detail: String) :
        AppError("Permission denied: $detail")

    data class Unknown(val detail: String) :
        AppError("Something went wrong: $detail")
}
