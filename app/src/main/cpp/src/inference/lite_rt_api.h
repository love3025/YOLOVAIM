#pragma once

//==============================================================================
//  LiteRT C API - Types and Function Pointer Table
//  Based on official Google LiteRT headers (litert/c/)
//  Used by MtkEngine (MediaTek NPU)
//==============================================================================

#include "common.h"
#include <dlfcn.h>
#include <vector>
#include <cstdint>
#include <cstddef>

// Status codes
typedef int LiteRtStatus;
static constexpr LiteRtStatus kLiteRtStatusOk = 0;

// Opaque handle types (pointer-sized on all platforms)
typedef struct LiteRtEnvironmentT*     LiteRtEnvironment;
typedef struct LiteRtOptionsT*         LiteRtOptions;
typedef struct LiteRtOpaqueOptionsT*   LiteRtOpaqueOptions;
typedef struct LiteRtModelT*           LiteRtModel;
typedef struct LiteRtCompiledModelT*   LiteRtCompiledModel;
typedef struct LiteRtSubgraphT*        LiteRtSubgraph;
typedef struct LiteRtTensorT*          LiteRtTensor;
typedef struct LiteRtTensorBufferT*    LiteRtTensorBuffer;
typedef struct LiteRtTensorBufferRequirementsT* LiteRtTensorBufferRequirements;

// For indexing into LiteRT collections
typedef size_t LiteRtParamIndex;

//==============================================================================
//  Element types (from litert/c/litert_model_types.h)
//==============================================================================
typedef enum {
    kLiteRtElementTypeNone = 0,
    kLiteRtElementTypeFloat32 = 1,
    kLiteRtElementTypeInt32 = 2,
    kLiteRtElementTypeUInt8 = 3,
    kLiteRtElementTypeInt64 = 4,
    kLiteRtElementTypeString = 5,
    kLiteRtElementTypeBool = 6,
    kLiteRtElementTypeInt16 = 7,
    kLiteRtElementTypeComplex64 = 8,
    kLiteRtElementTypeInt8 = 9,
    kLiteRtElementTypeFloat16 = 10,
    kLiteRtElementTypeFloat64 = 11,
} LiteRtElementType;

//==============================================================================
//  Layout (from litert/c/litert_layout.h)
//  sizeof(LiteRtLayout) = 68 on arm64 Linux/Android
//==============================================================================
#define LITERT_TENSOR_MAX_RANK 8

struct LiteRtLayout {
    unsigned int rank : 7;
    bool has_strides : 1;
    int32_t dimensions[LITERT_TENSOR_MAX_RANK];   // offset 4
    uint32_t strides[LITERT_TENSOR_MAX_RANK];     // offset 36
};  // total 68 bytes

//==============================================================================
//  Ranked tensor type (from litert/c/litert_model_types.h)
//  sizeof(LiteRtRankedTensorType) = 72 on arm64 Linux/Android
//==============================================================================
struct LiteRtRankedTensorType {
    LiteRtElementType element_type;  // offset 0, 4 bytes
    LiteRtLayout layout;             // offset 4, 68 bytes
};  // total 72 bytes

//==============================================================================
//  Quantization (from litert/c/litert_model_types.h)
//  sizeof(LiteRtQuantizationPerTensor) = 16 on arm64
//==============================================================================
struct LiteRtQuantizationPerTensor {
    float scale;           // offset 0
    int64_t zero_point;    // offset 8 (NOT int!)
};  // total 16 bytes

//==============================================================================
//  Environment options (from litert/c/litert_environment_options.h + litert_any.h)
//  sizeof(LiteRtEnvOption) = 24 on arm64
//==============================================================================
typedef enum {
    kLiteRtEnvOptionTagCompilerPluginLibraryDir = 0,
    kLiteRtEnvOptionTagDispatchLibraryDir = 1,
    kLiteRtEnvOptionTagOpenClDeviceId = 2,
    kLiteRtEnvOptionTagOpenClPlatformId = 3,
    kLiteRtEnvOptionTagOpenClContext = 4,
    kLiteRtEnvOptionTagOpenClCommandQueue = 5,
    kLiteRtEnvOptionTagEglDisplay = 6,
    kLiteRtEnvOptionTagEglContext = 7,
    kLiteRtEnvOptionTagCompilerCacheDir = 18,
    kLiteRtEnvOptionTagNull = 255,
} LiteRtEnvOptionTag;

typedef enum {
    kLiteRtAnyTypeNone = 0,
    kLiteRtAnyTypeBool = 1,
    kLiteRtAnyTypeInt = 2,
    kLiteRtAnyTypeReal = 3,
    kLiteRtAnyTypeString = 8,
    kLiteRtAnyTypeVoidPtr = 9,
} LiteRtAnyType;

struct LiteRtAny {
    LiteRtAnyType type;         // offset 0, 4 bytes
    union {                     // offset 8, 8 bytes
        bool bool_value;
        int64_t int_value;
        double real_value;
        const char* str_value;
        const void* ptr_value;
    };
};  // total 16 bytes

struct LiteRtEnvOption {
    LiteRtEnvOptionTag tag;     // offset 0, 4 bytes
    LiteRtAny value;            // offset 8, 16 bytes
};  // total 24 bytes

//==============================================================================
//  Tensor buffer types (from litert/c/litert_tensor_buffer_types.h)
//==============================================================================
typedef enum {
    kLiteRtTensorBufferTypeUnknown = 0,
    kLiteRtTensorBufferTypeHostMemory = 1,
    kLiteRtTensorBufferTypeAhwb = 2,
    kLiteRtTensorBufferTypeIon = 3,
    kLiteRtTensorBufferTypeDmaBuf = 4,
    kLiteRtTensorBufferTypeFastRpc = 5,
} LiteRtTensorBufferType;

//==============================================================================
//  Tensor buffer lock mode (from litert/c/litert_common.h)
//==============================================================================
typedef enum {
    kLiteRtTensorBufferLockModeRead = 0,
    kLiteRtTensorBufferLockModeWrite = 1,
    kLiteRtTensorBufferLockModeReadWrite = 2,
} LiteRtTensorBufferLockMode;

//==============================================================================
//  Free function type
//==============================================================================
typedef void (*LiteRtFreeFunc)(void*);

//==============================================================================
//  LiteRT C API Function Pointer Types
//==============================================================================

// Environment
typedef LiteRtStatus (*PFN_LiteRtCreateEnvironment)(
    int num_options, const LiteRtEnvOption* options, LiteRtEnvironment* environment);
typedef void (*PFN_LiteRtDestroyEnvironment)(LiteRtEnvironment environment);

// Options
typedef LiteRtStatus (*PFN_LiteRtCreateOptions)(LiteRtOptions* options);
typedef void (*PFN_LiteRtDestroyOptions)(LiteRtOptions options);
typedef LiteRtStatus (*PFN_LiteRtSetOptionsHardwareAccelerators)(
    LiteRtOptions options, int hardware_accelerators);

// Opaque options (for MediaTek vendor options)
typedef LiteRtStatus (*PFN_LiteRtCreateOpaqueOptions)(
    const char* option_namespace, const char* config,
    LiteRtFreeFunc free_func, LiteRtOpaqueOptions* opaque_options);
typedef LiteRtStatus (*PFN_LiteRtAddOpaqueOptions)(
    LiteRtOptions options, LiteRtOpaqueOptions opaque_options);
typedef void (*PFN_LiteRtDestroyOpaqueOptions)(LiteRtOpaqueOptions opaque_options);

// Model
typedef LiteRtStatus (*PFN_LiteRtCreateModelFromFile)(
    const char* model_path, LiteRtModel* model);
typedef void (*PFN_LiteRtDestroyModel)(LiteRtModel model);
typedef LiteRtStatus (*PFN_LiteRtGetMainModelSubgraphIndex)(
    LiteRtModel model, int* subgraph_index);
typedef LiteRtStatus (*PFN_LiteRtGetModelSubgraph)(
    LiteRtModel model, int subgraph_index, LiteRtSubgraph* subgraph);

// Subgraph / Tensor queries
typedef LiteRtStatus (*PFN_LiteRtGetNumSubgraphInputs)(
    LiteRtSubgraph subgraph, int* num_inputs);
typedef LiteRtStatus (*PFN_LiteRtGetSubgraphInput)(
    LiteRtSubgraph subgraph, int input_index, LiteRtTensor* tensor);
typedef LiteRtStatus (*PFN_LiteRtGetNumSubgraphOutputs)(
    LiteRtSubgraph subgraph, int* num_outputs);
typedef LiteRtStatus (*PFN_LiteRtGetSubgraphOutput)(
    LiteRtSubgraph subgraph, int output_index, LiteRtTensor* tensor);
typedef LiteRtStatus (*PFN_LiteRtGetRankedTensorType)(
    LiteRtTensor tensor, LiteRtRankedTensorType* type);
typedef LiteRtStatus (*PFN_LiteRtGetQuantizationTypeId)(
    LiteRtTensor tensor, int* quantization_type_id);
typedef LiteRtStatus (*PFN_LiteRtGetPerTensorQuantization)(
    LiteRtTensor tensor, LiteRtQuantizationPerTensor* params);

// Compiled model
typedef LiteRtStatus (*PFN_LiteRtCreateCompiledModel)(
    LiteRtEnvironment environment, LiteRtModel model,
    LiteRtOptions options, LiteRtCompiledModel* compiled_model);
typedef void (*PFN_LiteRtDestroyCompiledModel)(LiteRtCompiledModel compiled_model);

// Buffer requirements (returns opaque handle, NOT int!)
typedef LiteRtStatus (*PFN_LiteRtGetCompiledModelInputBufferRequirements)(
    LiteRtCompiledModel compiled_model, LiteRtParamIndex signature_index,
    LiteRtParamIndex input_index, LiteRtTensorBufferRequirements* buffer_requirements);
typedef LiteRtStatus (*PFN_LiteRtGetCompiledModelOutputBufferRequirements)(
    LiteRtCompiledModel compiled_model, LiteRtParamIndex signature_index,
    LiteRtParamIndex output_index, LiteRtTensorBufferRequirements* buffer_requirements);

// Buffer creation
typedef LiteRtStatus (*PFN_LiteRtCreateManagedTensorBufferFromRequirements)(
    LiteRtEnvironment env, const LiteRtRankedTensorType* tensor_type,
    LiteRtTensorBufferRequirements requirements, LiteRtTensorBuffer* buffer);
typedef LiteRtStatus (*PFN_LiteRtCreateManagedTensorBuffer)(
    LiteRtEnvironment env, LiteRtTensorBufferType buffer_type,
    const LiteRtRankedTensorType* tensor_type, size_t buffer_size,
    LiteRtTensorBuffer* buffer);

// Buffer access
typedef LiteRtStatus (*PFN_LiteRtGetTensorBufferPackedSize)(
    LiteRtTensorBuffer buffer, size_t* packed_size);
typedef void (*PFN_LiteRtDestroyTensorBuffer)(LiteRtTensorBuffer buffer);
typedef LiteRtStatus (*PFN_LiteRtLockTensorBuffer)(
    LiteRtTensorBuffer buffer, void** host_mem_addr,
    LiteRtTensorBufferLockMode lock_mode);
typedef LiteRtStatus (*PFN_LiteRtUnlockTensorBuffer)(LiteRtTensorBuffer buffer);

// Inference (has signature_index parameter!)
typedef LiteRtStatus (*PFN_LiteRtRunCompiledModel)(
    LiteRtCompiledModel compiled_model,
    LiteRtParamIndex signature_index,
    size_t num_input_buffers, LiteRtTensorBuffer* input_buffers,
    size_t num_output_buffers, LiteRtTensorBuffer* output_buffers);

// Runtime tensor layout queries
typedef LiteRtStatus (*PFN_LiteRtGetCompiledModelInputTensorLayout)(
    LiteRtCompiledModel compiled_model, LiteRtParamIndex signature_index,
    LiteRtParamIndex input_index, LiteRtLayout* layout);
typedef LiteRtStatus (*PFN_LiteRtGetCompiledModelOutputTensorLayouts)(
    LiteRtCompiledModel compiled_model, LiteRtParamIndex signature_index,
    size_t num_layouts, LiteRtLayout* layouts, bool update_allocation);

//==============================================================================
//  MediaTek option setters (from dispatch plugin, optional)
//  These operate on LiteRtOptions directly (vendor extension).
//==============================================================================
typedef LiteRtStatus (*PFN_LiteRtSetMediatekPerformanceMode)(
    LiteRtOptions options, int mode);
typedef LiteRtStatus (*PFN_LiteRtSetMediatekOptimizationHint)(
    LiteRtOptions options, int hint);
typedef LiteRtStatus (*PFN_LiteRtSetMediatekNeronSDKVersionType)(
    LiteRtOptions options, int version);

//==============================================================================
//  Output info (per-output tensor buffer + metadata)
//==============================================================================
struct OutputInfo {
    LiteRtTensorBuffer buffer = nullptr;
    LiteRtRankedTensorType tensor_type = {};
    size_t packed_size = 0;
    LiteRtQuantizationPerTensor quant_params = {1.0f, 0};
    bool has_per_tensor_quant = false;
};

//==============================================================================
//  Function pointer table
//==============================================================================
struct LiteRtApi {
    void* lib_handle = nullptr;

    // Environment
    PFN_LiteRtCreateEnvironment                CreateEnvironment = nullptr;
    PFN_LiteRtDestroyEnvironment               DestroyEnvironment = nullptr;

    // Options
    PFN_LiteRtCreateOptions                    CreateOptions = nullptr;
    PFN_LiteRtDestroyOptions                   DestroyOptions = nullptr;
    PFN_LiteRtSetOptionsHardwareAccelerators   SetOptionsHardwareAccelerators = nullptr;

    // Opaque options
    PFN_LiteRtCreateOpaqueOptions              CreateOpaqueOptions = nullptr;
    PFN_LiteRtAddOpaqueOptions                 AddOpaqueOptions = nullptr;
    PFN_LiteRtDestroyOpaqueOptions             DestroyOpaqueOptions = nullptr;

    // Model
    PFN_LiteRtCreateModelFromFile              CreateModelFromFile = nullptr;
    PFN_LiteRtDestroyModel                     DestroyModel = nullptr;
    PFN_LiteRtGetMainModelSubgraphIndex        GetMainModelSubgraphIndex = nullptr;
    PFN_LiteRtGetModelSubgraph                 GetModelSubgraph = nullptr;

    // Subgraph / Tensor
    PFN_LiteRtGetNumSubgraphInputs             GetNumSubgraphInputs = nullptr;
    PFN_LiteRtGetSubgraphInput                 GetSubgraphInput = nullptr;
    PFN_LiteRtGetNumSubgraphOutputs            GetNumSubgraphOutputs = nullptr;
    PFN_LiteRtGetSubgraphOutput                GetSubgraphOutput = nullptr;
    PFN_LiteRtGetRankedTensorType              GetRankedTensorType = nullptr;
    PFN_LiteRtGetQuantizationTypeId            GetQuantizationTypeId = nullptr;
    PFN_LiteRtGetPerTensorQuantization         GetPerTensorQuantization = nullptr;

    // Compiled model
    PFN_LiteRtCreateCompiledModel              CreateCompiledModel = nullptr;
    PFN_LiteRtDestroyCompiledModel             DestroyCompiledModel = nullptr;

    // Buffer requirements (opaque handle!)
    PFN_LiteRtGetCompiledModelInputBufferRequirements  GetInputBufferRequirements = nullptr;
    PFN_LiteRtGetCompiledModelOutputBufferRequirements GetOutputBufferRequirements = nullptr;

    // Buffer creation
    PFN_LiteRtCreateManagedTensorBufferFromRequirements CreateManagedTensorBufferFromReq = nullptr;
    PFN_LiteRtCreateManagedTensorBuffer     CreateManagedTensorBuffer = nullptr;

    // Buffer access
    PFN_LiteRtGetTensorBufferPackedSize        GetTensorBufferPackedSize = nullptr;
    PFN_LiteRtDestroyTensorBuffer              DestroyTensorBuffer = nullptr;
    PFN_LiteRtLockTensorBuffer                 LockTensorBuffer = nullptr;
    PFN_LiteRtUnlockTensorBuffer               UnlockTensorBuffer = nullptr;

    // Inference
    PFN_LiteRtRunCompiledModel                 RunCompiledModel = nullptr;

    // Runtime tensor layout queries
    PFN_LiteRtGetCompiledModelInputTensorLayout   GetInputTensorLayout = nullptr;
    PFN_LiteRtGetCompiledModelOutputTensorLayouts GetOutputTensorLayouts = nullptr;

    // MediaTek-specific option setters (optional, from dispatch plugin)
    PFN_LiteRtSetMediatekPerformanceMode       SetMediatekPerformanceMode = nullptr;
    PFN_LiteRtSetMediatekOptimizationHint      SetMediatekOptimizationHint = nullptr;
    PFN_LiteRtSetMediatekNeronSDKVersionType   SetMediatekNeronSDKVersionType = nullptr;

    bool loadAll();
    void unload();
};
