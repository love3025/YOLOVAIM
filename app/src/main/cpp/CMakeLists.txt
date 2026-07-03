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

# Compile aimbot (JNI bridge + inference engines)
add_library(aimbot SHARED
    src/inference/aimbot.cpp
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
add_executable(root_daemon src/daemon/root_daemon.cpp)
target_include_directories(root_daemon PRIVATE src/injection)
target_link_libraries(root_daemon touch_core android log)
set_target_properties(root_daemon PROPERTIES
    OUTPUT_NAME "libroot_daemon.so"
)

# InputManager evdev reader (for InputManager injection mode)
add_library(inputmgr_core STATIC src/injection/inputmgr_core.cpp)
target_link_libraries(inputmgr_core android log)

add_library(inputmgr_inject SHARED src/injection/inputmgr_jni.cpp)
target_link_libraries(inputmgr_inject inputmgr_core android log)

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

# -----------------------------------------------------------------------------
# AimSvc: vendored Shizuku starter + adb pairing (invisible to external detection)
# -----------------------------------------------------------------------------
# Spawned via "adb shell /data/local/tmp/libaimbot_svc.so --apk=<apk_path>" from the
# host app. Process name is "aimbot_svc" (not "shizuku_server"). Runs as adb shell
# (uid 2000), forks+execs app_process to run the Java service.
# io.github.vvb2060.ndk:boringssl ships prebuilt static libs via prefab, but the
# AAR does not include a *Config.cmake so find_package() fails. We import the .a
# files directly from the Gradle transforms cache instead.
if (DEFINED ENV{GRADLE_USER_HOME})
    set(GRADLE_USER_HOME_DIR "$ENV{GRADLE_USER_HOME}")
elseif (DEFINED ENV{USERPROFILE})
    set(GRADLE_USER_HOME_DIR "$ENV{USERPROFILE}/.gradle")
elseif (DEFINED ENV{HOME})
    set(GRADLE_USER_HOME_DIR "$ENV{HOME}/.gradle")
else()
    set(GRADLE_USER_HOME_DIR "$ENV{HOME}/.gradle")
endif()

file(GLOB boringssl_module_dirs LIST_DIRECTORIES TRUE
    "${GRADLE_USER_HOME_DIR}/caches/8.11.1/transforms/*/transformed/boringssl-*/prefab/modules")
if (NOT boringssl_module_dirs)
    message(FATAL_ERROR "boringssl prefab module dir not found under ${GRADLE_USER_HOME_DIR}/caches/8.11.1/transforms/")
endif()
list(GET boringssl_module_dirs 0 BORINGSSL_PREFAB_ROOT)

add_library(crypto_static STATIC IMPORTED)
set_target_properties(crypto_static PROPERTIES
    IMPORTED_LOCATION "${BORINGSSL_PREFAB_ROOT}/crypto_static/libs/android.${ANDROID_ABI}/libcrypto_static.a")
set(BORINGSSL_INCLUDE_DIR "${BORINGSSL_PREFAB_ROOT}/crypto_static/include" CACHE PATH "")

add_library(ssl_static STATIC IMPORTED)
set_target_properties(ssl_static PROPERTIES
    IMPORTED_LOCATION "${BORINGSSL_PREFAB_ROOT}/ssl_static/libs/android.${ANDROID_ABI}/libssl_static.a")

# libcxx: just import the .a — we don't import its include path because the
# libcxx C++ wrappers for <stdint.h>/<stddef.h> use __has_include_next chains
# that break against the NDK 27 toolchain include layout (the parent
# ${SYSROOT}/usr/include/ is not "next" relative to c++/v1/, so the wrappers
# never reach the C header that defines uint32_t / size_t). Linking libcxx.a
# is enough — we get the C++ ABI symbols we need.
file(GLOB libcxx_module_dirs LIST_DIRECTORIES TRUE
    "${GRADLE_USER_HOME_DIR}/caches/8.11.1/transforms/*/transformed/libcxx-*/prefab/modules")
if (NOT libcxx_module_dirs)
    message(FATAL_ERROR "libcxx prefab module dir not found under ${GRADLE_USER_HOME_DIR}/caches/8.11.1/transforms/")
endif()
list(GET libcxx_module_dirs 0 LIBCXX_PREFAB_ROOT)

add_library(cxx STATIC IMPORTED)
set_target_properties(cxx PROPERTIES
    IMPORTED_LOCATION "${LIBCXX_PREFAB_ROOT}/cxx/libs/android.${ANDROID_ABI}/libcxx.a")
add_library(cxx::cxx ALIAS cxx)

add_library(aimbot_svc_pair SHARED
    src/svc/aimsvc_pair.cpp
    src/svc/misc.cpp
)
target_include_directories(aimbot_svc_pair PRIVATE
    "${BORINGSSL_INCLUDE_DIR}"
    "${BORINGSSL_PREFAB_ROOT}/ssl_static/include")
target_link_libraries(aimbot_svc_pair
    crypto_static
    ssl_static
    android
    log)

add_executable(aimbot_svc_starter
    src/svc/aimsvc_starter.cpp
    src/svc/selinux.cpp
    src/svc/cgroup.cpp
    src/svc/android.cpp
    src/svc/helper.cpp
    src/svc/misc.cpp
)
target_include_directories(aimbot_svc_starter PRIVATE src/injection)
target_link_libraries(aimbot_svc_starter
    android
    log)
set_target_properties(aimbot_svc_starter PROPERTIES
    OUTPUT_NAME "libaimbot_svc.so"
)
