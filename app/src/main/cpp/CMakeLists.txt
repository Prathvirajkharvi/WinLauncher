cmake_minimum_required(VERSION 3.22.1)
project("winlauncher_native")

add_library(
    gpudetect
    SHARED
    gpu/gpu_detector.cpp
    gpu/vulkan_detector.cpp
    gpu/gles_detector.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(glesv2-lib GLESv2)
find_library(vulkan-lib vulkan)

target_include_directories(gpudetect PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/gpu)

target_link_libraries(
    gpudetect
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    ${glesv2-lib}
    ${vulkan-lib}
)

target_compile_options(gpudetect PRIVATE -Wall -Wextra)

# Box64/Wine execution shim (see domain/process/NativeProcessLauncher.kt): a
# minimal memfd_create+execveat loader that lets an SAF-imported ARM64 ELF be
# exec'd despite Android 10+'s W^X restriction on app-private files. Kept as
# its own target, independent of gpudetect above, so the two builds can never
# affect each other.
add_library(
    execshim
    SHARED
    exec/native_exec.c
)

target_include_directories(execshim PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/exec)

target_link_libraries(
    execshim
    ${log-lib}
)

target_compile_options(execshim PRIVATE -Wall -Wextra)
