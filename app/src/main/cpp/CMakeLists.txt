cmake_minimum_required(VERSION 3.22.1)
project("yolovaim")

set(QNN_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lib)
set(QNN_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/include)

include_directories(${QNN_INCLUDE_DIR})

# Helper macro for library
macro(add_qnn_lib basename)
    set(libname "${basename}.so")
    add_library(${basename} SHARED IMPORTED)
    set_target_properties(${basename} PROPERTIES IMPORTED_LOCATION ${QNN_LIB_DIR}/${libname})
endmacro()

# QNN HTP Libraries (Hexagon DSP/NPU acceleration)
add_qnn_lib(libQnnHtp)
add_qnn_lib(libQnnSystem)
add_qnn_lib(libQnnTFLiteDelegate)

# TFLite JNI library - use local copy
add_library(tensorflowlite_jni SHARED IMPORTED)
set_target_properties(tensorflowlite_jni PROPERTIES
    IMPORTED_LOCATION ${QNN_LIB_DIR}/libtensorflowlite_jni.so)

# TFLite GPU delegate library
add_library(tensorflowlite_gpu_jni SHARED IMPORTED)
set_target_properties(tensorflowlite_gpu_jni PROPERTIES
    IMPORTED_LOCATION ${QNN_LIB_DIR}/libtensorflowlite_gpu_jni.so)

# Compile yolovaim (JNI bridge + inference engines)
add_library(yolovaim SHARED
    src/inference/yolovaim.cpp
    src/inference/ncnn_engine.cpp
    src/inference/litert_engine.cpp
    src/inference/qnn_engine.cpp
    src/inference/mediatek_engine.cpp
    src/inference/lite_rt_api.cpp
)

# Shared touch core library
add_library(touch_core STATIC src/injection/touch_core.cpp)
target_link_libraries(touch_core android log)

# Compile uinput injector (JNI wrapper over touch_core)
add_library(uinput_inject SHARED src/injection/uinput_inject.cpp)
target_link_libraries(uinput_inject touch_core android log)

# Root daemon (standalone binary for su execution, packaged as .so for Gradle)
# The HUD module is compiled in: it talks to SurfaceFlinger through runtime
# dlopen/dlsym, so there is no link-time dependency on libgui.
# The stealth module (KPM kernel injection) is compiled in too: the KPM syscall
# channel requires a root caller (inputprobe.c checks current_uid() == 0), so
# touchc must live in this process, not the app process. The pairing salt lives
# directly in src/injection/stealth/touch_pairing.h (see the decision note
# there) — nothing to configure at build time.
add_executable(root_daemon
    src/daemon/root_daemon.cpp
    src/hud/hud_surface.cpp
    src/hud/hud_renderer.cpp
    src/injection/stealth/touchc.cpp
    src/injection/stealth/stealth_backend.cpp)
target_include_directories(root_daemon PRIVATE src/injection src/injection/stealth src/hud)
target_link_libraries(root_daemon touch_core android log dl)
set_target_properties(root_daemon PROPERTIES
    OUTPUT_NAME "libroot_daemon.so"
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

# InputManager evdev reader (for InputManager injection mode)
add_library(inputmgr_core STATIC src/injection/inputmgr_core.cpp)
target_link_libraries(inputmgr_core android log)

add_library(inputmgr_inject SHARED src/injection/inputmgr_jni.cpp)
target_link_libraries(inputmgr_inject inputmgr_core android log)

target_include_directories(yolovaim PRIVATE
        ${QNN_INCLUDE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/include/qnn)

# Link TFLite and QNN libraries
target_link_libraries(yolovaim
        libQnnHtp
        libQnnSystem
        libQnnTFLiteDelegate
        tensorflowlite_jni
        tensorflowlite_gpu_jni
        android
        log
        dl)

# NCNN configuration (fastest inference)
set(NCNN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ncnn/arm64-v8a)
set(NCNN_INCLUDE_DIR ${NCNN_DIR}/include)
set(NCNN_LIB_DIR ${NCNN_DIR}/lib)

# Import NCNN static library
add_library(ncnn STATIC IMPORTED)
set_target_properties(ncnn PROPERTIES
    IMPORTED_LOCATION ${NCNN_LIB_DIR}/libncnn.a)

# Import glslang and SPIRV for NCNN Vulkan support
add_library(glslang STATIC IMPORTED)
set_target_properties(glslang PROPERTIES
    IMPORTED_LOCATION ${NCNN_LIB_DIR}/libglslang.a)

add_library(SPIRV STATIC IMPORTED)
set_target_properties(SPIRV PROPERTIES
    IMPORTED_LOCATION ${NCNN_LIB_DIR}/libSPIRV.a)

add_library(glslang-default-resource-limits STATIC IMPORTED)
set_target_properties(glslang-default-resource-limits PROPERTIES
    IMPORTED_LOCATION ${NCNN_LIB_DIR}/libglslang-default-resource-limits.a)

add_library(MachineIndependent STATIC IMPORTED)
set_target_properties(MachineIndependent PROPERTIES
    IMPORTED_LOCATION ${NCNN_LIB_DIR}/libMachineIndependent.a)

add_library(OSDependent STATIC IMPORTED)
set_target_properties(OSDependent PROPERTIES
    IMPORTED_LOCATION ${NCNN_LIB_DIR}/libOSDependent.a)

add_library(OGLCompiler STATIC IMPORTED)
set_target_properties(OGLCompiler PROPERTIES
    IMPORTED_LOCATION ${NCNN_LIB_DIR}/libOGLCompiler.a)

add_library(GenericCodeGen STATIC IMPORTED)
set_target_properties(GenericCodeGen PROPERTIES
    IMPORTED_LOCATION ${NCNN_LIB_DIR}/libGenericCodeGen.a)

# OpenMP support for NCNN
find_package(OpenMP REQUIRED)

# Vulkan support for NCNN GPU acceleration
find_package(Vulkan REQUIRED)

# Link NCNN with all dependencies (including Vulkan for GPU acceleration)
target_link_libraries(yolovaim
        ncnn
        glslang
        SPIRV
        glslang-default-resource-limits
        MachineIndependent
        OSDependent
        OGLCompiler
        GenericCodeGen
        OpenMP::OpenMP_CXX
        Vulkan::Vulkan
        android
        log)
target_include_directories(yolovaim PRIVATE ${NCNN_INCLUDE_DIR})
