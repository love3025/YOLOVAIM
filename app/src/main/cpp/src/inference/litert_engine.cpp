#include "litert_engine.h"
#include <dlfcn.h>
#include <cstdio>

//==============================================================================
//  GPU Delegate Builder (universal, stays in LiteRtEngine)
//==============================================================================
TfLiteDelegate* LiteRtEngine::buildGpuDelegate() {
    TfLiteGpuDelegateOptionsV2 gpu_options = TfLiteGpuDelegateOptionsV2Default();
    gpu_options.inference_preference = TFLITE_GPU_INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER;
    gpu_options.inference_priority1 = TFLITE_GPU_INFERENCE_PRIORITY_MIN_LATENCY;
    gpu_options.inference_priority2 = TFLITE_GPU_INFERENCE_PRIORITY_MAX_PRECISION;
    gpu_options.inference_priority3 = TFLITE_GPU_INFERENCE_PRIORITY_AUTO;

    TfLiteDelegate* delegate = TfLiteGpuDelegateV2Create(&gpu_options);
    if (delegate) LOGD("GPU delegate created (OpenCL/OpenGL)");
    else          LOGW("GPU delegate creation failed");
    return delegate;
}

void LiteRtEngine::deleteDelegate() {
    if (m_delegate) {
        if (m_backend_type == "QNN HTP") {
            m_qnn_engine.deleteDelegate();
        } else if (m_backend_type == "GPU") {
            TfLiteGpuDelegateV2Delete(m_delegate);
        }
        m_delegate = nullptr;
    }
}

//==============================================================================
//  Lifecycle
//==============================================================================
LiteRtEngine::LiteRtEngine() = default;

LiteRtEngine::~LiteRtEngine() {
    release();
}

void LiteRtEngine::setQnnModelToken(const char* token) {
    m_qnn_model_token = token ? token : "";
}

bool LiteRtEngine::init(const char* model_path) {
    release();

    m_model = TfLiteModelCreateFromFile(model_path);
    if (!m_model) {
        LOGE("Failed to load model: %s", model_path);
        return false;
    }

    // Delegate fallback chain:
    // Try platform-specific NPU first (QNN HTP / Neuron)
    // Then universal GPU
    // Finally CPU
    struct DelegateTrial {
        std::function<TfLiteDelegate*()> builder;
        std::string name;
    };

    std::vector<DelegateTrial> trials;
    if (!m_force_cpu) {
        // Hand the per-model token to QnnEngine before any buildDelegate() call,
        // so the cache file is uniquely named and warm-up work survives across
        // cold launches (fingerprint match skips HTP graph compile on reuse).
        if (!m_qnn_model_token.empty()) {
            m_qnn_engine.setModelToken(m_qnn_model_token.c_str());
        }
        // Platform-specific NPU delegates
        trials.push_back({[this]{ return m_qnn_engine.buildDelegate(); }, "QNN HTP"});
        // trials.push_back({[this]{ return m_neuron_engine.buildDelegate(); }, "Neuron"});  // TODO

        // Universal GPU delegate
        trials.push_back({[this]{ return buildGpuDelegate(); }, "GPU"});
    }

    bool interpreter_created = false;
    for (auto& trial : trials) {
        LOGD("Trying %s delegate...", trial.name.c_str());
        TfLiteDelegate* del = trial.builder();
        if (!del) {
            LOGD("%s delegate not available, skipping", trial.name.c_str());
            continue;
        }

        TfLiteInterpreterOptions* trial_opts = TfLiteInterpreterOptionsCreate();
        TfLiteInterpreterOptionsAddDelegate(trial_opts, del);
        TfLiteInterpreterOptionsSetNumThreads(trial_opts, 1);

        TfLiteInterpreter* interp = TfLiteInterpreterCreate(m_model, trial_opts);
        TfLiteInterpreterOptionsDelete(trial_opts);

        if (interp) {
            m_delegate = del;
            m_interpreter = interp;
            m_backend_type = trial.name;
            LOGD("Interpreter created with %s delegate", trial.name.c_str());
            interpreter_created = true;
            break;
        } else {
            LOGW("%s delegate: interpreter creation failed", trial.name.c_str());
            // Clean up failed delegate
            if (trial.name == "QNN HTP") m_qnn_engine.deleteDelegate();
            else if (trial.name == "GPU") TfLiteGpuDelegateV2Delete(del);
        }
    }

if (!interpreter_created) {
    m_delegate = nullptr;
    m_backend_type = "CPU";
    LOGD("Falling back to built-in CPU kernel (XNNPACK-accelerated)");

    TfLiteInterpreterOptions* cpu_opts = TfLiteInterpreterOptionsCreate();
    TfLiteInterpreterOptionsSetNumThreads(cpu_opts, m_cpu_threads);

    m_interpreter = TfLiteInterpreterCreate(m_model, cpu_opts);
    TfLiteInterpreterOptionsDelete(cpu_opts);

    if (!m_interpreter) {
        LOGE("Failed to create CPU interpreter");
        TfLiteModelDelete(m_model);
        m_model = nullptr;
        return false;
    }
}

    if (TfLiteInterpreterAllocateTensors(m_interpreter) != kTfLiteOk) {
        LOGE("Failed to allocate tensors");
        release();
        return false;
    }

    // Get input tensor info
    int input_count = TfLiteInterpreterGetInputTensorCount(m_interpreter);
    if (input_count > 0) {
        const TfLiteTensor* input_tensor = TfLiteInterpreterGetInputTensor(m_interpreter, 0);
        if (input_tensor) {
            int ndim = TfLiteTensorNumDims(input_tensor);
            int dim1 = TfLiteTensorDim(input_tensor, 1);
            int dim2 = TfLiteTensorDim(input_tensor, 2);
            if (ndim >= 4) {
                int dim3 = TfLiteTensorDim(input_tensor, 3);
                if (dim3 == 3 && dim1 != 3) {
                    m_input_nhwc = true;
                    m_input_height = dim1;
                    m_input_width = dim2;
                } else {
                    m_input_nhwc = false;
                    m_input_height = dim1;
                    m_input_width = dim2;
                }
            } else {
                m_input_nhwc = false;
                m_input_height = dim1;
                m_input_width = dim2;
            }
            LOGD("Input: %s, H=%d, W=%d", m_input_nhwc ? "NHWC" : "NCHW", m_input_height, m_input_width);
        }
    }

    // Get output tensor info
    {
        const TfLiteTensor* out = TfLiteInterpreterGetOutputTensor(m_interpreter, 0);
        if (out) {
            int ndim = TfLiteTensorNumDims(out);
            m_num_outputs = TfLiteTensorDim(out, ndim - 1);
            int channels = TfLiteTensorDim(out, 1);
            m_num_classes = channels - 4;
            if (m_num_classes < 1) m_num_classes = 1;
            LOGD("Output: dims=%d, channels=%d, num_outputs=%d, num_classes=%d",
                 ndim, channels, m_num_outputs, m_num_classes);
        }
    }

    m_initialized = true;
    LOGD("LiteRT initialized, backend: %s", m_backend_type.c_str());
    return true;
}

void LiteRtEngine::release() {
    if (m_interpreter) {
        TfLiteInterpreterDelete(m_interpreter);
        m_interpreter = nullptr;
    }
    deleteDelegate();
    if (m_model) {
        TfLiteModelDelete(m_model);
        m_model = nullptr;
    }
    m_initialized = false;
}

//==============================================================================
//  Detect
//==============================================================================
std::vector<Detection> LiteRtEngine::detect(
    uint8_t* src,
    int offsetX, int offsetY,
    int regionWidth, int regionHeight,
    int screenWidth, int screenHeight,
    int rowStride, int pixelStride)
{
    if (!m_interpreter) return {};

    long long tPreStart = getTimeUs();

    TfLiteTensor* input_tensor = TfLiteInterpreterGetInputTensor(m_interpreter, 0);
    if (!input_tensor) return {};

    int H = m_input_height;
    int W = m_input_width;

    // Coordinate LUTs. These depend only on the crop rect and the model input
    // size, and neither changes between frames unless the user drags the
    // 截取范围 slider — so rebuilding them every frame cost two heap
    // allocations plus W+H integer divisions to produce an identical table.
    // Cached the same way the quantize LUTs below are; detect() runs on the
    // single inference thread.
    static std::vector<int> s_srcX_lut;
    static std::vector<int> s_srcY_lut;
    static int s_lutOffX = -1, s_lutOffY = -1;
    static int s_lutRegW = -1, s_lutRegH = -1;
    static int s_lutW = -1, s_lutH = -1;
    if (offsetX != s_lutOffX || offsetY != s_lutOffY ||
        regionWidth != s_lutRegW || regionHeight != s_lutRegH ||
        W != s_lutW || H != s_lutH) {
        s_srcX_lut.resize(W);
        s_srcY_lut.resize(H);
        for (int x = 0; x < W; ++x) s_srcX_lut[x] = offsetX + x * regionWidth / W;
        for (int y = 0; y < H; ++y) s_srcY_lut[y] = offsetY + y * regionHeight / H;
        s_lutOffX = offsetX; s_lutOffY = offsetY;
        s_lutRegW = regionWidth; s_lutRegH = regionHeight;
        s_lutW = W; s_lutH = H;
    }
    const std::vector<int>& srcX_lut = s_srcX_lut;
    const std::vector<int>& srcY_lut = s_srcY_lut;

    static const float inv255 = 1.0f / 255.0f;

    TfLiteType input_type = TfLiteTensorType(input_tensor);
    void* input_data = TfLiteTensorData(input_tensor);
    if (!input_data) return {};

    TfLiteQuantizationParams qp_input = TfLiteTensorQuantizationParams(input_tensor);

    if (input_type == kTfLiteInt8) {
        int8_t* data = static_cast<int8_t*>(input_data);
        float input_scale = qp_input.scale;
        int input_zero_point = qp_input.zero_point;

        // Build/cached quantize LUT: precompute (round(p * inv255 / scale + zp)) for every (channel, pixel).
        // The scale/zp only change when the model is reloaded, so a static cache amortizes
        // 3*256 mul+round per call down to 3*256 table lookups. ~3-5x faster than per-pixel math.
        static int8_t s_quantLUT[3][256];
        static float s_cachedScale = 0.0f;
        static int s_cachedZp = 0;
        static bool s_quantLUTReady = false;
        if (!s_quantLUTReady || input_scale != s_cachedScale || input_zero_point != s_cachedZp) {
            const float invScale = (1.0f / 255.0f) / input_scale;
            for (int c = 0; c < 3; c++) {
                for (int p = 0; p < 256; p++) {
                    int v = (int)std::roundf(p * invScale + input_zero_point);
                    if (v < -128) v = -128;
                    else if (v > 127) v = 127;
                    s_quantLUT[c][p] = (int8_t)v;
                }
            }
            s_cachedScale = input_scale;
            s_cachedZp = input_zero_point;
            s_quantLUTReady = true;
        }

        // Single-thread scalar LUT lookup. ~0.4-0.6ms on a big core, stable under
        // CPU contention because there's no OpenMP fork-join or NEON gather overhead.
        const int8_t* lutR = s_quantLUT[0];
        const int8_t* lutG = s_quantLUT[1];
        const int8_t* lutB = s_quantLUT[2];
        const int* xLut = srcX_lut.data();
        const int ps = pixelStride;
        for (int y = 0; y < H; y++) {
            const uint8_t* srcRow = src + srcY_lut[y] * rowStride;
            int8_t* dstRow = data + (size_t)y * W * 3;
            for (int x = 0; x < W; x++) {
                const uint8_t* p = srcRow + xLut[x] * ps;
                dstRow[0] = lutR[p[0]];
                dstRow[1] = lutG[p[1]];
                dstRow[2] = lutB[p[2]];
                dstRow += 3;
            }
        }
    } else if (input_type == kTfLiteUInt8) {
        uint8_t* data = static_cast<uint8_t*>(input_data);
        float input_scale = qp_input.scale;
        int input_zero_point = qp_input.zero_point;

        // Same LUT trick for uint8 quantize
        static int8_t s_u8QuantLUT[3][256];
        static float s_u8CachedScale = 0.0f;
        static int s_u8CachedZp = 0;
        static bool s_u8LUTReady = false;
        if (!s_u8LUTReady || input_scale != s_u8CachedScale || input_zero_point != s_u8CachedZp) {
            const float invScale = (1.0f / 255.0f) / input_scale;
            for (int c = 0; c < 3; c++) {
                for (int p = 0; p < 256; p++) {
                    int v = (int)std::roundf(p * invScale + input_zero_point);
                    if (v < 0) v = 0;
                    else if (v > 255) v = 255;
                    s_u8QuantLUT[c][p] = (int8_t)v;
                }
            }
            s_u8CachedScale = input_scale;
            s_u8CachedZp = input_zero_point;
            s_u8LUTReady = true;
        }

        const int* xLut = srcX_lut.data();
        const int ps = pixelStride;
        for (int y = 0; y < H; y++) {
            const uint8_t* srcRow = src + srcY_lut[y] * rowStride;
            uint8_t* dstRow = data + (size_t)y * W * 3;
            for (int x = 0; x < W; x++) {
                const uint8_t* p = srcRow + xLut[x] * ps;
                dstRow[0] = (uint8_t)s_u8QuantLUT[0][p[0]];
                dstRow[1] = (uint8_t)s_u8QuantLUT[1][p[1]];
                dstRow[2] = (uint8_t)s_u8QuantLUT[2][p[2]];
                dstRow += 3;
            }
        }
    } else {
        float* data = static_cast<float*>(input_data);

        // Float path: LUT for inv255 multiplication (saves 1 mul per channel per pixel)
        static float s_floatLUT[3][256];
        static bool s_floatLUTReady = false;
        if (!s_floatLUTReady) {
            for (int c = 0; c < 3; c++) {
                for (int p = 0; p < 256; p++) {
                    s_floatLUT[c][p] = p * inv255;
                }
            }
            s_floatLUTReady = true;
        }

        const int* xLut = srcX_lut.data();
        const int ps = pixelStride;
        for (int y = 0; y < H; y++) {
            const uint8_t* srcRow = src + srcY_lut[y] * rowStride;
            float* dstRow = data + (size_t)y * W * 3;
            for (int x = 0; x < W; x++) {
                const uint8_t* p = srcRow + xLut[x] * ps;
                dstRow[0] = s_floatLUT[0][p[0]];
                dstRow[1] = s_floatLUT[1][p[1]];
                dstRow[2] = s_floatLUT[2][p[2]];
                dstRow += 3;
            }
        }
    }
    long long tPreEnd = getTimeUs();

    long long t1 = getTimeUs();

    if (TfLiteInterpreterInvoke(m_interpreter) != kTfLiteOk) {
        LOGE("Inference failed");
        return {};
    }

    long long t2 = getTimeUs();
    LOGTRACE("LiteRT Inference: %lld us", t2 - t1);

    // Parse output
    const TfLiteTensor* output_tensor = TfLiteInterpreterGetOutputTensor(m_interpreter, 0);
    if (!output_tensor) return {};
    long long tPostStart = getTimeUs();

    // Reuse a static buffer instead of re-allocating each call.
    // reserve() on a vector with sufficient capacity is O(1); the previous
    // local vector could trigger occasional ~1.7ms heap-alloc spikes under
    // contention (the post=1.78ms samples in the latency log).
    static std::vector<Detection> s_detections;
    s_detections.clear();
    s_detections.reserve(m_num_outputs);

    float invW = 1.0f / screenWidth;
    float invH = 1.0f / screenHeight;

    TfLiteType output_type = TfLiteTensorType(output_tensor);
    void* output_data = const_cast<void*>(TfLiteTensorData(output_tensor));
    TfLiteQuantizationParams qp_output = TfLiteTensorQuantizationParams(output_tensor);

    // Auto-detect bbox format
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

    if (output_type == kTfLiteInt8) {
        int8_t* data = static_cast<int8_t*>(output_data);
        float out_scale = qp_output.scale;
        int out_zp = qp_output.zero_point;

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
            s_detections.push_back({
                (offsetX + (cx - hw) * regionWidth) * invW,
                (offsetY + (cy - hh) * regionHeight) * invH,
                (offsetX + (cx + hw) * regionWidth) * invW,
                (offsetY + (cy + hh) * regionHeight) * invH,
                score,
                (float)classId
            });
        }
    } else {
        float* data = static_cast<float*>(output_data);

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
            s_detections.push_back({
                (offsetX + (cx - hw) * regionWidth) * invW,
                (offsetY + (cy - hh) * regionHeight) * invH,
                (offsetX + (cx + hw) * regionWidth) * invW,
                (offsetY + (cy + hh) * regionHeight) * invH,
                score,
                (float)classId
            });
        }
    }

    LOGTRACE("LiteRT Raw: %zu", s_detections.size());

    auto finalDetections = nms(s_detections, 0.45f);
    long long tPostEnd = getTimeUs();

    LOGTRACELAT("LiteRT[%s] | pre=%.2fms infer=%.2fms post=%.2fms total=%.2fms raw=%zu nms=%zu",
           m_backend_type.c_str(),
           (tPreEnd - tPreStart) / 1e3,
           (t2 - t1) / 1e3,
           (tPostEnd - tPostStart) / 1e3,
           (tPostEnd - tPreStart) / 1e3,
           s_detections.size(), finalDetections.size());
    m_last_pre_ms = (float)((tPreEnd - tPreStart) / 1e3);
    m_last_infer_ms = (float)((t2 - t1) / 1e3);
    m_last_post_ms = (float)((tPostEnd - tPostStart) / 1e3);
    return finalDetections;
}
