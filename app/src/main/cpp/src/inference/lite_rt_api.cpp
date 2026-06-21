#include "lite_rt_api.h"
#include <cstdio>
#include <cstring>

//==============================================================================
//  LiteRT API Symbol Loading (shared by MtkEngine)
//==============================================================================

bool LiteRtApi::loadAll() {
    lib_handle = dlopen("libLiteRt.so", RTLD_NOW);
    if (!lib_handle) {
        LOGE("libLiteRt.so failed to load: %s", dlerror());
        return false;
    }
    LOGD("libLiteRt.so loaded successfully");

    #define RESOLVE(field, sym) \
        field = reinterpret_cast<decltype(field)>(dlsym(lib_handle, sym)); \
        if (!field) { LOGE("Failed to resolve: %s", sym); return false; }

    // Environment
    RESOLVE(CreateEnvironment,                "LiteRtCreateEnvironment");
    RESOLVE(DestroyEnvironment,               "LiteRtDestroyEnvironment");

    // Options
    RESOLVE(CreateOptions,                    "LiteRtCreateOptions");
    RESOLVE(DestroyOptions,                   "LiteRtDestroyOptions");
    RESOLVE(SetOptionsHardwareAccelerators,   "LiteRtSetOptionsHardwareAccelerators");

    // Opaque options
    RESOLVE(CreateOpaqueOptions,              "LiteRtCreateOpaqueOptions");
    RESOLVE(AddOpaqueOptions,                 "LiteRtAddOpaqueOptions");
    RESOLVE(DestroyOpaqueOptions,             "LiteRtDestroyOpaqueOptions");

    // Model
    RESOLVE(CreateModelFromFile,              "LiteRtCreateModelFromFile");
    RESOLVE(DestroyModel,                     "LiteRtDestroyModel");
    RESOLVE(GetMainModelSubgraphIndex,        "LiteRtGetMainModelSubgraphIndex");
    RESOLVE(GetModelSubgraph,                 "LiteRtGetModelSubgraph");

    // Subgraph / Tensor
    RESOLVE(GetNumSubgraphInputs,             "LiteRtGetNumSubgraphInputs");
    RESOLVE(GetSubgraphInput,                 "LiteRtGetSubgraphInput");
    RESOLVE(GetNumSubgraphOutputs,            "LiteRtGetNumSubgraphOutputs");
    RESOLVE(GetSubgraphOutput,                "LiteRtGetSubgraphOutput");
    RESOLVE(GetRankedTensorType,              "LiteRtGetRankedTensorType");
    RESOLVE(GetQuantizationTypeId,            "LiteRtGetQuantizationTypeId");
    RESOLVE(GetPerTensorQuantization,         "LiteRtGetPerTensorQuantization");

    // Compiled model
    RESOLVE(CreateCompiledModel,              "LiteRtCreateCompiledModel");
    RESOLVE(DestroyCompiledModel,             "LiteRtDestroyCompiledModel");

    // Buffer requirements (returns opaque handle)
    RESOLVE(GetInputBufferRequirements,       "LiteRtGetCompiledModelInputBufferRequirements");
    RESOLVE(GetOutputBufferRequirements,      "LiteRtGetCompiledModelOutputBufferRequirements");

    // Buffer creation
    RESOLVE(CreateManagedTensorBufferFromReq, "LiteRtCreateManagedTensorBufferFromRequirements");
    RESOLVE(CreateManagedTensorBuffer,        "LiteRtCreateManagedTensorBuffer");

    // Buffer access
    RESOLVE(GetTensorBufferPackedSize,        "LiteRtGetTensorBufferPackedSize");
    RESOLVE(DestroyTensorBuffer,              "LiteRtDestroyTensorBuffer");
    RESOLVE(LockTensorBuffer,                 "LiteRtLockTensorBuffer");
    RESOLVE(UnlockTensorBuffer,               "LiteRtUnlockTensorBuffer");

    // Inference
    RESOLVE(RunCompiledModel,                 "LiteRtRunCompiledModel");

    #undef RESOLVE

    // Runtime tensor layout queries (optional)
    GetInputTensorLayout = reinterpret_cast<PFN_LiteRtGetCompiledModelInputTensorLayout>(
        dlsym(lib_handle, "LiteRtGetCompiledModelInputTensorLayout"));
    GetOutputTensorLayouts = reinterpret_cast<PFN_LiteRtGetCompiledModelOutputTensorLayouts>(
        dlsym(lib_handle, "LiteRtGetCompiledModelOutputTensorLayouts"));
    if (GetInputTensorLayout) LOGD("Resolved: LiteRtGetCompiledModelInputTensorLayout");
    if (GetOutputTensorLayouts) LOGD("Resolved: LiteRtGetCompiledModelOutputTensorLayouts");

    // MediaTek-specific option symbols (optional, from dispatch plugin)
    SetMediatekPerformanceMode = reinterpret_cast<PFN_LiteRtSetMediatekPerformanceMode>(
        dlsym(lib_handle, "LiteRtSetMediatekPerformanceMode"));
    SetMediatekOptimizationHint = reinterpret_cast<PFN_LiteRtSetMediatekOptimizationHint>(
        dlsym(lib_handle, "LiteRtSetMediatekOptimizationHint"));
    SetMediatekNeronSDKVersionType = reinterpret_cast<PFN_LiteRtSetMediatekNeronSDKVersionType>(
        dlsym(lib_handle, "LiteRtSetMediatekNeronSDKVersionType"));

    if (SetMediatekPerformanceMode) LOGD("Resolved: LiteRtSetMediatekPerformanceMode");
    if (SetMediatekOptimizationHint) LOGD("Resolved: LiteRtSetMediatekOptimizationHint");
    if (SetMediatekNeronSDKVersionType) LOGD("Resolved: LiteRtSetMediatekNeronSDKVersionType");

    LOGD("All core LiteRT symbols resolved");
    return true;
}

void LiteRtApi::unload() {
    lib_handle = nullptr;

    // Environment
    CreateEnvironment = nullptr;
    DestroyEnvironment = nullptr;

    // Options
    CreateOptions = nullptr;
    DestroyOptions = nullptr;
    SetOptionsHardwareAccelerators = nullptr;

    // Opaque options
    CreateOpaqueOptions = nullptr;
    AddOpaqueOptions = nullptr;
    DestroyOpaqueOptions = nullptr;

    // Model
    CreateModelFromFile = nullptr;
    DestroyModel = nullptr;
    GetMainModelSubgraphIndex = nullptr;
    GetModelSubgraph = nullptr;

    // Subgraph / Tensor
    GetNumSubgraphInputs = nullptr;
    GetSubgraphInput = nullptr;
    GetNumSubgraphOutputs = nullptr;
    GetSubgraphOutput = nullptr;
    GetRankedTensorType = nullptr;
    GetQuantizationTypeId = nullptr;
    GetPerTensorQuantization = nullptr;

    // Compiled model
    CreateCompiledModel = nullptr;
    DestroyCompiledModel = nullptr;

    // Buffer requirements
    GetInputBufferRequirements = nullptr;
    GetOutputBufferRequirements = nullptr;

    // Buffer creation
    CreateManagedTensorBufferFromReq = nullptr;
    CreateManagedTensorBuffer = nullptr;

    // Buffer access
    GetTensorBufferPackedSize = nullptr;
    DestroyTensorBuffer = nullptr;
    LockTensorBuffer = nullptr;
    UnlockTensorBuffer = nullptr;

    // Inference
    RunCompiledModel = nullptr;

    // Layout queries
    GetInputTensorLayout = nullptr;
    GetOutputTensorLayouts = nullptr;

    // MediaTek
    SetMediatekPerformanceMode = nullptr;
    SetMediatekOptimizationHint = nullptr;
    SetMediatekNeronSDKVersionType = nullptr;
}
