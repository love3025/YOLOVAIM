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
