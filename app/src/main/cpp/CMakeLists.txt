cmake_minimum_required(VERSION 3.22.1)
project("aimbot")

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

# Compile aimbot.cpp
add_library(aimbot SHARED aimbot.cpp)

# Compile uinput injector
add_library(uinput_inject SHARED uinput_inject.cpp)
target_link_libraries(uinput_inject android log)

# Root daemon (standalone binary for su execution, packaged as .so for Gradle)
add_executable(root_daemon root_daemon.cpp)
target_link_libraries(root_daemon android log)
set_target_properties(root_daemon PROPERTIES
    OUTPUT_NAME "libroot_daemon.so"
)

target_include_directories(aimbot PRIVATE
        ${QNN_INCLUDE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/include/qnn)

# Link TFLite and QNN libraries
target_link_libraries(aimbot
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
target_link_libraries(aimbot
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
target_include_directories(aimbot PRIVATE ${NCNN_INCLUDE_DIR})
