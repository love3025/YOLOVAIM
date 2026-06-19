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
        // Neuron delegate delete - TODO
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
        LOGD("Falling back to CPU");
        TfLiteInterpreterOptions* cpu_opts = TfLiteInterpreterOptionsCreate();
        TfLiteInterpreterOptionsSetNumThreads(cpu_opts, m_cpu_threads);
        m_interpreter = TfLiteInterpreterCreate(m_model, cpu_opts);
        TfLiteInterpreterOptionsDelete(cpu_opts);

        if (!m_interpreter) {
            LOGE("Failed to create interpreter with CPU");
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

    TfLiteTensor* input_tensor = TfLiteInterpreterGetInputTensor(m_interpreter, 0);
    if (!input_tensor) return {};

    int H = m_input_height;
    int W = m_input_width;

    // Precompute coordinate LUTs
    std::vector<int> srcX_lut(W);
    std::vector<int> srcY_lut(H);
    for (int x = 0; x < W; ++x) srcX_lut[x] = offsetX + x * regionWidth / W;
    for (int y = 0; y < H; ++y) srcY_lut[y] = offsetY + y * regionHeight / H;

    static const float inv255 = 1.0f / 255.0f;

    TfLiteType input_type = TfLiteTensorType(input_tensor);
    void* input_data = TfLiteTensorData(input_tensor);
    if (!input_data) return {};

    TfLiteQuantizationParams qp_input = TfLiteTensorQuantizationParams(input_tensor);

    if (input_type == kTfLiteInt8) {
        int8_t* data = static_cast<int8_t*>(input_data);
        float input_scale = qp_input.scale;
        int input_zero_point = qp_input.zero_point;

        for (int y = 0; y < H; ++y) {
            int baseRow = srcY_lut[y] * rowStride;
            for (int x = 0; x < W; ++x) {
                int srcIdx = baseRow + srcX_lut[x] * pixelStride;
                int idx = (y * W + x) * 3;
                data[idx + 0] = (int8_t)std::round(src[srcIdx + 0] * inv255 / input_scale + input_zero_point);
                data[idx + 1] = (int8_t)std::round(src[srcIdx + 1] * inv255 / input_scale + input_zero_point);
                data[idx + 2] = (int8_t)std::round(src[srcIdx + 2] * inv255 / input_scale + input_zero_point);
            }
        }
    } else if (input_type == kTfLiteUInt8) {
        uint8_t* data = static_cast<uint8_t*>(input_data);
        float input_scale = qp_input.scale;
        int input_zero_point = qp_input.zero_point;

        for (int y = 0; y < H; ++y) {
            int baseRow = srcY_lut[y] * rowStride;
            for (int x = 0; x < W; ++x) {
                int srcIdx = baseRow + srcX_lut[x] * pixelStride;
                int idx = (y * W + x) * 3;
                data[idx + 0] = (uint8_t)std::round(src[srcIdx + 0] * inv255 / input_scale + input_zero_point);
                data[idx + 1] = (uint8_t)std::round(src[srcIdx + 1] * inv255 / input_scale + input_zero_point);
                data[idx + 2] = (uint8_t)std::round(src[srcIdx + 2] * inv255 / input_scale + input_zero_point);
            }
        }
    } else {
        float* data = static_cast<float*>(input_data);
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
    }

    long long t1 = getTimeUs();

    if (TfLiteInterpreterInvoke(m_interpreter) != kTfLiteOk) {
        LOGE("Inference failed");
        return {};
    }

    long long t2 = getTimeUs();
    LOGD("LiteRT Inference: %lld us", t2 - t1);

    // Parse output
    const TfLiteTensor* output_tensor = TfLiteInterpreterGetOutputTensor(m_interpreter, 0);
    if (!output_tensor) return {};

    std::vector<Detection> detections;
    detections.reserve(m_num_outputs);

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
            detections.push_back({
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
            detections.push_back({
                (offsetX + (cx - hw) * regionWidth) * invW,
                (offsetY + (cy - hh) * regionHeight) * invH,
                (offsetX + (cx + hw) * regionWidth) * invW,
                (offsetY + (cy + hh) * regionHeight) * invH,
                score,
                (float)classId
            });
        }
    }

    LOGD("LiteRT Raw: %zu", detections.size());

    auto finalDetections = nms(detections, 0.45f);
    return finalDetections;
}
