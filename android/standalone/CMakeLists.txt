# Standalone mode's model runner: llama.cpp plus a small JNI bridge, built into one
# library (libstandalone.so) by tools/build-standalone.sh with the Android NDK.
cmake_minimum_required(VERSION 3.22)
project(standalone CXX C)

set(LLAMA_DIR "" CACHE PATH "llama.cpp source folder (pinned in tools/llama-cpp.version)")
if(NOT LLAMA_DIR)
    message(FATAL_ERROR "Set LLAMA_DIR to the llama.cpp source folder")
endif()

# Only the library itself: no tools, tests, examples, server or HTTP client.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
# The vision part (llama.cpp's mtmd library) for models that can see pictures; no video helper.
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if(ANDROID)
    # Phones from about 2019 on (dot product and int8 matrix instructions). The app checks the
    # phone's CPU before loading this library, so older phones never run it.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)
endif()

add_subdirectory(${LLAMA_DIR} llama)

add_library(runner STATIC runner.cpp)
target_link_libraries(runner PUBLIC llama mtmd)
target_compile_features(runner PRIVATE cxx_std_17)
set_target_properties(runner PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(NOT ANDROID)
    # The desktop check (tools/check-standalone-desktop.sh): the same runner on the PC.
    add_executable(desktop_check desktop_check.cpp)
    target_link_libraries(desktop_check PRIVATE runner)
    target_compile_features(desktop_check PRIVATE cxx_std_17)
    return()
endif()
target_link_libraries(runner PUBLIC log)

add_library(standalone SHARED standalone_jni.cpp)
target_link_libraries(standalone PRIVATE runner log android)
target_compile_features(standalone PRIVATE cxx_std_17)
target_compile_options(standalone PRIVATE -Wno-deprecated-declarations)
# Keep only the JNI entry points visible; drop unused code.
set_target_properties(standalone PROPERTIES CXX_VISIBILITY_PRESET hidden)
target_link_options(standalone PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
