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
add_qnn_lib(libQnnGpu)

# TFLite JNI library - use local copy
add_library(tensorflowlite_jni SHARED IMPORTED)
set_target_properties(tensorflowlite_jni PROPERTIES
    IMPORTED_LOCATION ${QNN_LIB_DIR}/libtensorflowlite_jni.so)

# NOTE: Stub libraries are loaded at runtime via dlopen(), NOT linked at build time.
# They depend on libcdsprpc.so which must be pre-loaded first.
# Stub libraries in cpp/lib/ will be packaged in APK and loaded from app's lib dir.

# Compile aimbot.cpp
add_library(${CMAKE_PROJECT_NAME} SHARED aimbot.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${QNN_INCLUDE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/include/qnn)

# Link TFLite and QNN libraries
target_link_libraries(${CMAKE_PROJECT_NAME}
        libQnnHtp
        libQnnSystem
        libQnnGpu
        tensorflowlite_jni
        android
        log
        dl)
