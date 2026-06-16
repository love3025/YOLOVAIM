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

# TFLite sources needed for Neuron Delegate
set(TFLITE_SOURCES
    tflite/common.c
    tflite/allocation.cc
    tflite/util.cc
    tflite/minimal_logging.cc
    tflite/minimal_logging_android.cc
    tflite/simple_delegate.cc
    include/tensorflow/lite/core/api/error_reporter.cc
    extracted_tflite/tensorflow/lite/delegates/utils.cc
    farmhash_stub.cc
)

# Prebuilt neuron_performance static library (MediaTek)
add_library(neuron_performance STATIC IMPORTED)
set_target_properties(neuron_performance PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/neuron/lib/libneuron_performance.a)

# Neuron Delegate (MediaTek APU acceleration)
add_library(neuron_delegate SHARED
    neuron_delegate_real.cc
    neuron_delegate_kernel.cc
    neuron_delegate_validation.cc
    neuron_implementation_full.cc
    mtk_utils.cc
    ${TFLITE_SOURCES})
target_include_directories(neuron_delegate PRIVATE
    ${QNN_INCLUDE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/include/tensorflow
    ${CMAKE_CURRENT_SOURCE_DIR}/include/farmhash)
target_link_libraries(neuron_delegate neuron_performance android log dl)
# Hide internal symbols, only export TfLiteNeuron* API
set_target_properties(neuron_delegate PROPERTIES
    LINK_FLAGS "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/neuron/version_script.lds")

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

# Link TFLite, QNN, and Neuron libraries
target_link_libraries(aimbot
        libQnnHtp
        libQnnSystem
        libQnnTFLiteDelegate
        tensorflowlite_jni
        neuron_delegate
        android
        log
        dl)
