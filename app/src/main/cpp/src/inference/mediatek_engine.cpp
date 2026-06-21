#include "mediatek_engine.h"
#include <cstdio>
#include <cstring>
#include <cmath>
#include <vector>
#include <algorithm>
#include <sys/stat.h>
#include <unistd.h>

//==============================================================================
//  Device Detection
//==============================================================================

static const char* getNativeLibDir() {
    static char dir[512] = {0};
    if (dir[0]) return dir;
    Dl_info info;
    if (dladdr((void*)getNativeLibDir, &info)) {
        std::string libPath(info.dli_fname);
        size_t pos = libPath.find_last_of('/');
        if (pos != std::string::npos) {
            strncpy(dir, libPath.substr(0, pos).c_str(), sizeof(dir) - 1);
        }
    }
    return dir;
}

bool MtkEngine::isMediaTekDevice() {
    FILE* f = fopen("/proc/cpuinfo", "r");
    if (f) {
        char line[512];
        while (fgets(line, sizeof(line), f)) {
            if (strstr(line, "MT") || strstr(line, "mt") ||
                strstr(line, "MediaTek") || strstr(line, "mediatek")) {
                fclose(f);
                return true;
            }
        }
        fclose(f);
    }
    FILE* p = popen("getprop ro.hardware", "r");
    if (p) {
        char line[128];
        if (fgets(line, sizeof(line), p)) {
            if (strstr(line, "mt") || strstr(line, "MT")) {
                pclose(p);
                return true;
            }
        }
        pclose(p);
    }
    p = popen("getprop ro.soc.manufacturer", "r");
    if (p) {
        char line[128];
        if (fgets(line, sizeof(line), p)) {
            if (strstr(line, "MediaTek") || strstr(line, "mediatek")) {
                pclose(p);
                return true;
            }
        }
        pclose(p);
    }
    return false;
}


//==============================================================================
//  Lifecycle
//==============================================================================

MtkEngine::MtkEngine() = default;

MtkEngine::~MtkEngine() {
    release();
}

bool MtkEngine::init(const char* model_path) {
    release();

    // 1. Device check
    if (!isMediaTekDevice()) {
        LOGW("Not a MediaTek device, MTK NPU unavailable");
        return false;
    }

    // 2. Load LiteRT API symbols
    if (!m_api.loadAll()) {
        LOGE("Failed to load LiteRT API");
        return false;
    }

    // 3. Get native lib dir — LiteRT will auto-discover dispatch/compiler plugins from here
    const char* native_lib_dir = getNativeLibDir();
    if (!native_lib_dir || !native_lib_dir[0]) {
        LOGE("Cannot determine native lib directory");
        return false;
    }
    LOGD("Native lib dir: %s", native_lib_dir);

    // 4. Create environment with DispatchLibraryDir + CompilerPluginLibraryDir
    //    LiteRT auto-discovers libLiteRtDispatch_MediaTek.so from this directory.
    LiteRtEnvOption env_options[3] = {};
    env_options[0].tag = kLiteRtEnvOptionTagDispatchLibraryDir;  // = 1
    env_options[0].value.type = kLiteRtAnyTypeString;
    env_options[0].value.str_value = native_lib_dir;
    env_options[1].tag = kLiteRtEnvOptionTagCompilerPluginLibraryDir;  // = 0
    env_options[1].value.type = kLiteRtAnyTypeString;
    env_options[1].value.str_value = native_lib_dir;
    env_options[2].tag = kLiteRtEnvOptionTagNull;  // terminator
    env_options[2].value.type = kLiteRtAnyTypeNone;

    LiteRtStatus status = m_api.CreateEnvironment(3, env_options, &m_environment);
    if (status != kLiteRtStatusOk) {
        LOGE("LiteRtCreateEnvironment failed: %d", status);
        return false;
    }
    LOGD("LiteRT environment created (dispatch dir: %s)", native_lib_dir);

    // 5. Create compilation options
    status = m_api.CreateOptions(&m_options);
    if (status != kLiteRtStatusOk) {
        LOGE("LiteRtCreateOptions failed: %d", status);
        return false;
    }

    // 6. Set hardware accelerators: NPU + CPU fallback (exactly like Google sample)
    status = m_api.SetOptionsHardwareAccelerators(m_options, 5);  // kNpu=4 | kCpu=1 = 5
    if (status != kLiteRtStatusOk) {
        LOGE("SetOptionsHardwareAccelerators failed: %d", status);
        return false;
    }
    LOGD("Hardware accelerators: NPU + CPU");

    // 7. Set MediaTek-specific options (vendor extension from dispatch plugin)
    //    If not available, NPU still works with default settings.
    if (m_api.SetMediatekPerformanceMode) {
        // FastSingleAnswer = 1 (kLiteRtMediatekNeuronAdapterPerformanceModeNeuronPreferFastSingleAnswer)
        m_api.SetMediatekPerformanceMode(m_options, 1);
        LOGD("MediaTek performance mode: FastSingleAnswer");
    }
    if (m_api.SetMediatekOptimizationHint) {
        // LowLatency = 1 (kLiteRtMediatekNeuronAdapterOptimizationHintLowLatency)
        m_api.SetMediatekOptimizationHint(m_options, 1);
        LOGD("MediaTek optimization hint: LowLatency");
    }
    if (m_api.SetMediatekNeronSDKVersionType) {
        // Version9 = 2 (kLiteRtMediatekOptionsNeronSDKVersionTypeVersion9)
        m_api.SetMediatekNeronSDKVersionType(m_options, 2);
        LOGD("MediaTek Neuron SDK version: 9");
    }

    // 8. Load model
    status = m_api.CreateModelFromFile(model_path, &m_model);
    if (status != kLiteRtStatusOk) {
        LOGE("LiteRtCreateModelFromFile failed: %d (%s)", status, model_path);
        return false;
    }
    LOGD("Model loaded: %s", model_path);

    // 9. Compile model (triggers NPU compilation)
    long long t1 = getTimeUs();
    status = m_api.CreateCompiledModel(m_environment, m_model, m_options, &m_compiled_model);
    long long t2 = getTimeUs();
    if (status != kLiteRtStatusOk) {
        LOGE("LiteRtCreateCompiledModel failed: %d", status);
        return false;
    }
    LOGD("Compiled model created in %lld us", t2 - t1);

    // 14. Get main subgraph
    int main_subgraph_idx = 0;
    status = m_api.GetMainModelSubgraphIndex(m_model, &main_subgraph_idx);
    if (status != kLiteRtStatusOk) {
        LOGE("GetMainModelSubgraphIndex failed: %d", status);
        return false;
    }

    LiteRtSubgraph subgraph = nullptr;
    status = m_api.GetModelSubgraph(m_model, main_subgraph_idx, &subgraph);
    if (status != kLiteRtStatusOk || !subgraph) {
        LOGE("GetModelSubgraph failed: %d", status);
        return false;
    }

    // 15. Setup input
    int num_inputs = 0;
    status = m_api.GetNumSubgraphInputs(subgraph, &num_inputs);
    if (status != kLiteRtStatusOk || num_inputs == 0) {
        LOGE("No subgraph inputs: %d", status);
        return false;
    }

    LiteRtTensor input_tensor = nullptr;
    status = m_api.GetSubgraphInput(subgraph, 0, &input_tensor);
    if (status != kLiteRtStatusOk || !input_tensor) {
        LOGE("GetSubgraphInput failed: %d", status);
        return false;
    }

    status = m_api.GetRankedTensorType(input_tensor, &m_input_tensor_type);
    if (status != kLiteRtStatusOk) {
        LOGE("GetRankedTensorType(input) failed: %d", status);
        return false;
    }

    // Query runtime tensor layout (NPU may change layout from model's original)
    if (m_api.GetInputTensorLayout) {
        if (m_api.GetInputTensorLayout(m_compiled_model, 0, 0, &m_input_runtime_layout) == kLiteRtStatusOk) {
            if (m_input_runtime_layout.rank > 0) {
                m_input_tensor_type.layout.rank = m_input_runtime_layout.rank;
                memcpy(m_input_tensor_type.layout.dimensions,
                       m_input_runtime_layout.dimensions,
                       sizeof(int32_t) * LITERT_TENSOR_MAX_RANK);
                LOGD("Input runtime layout: rank=%d, dims=[%d,%d,%d,%d]",
                     m_input_runtime_layout.rank,
                     m_input_runtime_layout.dimensions[0],
                     m_input_runtime_layout.rank > 1 ? m_input_runtime_layout.dimensions[1] : 0,
                     m_input_runtime_layout.rank > 2 ? m_input_runtime_layout.dimensions[2] : 0,
                     m_input_runtime_layout.rank > 3 ? m_input_runtime_layout.dimensions[3] : 0);
            }
        }
    }

    LiteRtTensorBufferRequirements input_reqs = nullptr;
    status = m_api.GetInputBufferRequirements(m_compiled_model, 0, 0, &input_reqs);
    if (status != kLiteRtStatusOk) {
        LOGE("GetInputBufferRequirements failed: %d", status);
        return false;
    }

    status = m_api.CreateManagedTensorBufferFromReq(
        m_environment, &m_input_tensor_type, input_reqs, &m_input_buffer);
    if (status != kLiteRtStatusOk) {
        LOGE("CreateManagedTensorBuffer(input) failed: %d", status);
        return false;
    }

    {
        size_t input_packed = 0;
        status = m_api.GetTensorBufferPackedSize(m_input_buffer, &input_packed);
        if (status != kLiteRtStatusOk) {
            LOGE("GetTensorBufferPackedSize(input) failed: %d", status);
            return false;
        }
        m_input_packed_size = input_packed;
    }
    LOGD("Input buffer: %zu bytes, element_type=%d", m_input_packed_size, m_input_tensor_type.element_type);

    // 16. Setup outputs
    int num_outputs = 0;
    status = m_api.GetNumSubgraphOutputs(subgraph, &num_outputs);
    if (status != kLiteRtStatusOk || num_outputs == 0) {
        LOGE("No subgraph outputs: %d", status);
        return false;
    }

    m_outputs.resize(num_outputs);

    // Query all output runtime layouts at once (plural API)
    m_output_runtime_layouts.resize(num_outputs);
    if (m_api.GetOutputTensorLayouts) {
        m_api.GetOutputTensorLayouts(m_compiled_model, 0, num_outputs,
                                     m_output_runtime_layouts.data(), false);
    }

    for (int i = 0; i < num_outputs; i++) {
        LiteRtTensor output_tensor = nullptr;
        status = m_api.GetSubgraphOutput(subgraph, i, &output_tensor);
        if (status != kLiteRtStatusOk || !output_tensor) {
            LOGE("GetSubgraphOutput(%d) failed: %d", i, status);
            return false;
        }

        status = m_api.GetRankedTensorType(output_tensor, &m_outputs[i].tensor_type);
        if (status != kLiteRtStatusOk) {
            LOGE("GetRankedTensorType(output %d) failed: %d", i, status);
            return false;
        }

        // Log model output dimensions
        LOGD("Output %d model dims: rank=%d, dims=[%d,%d,%d,%d]", i,
             m_outputs[i].tensor_type.layout.rank,
             m_outputs[i].tensor_type.layout.rank > 0 ? m_outputs[i].tensor_type.layout.dimensions[0] : 0,
             m_outputs[i].tensor_type.layout.rank > 1 ? m_outputs[i].tensor_type.layout.dimensions[1] : 0,
             m_outputs[i].tensor_type.layout.rank > 2 ? m_outputs[i].tensor_type.layout.dimensions[2] : 0,
             m_outputs[i].tensor_type.layout.rank > 3 ? m_outputs[i].tensor_type.layout.dimensions[3] : 0);

        // Apply runtime layout if available
        if (m_output_runtime_layouts[i].rank > 0) {
            m_outputs[i].tensor_type.layout.rank = m_output_runtime_layouts[i].rank;
            memcpy(m_outputs[i].tensor_type.layout.dimensions,
                   m_output_runtime_layouts[i].dimensions,
                   sizeof(int32_t) * LITERT_TENSOR_MAX_RANK);
            LOGD("Output %d runtime dims: rank=%d, dims=[%d,%d,%d,%d]", i,
                 m_output_runtime_layouts[i].rank,
                 m_output_runtime_layouts[i].rank > 0 ? m_output_runtime_layouts[i].dimensions[0] : 0,
                 m_output_runtime_layouts[i].rank > 1 ? m_output_runtime_layouts[i].dimensions[1] : 0,
                 m_output_runtime_layouts[i].rank > 2 ? m_output_runtime_layouts[i].dimensions[2] : 0,
                 m_output_runtime_layouts[i].rank > 3 ? m_output_runtime_layouts[i].dimensions[3] : 0);
        }

        LiteRtTensorBufferRequirements output_reqs = nullptr;
        status = m_api.GetOutputBufferRequirements(m_compiled_model, 0, i, &output_reqs);
        if (status != kLiteRtStatusOk) {
            LOGE("GetOutputBufferRequirements(%d) failed: %d", i, status);
            return false;
        }

        status = m_api.CreateManagedTensorBufferFromReq(
            m_environment, &m_outputs[i].tensor_type, output_reqs, &m_outputs[i].buffer);
        if (status != kLiteRtStatusOk) {
            LOGE("CreateManagedTensorBuffer(output %d) failed: %d", i, status);
            return false;
        }

        {
            size_t out_packed = 0;
            status = m_api.GetTensorBufferPackedSize(m_outputs[i].buffer, &out_packed);
            m_outputs[i].packed_size = out_packed;
        }
        if (status != kLiteRtStatusOk) {
            LOGE("GetTensorBufferPackedSize(output %d) failed: %d", i, status);
            return false;
        }

        // Query quantization params (from IDA @ 0x0A280-0x0A2AC)
        int quant_type_id = 0;
        m_api.GetQuantizationTypeId(output_tensor, &quant_type_id);
        if (quant_type_id == 1) {
            m_api.GetPerTensorQuantization(output_tensor, &m_outputs[i].quant_params);
            m_outputs[i].has_per_tensor_quant = true;
            LOGD("Output %d: per-tensor quant: scale=%f, zp=%d",
                 i, m_outputs[i].quant_params.scale, m_outputs[i].quant_params.zero_point);
        }

        LOGD("Output %d: %zu bytes, element_type=%d", i, m_outputs[i].packed_size, m_outputs[i].tensor_type.element_type);
    }

    // 17. Infer num_classes from output tensor dimensions
    // YOLOv8 output shape: [1, 4+num_classes, num_outputs]
    if (!m_outputs.empty() && m_outputs[0].tensor_type.layout.rank >= 3) {
        const int32_t* out_dims = m_outputs[0].tensor_type.layout.dimensions;
        if (out_dims) {
            // dims[0]=batch(1), dims[1]=channels(4+num_classes), dims[2]=num_outputs
            int channels = out_dims[1];
            if (channels > 4) {
                m_num_classes = channels - 4;
                LOGD("Inferred m_num_classes = %d (channels=%d)", m_num_classes, channels);
            } else {
                m_num_classes = 1;
                LOGD("Using default m_num_classes = 1 (channels=%d)", channels);
            }
        }
    }

    // 18. Infer model input dimensions from tensor type
    if (m_input_tensor_type.layout.rank >= 3) {
        // Assume NHWC or NCHW: dimensions[0]=batch, then H,W,C or C,H,W
        const int32_t* dims = m_input_tensor_type.layout.dimensions;
        if (dims) {
            // For YOLOv8: [1, H, W, 3] (NHWC) or [1, 3, H, W] (NCHW)
            if (m_input_tensor_type.layout.rank == 4) {
                if (dims[3] == 3) {
                    // NHWC
                    m_input_nhwc = true;
                    m_input_height = dims[1];
                    m_input_width = dims[2];
                } else {
                    // NCHW
                    m_input_nhwc = false;
                    m_input_height = dims[2];
                    m_input_width = dims[3];
                }
            }
            LOGD("Input: %s, H=%d, W=%d", m_input_nhwc ? "NHWC" : "NCHW", m_input_height, m_input_width);
        }
    }

    // 19. Infer num_outputs from output packed size
    // Output shape: [1, 4+num_classes, num_outputs]
    if (!m_outputs.empty()) {
        size_t total_bytes = m_outputs[0].packed_size;
        // Assume float32 (4 bytes per element)
        int total_floats = (int)(total_bytes / 4);
        m_num_outputs = total_floats / (4 + m_num_classes);
        if (m_num_outputs < 1) m_num_outputs = 1344;
        LOGD("Estimated num_outputs: %d", m_num_outputs);
    }

    m_initialized = true;
    LOGD("MTK NPU engine initialized successfully");
    return true;
}

void MtkEngine::release() {
    // Destroy in reverse order of creation
    for (auto& out : m_outputs) {
        if (out.buffer && m_api.DestroyTensorBuffer) {
            m_api.DestroyTensorBuffer(out.buffer);
        }
        out.buffer = nullptr;
    }
    m_outputs.clear();

    if (m_input_buffer && m_api.DestroyTensorBuffer) {
        m_api.DestroyTensorBuffer(m_input_buffer);
        m_input_buffer = nullptr;
    }
    if (m_compiled_model && m_api.DestroyCompiledModel) {
        m_api.DestroyCompiledModel(m_compiled_model);
        m_compiled_model = nullptr;
    }
    if (m_model && m_api.DestroyModel) {
        m_api.DestroyModel(m_model);
        m_model = nullptr;
    }
    if (m_options && m_api.DestroyOptions) {
        m_api.DestroyOptions(m_options);
        m_options = nullptr;
    }
    if (m_environment && m_api.DestroyEnvironment) {
        m_api.DestroyEnvironment(m_environment);
        m_environment = nullptr;
    }

    m_api.unload();
    m_input_packed_size = 0;
    m_input_tensor_type = {};
    m_initialized = false;
}

//==============================================================================
//  Detect
//==============================================================================
std::vector<Detection> MtkEngine::detect(
    uint8_t* src,
    int offsetX, int offsetY,
    int regionWidth, int regionHeight,
    int screenWidth, int screenHeight,
    int rowStride, int pixelStride)
{
    if (!m_initialized || !m_input_buffer || m_outputs.empty()) return {};

    int H = m_input_height;
    int W = m_input_width;

    // Precompute coordinate LUTs
    std::vector<int> srcX_lut(W);
    std::vector<int> srcY_lut(H);
    for (int x = 0; x < W; ++x) srcX_lut[x] = offsetX + x * regionWidth / W;
    for (int y = 0; y < H; ++y) srcY_lut[y] = offsetY + y * regionHeight / H;

    static const float inv255 = 1.0f / 255.0f;

    // 1. Write input to buffer
    void* input_ptr = nullptr;
    LiteRtStatus status = m_api.LockTensorBuffer(m_input_buffer, &input_ptr,
                                                  kLiteRtTensorBufferLockModeReadWrite);
    if (status != kLiteRtStatusOk || !input_ptr) {
        LOGE("LockTensorBuffer(input) failed: %d", status);
        return {};
    }

    // Determine input type from element_type
    // element_type: 1=FLOAT32, 2=INT32, 3=INT64, 9=INT8, 10=UINT8
    int elem_type = m_input_tensor_type.element_type;
    int input_elements = W * H * 3;

    if (elem_type == 1) {
        // FLOAT32: pixel / 255.0
        float* data = static_cast<float*>(input_ptr);
        for (int y = 0; y < H; ++y) {
            int baseRow = srcY_lut[y] * rowStride;
            for (int x = 0; x < W; ++x) {
                int srcIdx = baseRow + srcX_lut[x] * pixelStride;
                int idx = (y * W + x) * 3;
                data[idx + 0] = src[srcIdx + 0] * inv255;
                data[idx + 1] = src[srcIdx + 1] * inv255;
                data[idx + 2] = src[srcIdx + 2] * inv255;
            }
        }
    } else if (elem_type == 9) {
        // INT8: quantize with default scale
        int8_t* data = static_cast<int8_t*>(input_ptr);
        float scale = 1.0f / 127.0f;
        int zp = 0;
        for (int y = 0; y < H; ++y) {
            int baseRow = srcY_lut[y] * rowStride;
            for (int x = 0; x < W; ++x) {
                int srcIdx = baseRow + srcX_lut[x] * pixelStride;
                int idx = (y * W + x) * 3;
                data[idx + 0] = (int8_t)std::round(src[srcIdx + 0] * inv255 / scale + zp);
                data[idx + 1] = (int8_t)std::round(src[srcIdx + 1] * inv255 / scale + zp);
                data[idx + 2] = (int8_t)std::round(src[srcIdx + 2] * inv255 / scale + zp);
            }
        }
    } else {
        // UINT8 or other: raw pixel values
        uint8_t* data = static_cast<uint8_t*>(input_ptr);
        for (int y = 0; y < H; ++y) {
            int baseRow = srcY_lut[y] * rowStride;
            for (int x = 0; x < W; ++x) {
                int srcIdx = baseRow + srcX_lut[x] * pixelStride;
                int idx = (y * W + x) * 3;
                data[idx + 0] = src[srcIdx + 0];
                data[idx + 1] = src[srcIdx + 1];
                data[idx + 2] = src[srcIdx + 2];
            }
        }
    }

    m_api.UnlockTensorBuffer(m_input_buffer);

    // 2. Run inference
    long long t1 = getTimeUs();

    // Build buffer arrays for all outputs
    std::vector<LiteRtTensorBuffer> output_buffers(m_outputs.size());
    for (size_t i = 0; i < m_outputs.size(); i++) {
        output_buffers[i] = m_outputs[i].buffer;
    }

    status = m_api.RunCompiledModel(
        m_compiled_model,
        0,  // signature_index
        1, &m_input_buffer,
        (int)output_buffers.size(), output_buffers.data());

    long long t2 = getTimeUs();
    if (status != kLiteRtStatusOk) {
        LOGE("RunCompiledModel failed: %d", status);
        return {};
    }
    LOGD("MTK NPU Inference: %lld us", t2 - t1);

    // 3. Read output (use first output for YOLOv8)
    void* output_ptr = nullptr;
    status = m_api.LockTensorBuffer(m_outputs[0].buffer, &output_ptr,
                                     kLiteRtTensorBufferLockModeRead);
    if (status != kLiteRtStatusOk || !output_ptr) {
        LOGE("LockTensorBuffer(output) failed: %d", status);
        return {};
    }

    LOGD("Post-process: num_outputs=%d, num_classes=%d, out_dims=[%d,%d,%d,%d], packed=%zu",
         m_num_outputs, m_num_classes,
         m_outputs[0].tensor_type.layout.rank > 0 ? m_outputs[0].tensor_type.layout.dimensions[0] : 0,
         m_outputs[0].tensor_type.layout.rank > 1 ? m_outputs[0].tensor_type.layout.dimensions[1] : 0,
         m_outputs[0].tensor_type.layout.rank > 2 ? m_outputs[0].tensor_type.layout.dimensions[2] : 0,
         m_outputs[0].tensor_type.layout.rank > 3 ? m_outputs[0].tensor_type.layout.dimensions[3] : 0,
         m_outputs[0].packed_size);

    // Debug: dump first 20 raw float values from output
    {
        float* dbg = static_cast<float*>(output_ptr);
        int total = (int)(m_outputs[0].packed_size / 4);
        int n = total < 20 ? total : 20;
        char buf[512] = {0};
        int off = 0;
        for (int k = 0; k < n; k++) {
            off += snprintf(buf + off, sizeof(buf) - off, "%.4f ", dbg[k]);
        }
        LOGD("Output raw[0..%d]: %s (total=%d floats)", n - 1, buf, total);
    }

    std::vector<Detection> detections;
    detections.reserve(m_num_outputs);

    float invW = 1.0f / screenWidth;
    float invH = 1.0f / screenHeight;

    auto normalizeIfNeeded = [this](float cx, float cy, float bw, float bh,
                                     float& ncx, float& ncy, float& nbw, float& nbh) {
        if (cx > 1.5f || cy > 1.5f) {
            float inv = 1.0f / (float)m_input_width;
            ncx = cx * inv; ncy = cy * inv;
            nbw = bw * inv; nbh = bh * inv;
        } else {
            ncx = cx; ncy = cy; nbw = bw; nbh = bh;
        }
    };

    int out_elem_type = m_outputs[0].tensor_type.element_type;
    float out_scale = m_outputs[0].quant_params.scale;
    int out_zp = m_outputs[0].quant_params.zero_point;

    if (out_elem_type == 1) {
        // Float32 output
        float* data = static_cast<float*>(output_ptr);
        for (int i = 0; i < m_num_outputs; ++i) {
            float cx_raw = data[i];
            float cy_raw = data[m_num_outputs + i];
            float bw_raw = data[2 * m_num_outputs + i];
            float bh_raw = data[3 * m_num_outputs + i];
            float cx, cy, bw, bh;
            normalizeIfNeeded(cx_raw, cy_raw, bw_raw, bh_raw, cx, cy, bw, bh);

            float score;
            int classId = 0;
            if (m_num_classes <= 1) {
                score = data[4 * m_num_outputs + i];
            } else {
                float maxProb = -1e9f;
                int maxClass = 0;
                for (int c = 0; c < m_num_classes; c++) {
                    float prob = data[(4 + c) * m_num_outputs + i];
                    if (prob > maxProb) { maxProb = prob; maxClass = c; }
                }
                score = maxProb;
                classId = maxClass;
            }

            if (score < m_conf_thresh) continue;
            if (bw <= 0 || bh <= 0) continue;
            if (cx < 0 || cx > 1 || cy < 0 || cy > 1) continue;

            float hw = bw * 0.5f, hh = bh * 0.5f;
            detections.push_back({
                (offsetX + (cx - hw) * regionWidth) * invW,
                (offsetY + (cy - hh) * regionHeight) * invH,
                (offsetX + (cx + hw) * regionWidth) * invW,
                (offsetY + (cy + hh) * regionHeight) * invH,
                score, (float)classId
            });
        }
    } else if (out_elem_type == 9) {
        // INT8 output with dequantization
        int8_t* data = static_cast<int8_t*>(output_ptr);
        for (int i = 0; i < m_num_outputs; ++i) {
            float cx_raw = (data[i] - out_zp) * out_scale;
            float cy_raw = (data[m_num_outputs + i] - out_zp) * out_scale;
            float bw_raw = (data[2 * m_num_outputs + i] - out_zp) * out_scale;
            float bh_raw = (data[3 * m_num_outputs + i] - out_zp) * out_scale;
            float cx, cy, bw, bh;
            normalizeIfNeeded(cx_raw, cy_raw, bw_raw, bh_raw, cx, cy, bw, bh);

            float score;
            int classId = 0;
            if (m_num_classes <= 1) {
                score = (data[4 * m_num_outputs + i] - out_zp) * out_scale;
            } else {
                float maxProb = -1e9f;
                int maxClass = 0;
                for (int c = 0; c < m_num_classes; c++) {
                    float prob = (data[(4 + c) * m_num_outputs + i] - out_zp) * out_scale;
                    if (prob > maxProb) { maxProb = prob; maxClass = c; }
                }
                score = maxProb;
                classId = maxClass;
            }

            if (score < m_conf_thresh) continue;
            if (bw <= 0 || bh <= 0) continue;
            if (cx < 0 || cx > 1 || cy < 0 || cy > 1) continue;

            float hw = bw * 0.5f, hh = bh * 0.5f;
            detections.push_back({
                (offsetX + (cx - hw) * regionWidth) * invW,
                (offsetY + (cy - hh) * regionHeight) * invH,
                (offsetX + (cx + hw) * regionWidth) * invW,
                (offsetY + (cy + hh) * regionHeight) * invH,
                score, (float)classId
            });
        }
    } else {
        // UINT8 or other: treat as float (bridge may have done dequantization)
        float* data = static_cast<float*>(output_ptr);
        for (int i = 0; i < m_num_outputs; ++i) {
            float cx_raw = data[i];
            float cy_raw = data[m_num_outputs + i];
            float bw_raw = data[2 * m_num_outputs + i];
            float bh_raw = data[3 * m_num_outputs + i];
            float cx, cy, bw, bh;
            normalizeIfNeeded(cx_raw, cy_raw, bw_raw, bh_raw, cx, cy, bw, bh);

            float score;
            int classId = 0;
            if (m_num_classes <= 1) {
                score = data[4 * m_num_outputs + i];
            } else {
                float maxProb = -1e9f;
                int maxClass = 0;
                for (int c = 0; c < m_num_classes; c++) {
                    float prob = data[(4 + c) * m_num_outputs + i];
                    if (prob > maxProb) { maxProb = prob; maxClass = c; }
                }
                score = maxProb;
                classId = maxClass;
            }

            if (score < m_conf_thresh) continue;
            if (bw <= 0 || bh <= 0) continue;
            if (cx < 0 || cx > 1 || cy < 0 || cy > 1) continue;

            float hw = bw * 0.5f, hh = bh * 0.5f;
            detections.push_back({
                (offsetX + (cx - hw) * regionWidth) * invW,
                (offsetY + (cy - hh) * regionHeight) * invH,
                (offsetX + (cx + hw) * regionWidth) * invW,
                (offsetY + (cy + hh) * regionHeight) * invH,
                score, (float)classId
            });
        }
    }

    m_api.UnlockTensorBuffer(m_outputs[0].buffer);

    LOGD("MTK NPU Raw: %zu", detections.size());

    auto finalDetections = nms(detections, 0.45f);
    return finalDetections;
}
