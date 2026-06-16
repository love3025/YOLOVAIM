/*
 * Neuron Delegate implementation for MediaTek APU
 * Dynamically loads libneuron_delegate.so if available
 */

#include "neuron/neuron_delegate.h"
#include "neuron/neuron_implementation.h"
#include <android/log.h>
#include <dlfcn.h>

#define LOG_TAG "NeuronDelegate"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Function pointers for dynamically loaded Neuron Delegate
typedef TfLiteDelegate* (*TfLiteNeuronDelegateCreate_fn)(const NeuronDelegateOptions*);
typedef void (*TfLiteNeuronDelegateDelete_fn)(TfLiteDelegate*);
typedef NeuronDelegateOptions (*TfLiteNeuronDelegateOptionsDefault_fn)();

static TfLiteNeuronDelegateCreate_fn g_create_fn = nullptr;
static TfLiteNeuronDelegateDelete_fn g_delete_fn = nullptr;
static TfLiteNeuronDelegateOptionsDefault_fn g_default_opts_fn = nullptr;
static void* g_delegate_lib = nullptr;
static bool g_initialized = false;

static bool loadNeuronDelegateLib() {
    if (g_initialized) return g_delegate_lib != nullptr;
    g_initialized = true;

    LOGD("Attempting to load libneuron_delegate.so...");

    // Try to load from app's native lib directory first
    g_delegate_lib = dlopen("libneuron_delegate.so", RTLD_LAZY | RTLD_LOCAL);
    if (g_delegate_lib) {
        LOGD("✓ libneuron_delegate.so loaded from default path");
    } else {
        LOGD("libneuron_delegate.so not found in default path: %s", dlerror());

        // Try absolute path
        g_delegate_lib = dlopen("/data/data/team.maodie.aimbot/lib/libneuron_delegate.so", RTLD_LAZY | RTLD_LOCAL);
        if (g_delegate_lib) {
            LOGD("✓ libneuron_delegate.so loaded from absolute path");
        } else {
            LOGW("✗ libneuron_delegate.so not found: %s", dlerror());
            return false;
        }
    }

    g_create_fn = reinterpret_cast<TfLiteNeuronDelegateCreate_fn>(
        dlsym(g_delegate_lib, "TfLiteNeuronDelegateCreate"));
    g_delete_fn = reinterpret_cast<TfLiteNeuronDelegateDelete_fn>(
        dlsym(g_delegate_lib, "TfLiteNeuronDelegateDelete"));
    g_default_opts_fn = reinterpret_cast<TfLiteNeuronDelegateOptionsDefault_fn>(
        dlsym(g_delegate_lib, "TfLiteNeuronDelegateOptionsDefault"));

    if (!g_create_fn) {
        LOGE("✗ TfLiteNeuronDelegateCreate not found in library");
    }
    if (!g_delete_fn) {
        LOGE("✗ TfLiteNeuronDelegateDelete not found in library");
    }

    if (!g_create_fn || !g_delete_fn) {
        LOGW("Failed to load required Neuron delegate functions");
        dlclose(g_delegate_lib);
        g_delegate_lib = nullptr;
        return false;
    }

    LOGD("✓ libneuron_delegate.so loaded successfully");
    return true;
}

NeuronDelegateOptions TfLiteNeuronDelegateOptionsDefault() {
    if (g_default_opts_fn) {
        return g_default_opts_fn();
    }

    // Return default options if library not loaded
    LOGD("Using default NeuronDelegateOptions (library not loaded)");
    NeuronDelegateOptions options = {};
    options.execution_preference = kFastSingleAnswer;
    options.execution_priority = kPriorityHigh;
    options.optimization_hint = 0;
    options.allow_fp16 = false;
    options.boost_duration = 0;
    options.cache_dir = nullptr;
    options.model_token = nullptr;
    options.use_ahwb = false;
    options.use_cacheable_buffer = true;
    options.compile_options[0] = '\0';
    options.accelerator_name[0] = '\0';
    return options;
}

TfLiteDelegate* TfLiteNeuronDelegateCreate(const NeuronDelegateOptions* options) {
    LOGD("TfLiteNeuronDelegateCreate called");

    if (!loadNeuronDelegateLib()) {
        LOGW("✗ Neuron delegate library not available, cannot create delegate");
        return nullptr;
    }

    if (!g_create_fn) {
        LOGE("✗ TfLiteNeuronDelegateCreate function not found");
        return nullptr;
    }

    LOGD("Creating Neuron delegate via libneuron_delegate.so...");
    TfLiteDelegate* delegate = g_create_fn(options);
    if (delegate) {
        LOGD("✓ Neuron delegate created successfully");
    } else {
        LOGW("✗ Neuron delegate creation returned nullptr");
    }
    return delegate;
}

void TfLiteNeuronDelegateDelete(TfLiteDelegate* delegate) {
    LOGD("TfLiteNeuronDelegateDelete called");
    if (g_delete_fn && delegate) {
        g_delete_fn(delegate);
        LOGD("✓ Neuron delegate deleted");
    }
}
