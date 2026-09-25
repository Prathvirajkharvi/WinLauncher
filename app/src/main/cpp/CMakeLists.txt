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
