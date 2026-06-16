/*
 * Copyright (C) 2021 MediaTek Inc.
 * MIT License
 *
 * Neuron API implementation - loads libneuron_runtime.so dynamically
 */

#include "neuron/neuron_implementation.h"
#include <dlfcn.h>
#include <android/log.h>
#include <sys/system_properties.h>
#include <cstring>
#include <cstdlib>

#define LOG_TAG "NeuronImpl"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int32_t GetAndroidSdkVersion() {
    const char* sdkProp = "ro.build.version.sdk";
    char sdkVersion[92];
    int length = __system_property_get(sdkProp, sdkVersion);
    if (length != 0) {
        return atoi(sdkVersion);
    }
    return 0;
}

static void* LoadFunction(void* handle, const char* name, bool optional) {
    if (handle == nullptr) return nullptr;
    void* fn = dlsym(handle, name);
    if (fn == nullptr && !optional) {
        LOGW("neuron: unable to load function %s", name);
    }
    return fn;
}

#define LOAD_FUNCTION(handle, name, obj) \
    obj.name = reinterpret_cast<decltype(obj.name)>(LoadFunction(handle, #name, false))

#define LOAD_FUNCTION_OPTIONAL(handle, name, obj) \
    obj.name = reinterpret_cast<decltype(obj.name)>(LoadFunction(handle, #name, true))

static NeuronApi LoadNeuronApi() {
    NeuronApi api = {};
    api.android_sdk_version = GetAndroidSdkVersion();
    LOGD("Android SDK version: %d", api.android_sdk_version);

    // Neuron requires SDK > 30
    if (api.android_sdk_version <= 30) {
        api.neuron_exists = false;
        api.handle = nullptr;
        return api;
    }

    // Try to load Neuron adapter library
    const char* libraries[] = {
        "libneuronusdk_adapter.mtk.so.5",
        "libneuronusdk_adapter.mtk.so",
        nullptr
    };

    void* handle = nullptr;
    for (int i = 0; libraries[i] != nullptr; i++) {
        handle = dlopen(libraries[i], RTLD_LAZY | RTLD_LOCAL);
        if (handle) {
            LOGD("Loaded %s", libraries[i]);
            break;
        }
    }

    api.handle = handle;
    api.neuron_exists = (handle != nullptr);

    if (!api.neuron_exists) {
        LOGW("Neuron adapter library not found");
        return api;
    }

    // Load function pointers
    LOAD_FUNCTION(handle, Neuron_getVersion, api);
    LOAD_FUNCTION(handle, Neuron_getL1MemorySizeKb, api);
    LOAD_FUNCTION(handle, NeuronModel_create, api);
    LOAD_FUNCTION(handle, NeuronModel_free, api);
    LOAD_FUNCTION(handle, NeuronModel_finish, api);
    LOAD_FUNCTION(handle, NeuronModel_getSupportedOperations, api);
    LOAD_FUNCTION(handle, NeuronModel_addOperand, api);
    LOAD_FUNCTION(handle, NeuronModel_setOperandValue, api);
    LOAD_FUNCTION_OPTIONAL(handle, NeuronModel_setOperandSymmPerChannelQuantParams, api);
    LOAD_FUNCTION(handle, NeuronModel_addOperation, api);
    LOAD_FUNCTION(handle, NeuronModel_identifyInputsAndOutputs, api);
    LOAD_FUNCTION(handle, NeuronModel_relaxComputationFloat32toFloat16, api);
    LOAD_FUNCTION(handle, NeuronCompilation_create, api);
    LOAD_FUNCTION(handle, NeuronCompilation_setPreference, api);
    LOAD_FUNCTION(handle, NeuronCompilation_setPriority, api);
    LOAD_FUNCTION_OPTIONAL(handle, NeuronCompilation_getCompiledNetworkSize, api);
    LOAD_FUNCTION_OPTIONAL(handle, NeuronCompilation_setOptimizationHint, api);
    LOAD_FUNCTION(handle, NeuronCompilation_free, api);
    LOAD_FUNCTION(handle, NeuronCompilation_finish, api);
    LOAD_FUNCTION(handle, NeuronCompilation_setCaching, api);
    LOAD_FUNCTION(handle, NeuronCompilation_setL1MemorySizeKb, api);
    LOAD_FUNCTION(handle, NeuronExecution_create, api);
    LOAD_FUNCTION(handle, NeuronExecution_free, api);
    LOAD_FUNCTION(handle, NeuronExecution_setInput, api);
    LOAD_FUNCTION(handle, NeuronExecution_setOutput, api);
    LOAD_FUNCTION_OPTIONAL(handle, NeuronExecution_setInputFromMemory, api);
    LOAD_FUNCTION_OPTIONAL(handle, NeuronExecution_setOutputFromMemory, api);
    LOAD_FUNCTION(handle, NeuronExecution_compute, api);
    LOAD_FUNCTION_OPTIONAL(handle, NeuronExecution_setBoostHint, api);
    LOAD_FUNCTION_OPTIONAL(handle, NeuronExecution_getOutputOperandRank, api);
    LOAD_FUNCTION_OPTIONAL(handle, NeuronExecution_getOutputOperandDimensions, api);
    LOAD_FUNCTION(handle, Neuron_getDeviceCount, api);
    LOAD_FUNCTION(handle, Neuron_getDevice, api);
    LOAD_FUNCTION(handle, NeuronDevice_getName, api);
    LOAD_FUNCTION_OPTIONAL(handle, NeuronCompilation_createForDevices, api);
    LOAD_FUNCTION_OPTIONAL(handle, NeuronEvent_free, api);
    LOAD_FUNCTION_OPTIONAL(handle, NeuronEvent_wait, api);
    LOAD_FUNCTION_OPTIONAL(handle, NeuronMemory_createFromAHardwareBuffer, api);
    LOAD_FUNCTION_OPTIONAL(handle, NeuronMemory_free, api);

    // Check if MDLA/DSP devices exist
    uint32_t num_devices = 0;
    api.Neuron_getDeviceCount(&num_devices);
    if (num_devices == 0) {
        api.neuron_exists = false;
        LOGW("No Neuron devices found");
    } else {
        api.neuron_exists = false;
        for (uint32_t i = 0; i < num_devices; i++) {
            const char* name = nullptr;
            NeuronDevice* device = nullptr;
            api.Neuron_getDevice(i, &device);
            api.NeuronDevice_getName(device, &name);
            if (name != nullptr && (strncmp(name, "mtk-dsp", 7) == 0 ||
                                    strncmp(name, "mtk-mdla", 8) == 0)) {
                api.neuron_exists = true;
                LOGD("Found Neuron device: %s", name);
                break;
            }
        }
    }

    // Load ASharedMemory_create
    void* libandroid = dlopen("libandroid.so", RTLD_LAZY | RTLD_LOCAL);
    if (libandroid) {
        api.ASharedMemory_create = reinterpret_cast<decltype(api.ASharedMemory_create)>(
            LoadFunction(libandroid, "ASharedMemory_create", true));
    }

    return api;
}

const NeuronApi* NeuronApiImplementation() {
    static const NeuronApi api = LoadNeuronApi();
    if (api.neuron_exists) {
        NeuronRuntimeVersion version;
        if (api.Neuron_getVersion && api.Neuron_getVersion(&version) == NEURON_NO_ERROR) {
            LOGD("Neuron API version: %d.%d.%d", version.major, version.minor, version.patch);
        }
    }
    return &api;
}
