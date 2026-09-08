#include "litert_engine.h"
#include <dlfcn.h>
#include <cstdio>
#include <sys/stat.h>

// QNN HTP 可用性的一次性结论。0=未试 1=可用 -1=不可用。
// 由下面 init() 的委托试用循环填写，prewarmQnn 读它决定要不要跳过预热。
static int s_qnn_usable = 0;

int LiteRtEngine::qnnUsable() { return s_qnn_usable; }

// GPU delegate 的 kernel 缓存目录由 appCacheDir() 按当前 Android 用户解析
// (见 common.h)。原来这里和 qnn_engine.cpp 各写死了一份 /data/data/<pkg>
// 路径,那只在主用户下成立。

//==============================================================================
//  GPU Delegate Builder (universal, stays in LiteRtEngine)
//==============================================================================
TfLiteDelegate* LiteRtEngine::buildGpuDelegate() {
    TfLiteGpuDelegateOptionsV2 gpu_options = TfLiteGpuDelegateOptionsV2Default();
    // SUSTAINED_SPEED, not FAST_SINGLE_ANSWER: this delegate is reused for
    // every frame of a play session (~43 inferences/s, 10k+ per session), which
    // is exactly the "same delegate on multiple inputs" case the header
    // describes. FAST_SINGLE_ANSWER optimises bootstrap time instead, i.e. the
    // one cost that is paid once and that kernel serialisation would remove
    // anyway. Trades a longer delegate build for steady-state throughput.
    gpu_options.inference_preference = TFLITE_GPU_INFERENCE_PREFERENCE_SUSTAINED_SPEED;
    gpu_options.inference_priority1 = TFLITE_GPU_INFERENCE_PRIORITY_MIN_LATENCY;
    gpu_options.inference_priority2 = TFLITE_GPU_INFERENCE_PRIORITY_MAX_PRECISION;
    gpu_options.inference_priority3 = TFLITE_GPU_INFERENCE_PRIORITY_AUTO;

    // OpenCL kernel 序列化缓存。不开的话每次冷启动都要重编一遍 kernel ——
    // 实测委托创建 1.84s(19:41:44.430 → 19:41:46.268),这笔钱每次启动都付。
    // 开了之后只有第一次付,之后命中磁盘缓存。
    //
    // token 必须唯一标定 (模型, 输入尺寸, 委托配置) 三者:换了任何一个而
    // token 没变,加载到的就是给另一张图编的 kernel。_ss 标的是
    // SUSTAINED_SPEED,改动上面那几个 priority/preference 时记得一并改 token。
    //
    // 模型这一维**不能只靠文件名**:m_qnn_model_token 是 basename(见
    // yolovaim.cpp 的 makeQnnTokenForPath),而 YOLO 导出默认就叫 best.tflite /
    // yolov8n_float32.tflite,用户从不同目录导两个同名模型时 token 会撞,第二个
    // 加载到的是给第一个编的 kernel —— 结果是错检测或崩溃,而且不报错。所以
    // 再拼上 init() 里 stat 出来的 size+mtime 指纹。
    //
    // 指纹拿不到(stat 失败)或缓存目录建不出来时,宁可完全不开序列化:多付一次
    // 1.8s 的编译远好过冒用别的模型的缓存。
    if (m_gpu_cache_dir.empty()) m_gpu_cache_dir = appCacheDir("gpu");
    if (!m_model_fingerprint.empty() && !m_gpu_cache_dir.empty()) {
        if (m_gpu_token.empty()) {
            char buf[320];
            snprintf(buf, sizeof(buf), "%s_%s_gpu_%dx%d_ss",
                     m_qnn_model_token.empty() ? "model" : m_qnn_model_token.c_str(),
                     m_model_fingerprint.c_str(),
                     m_input_width, m_input_height);
            m_gpu_token = buf;
        }
        gpu_options.serialization_dir = m_gpu_cache_dir.c_str();
        gpu_options.model_token = m_gpu_token.c_str();
        gpu_options.experimental_flags |= TFLITE_GPU_EXPERIMENTAL_FLAGS_ENABLE_SERIALIZATION;
    } else {
        LOGW("GPU kernel serialisation disabled (fingerprint='%s' cacheDir='%s')",
             m_model_fingerprint.c_str(), m_gpu_cache_dir.c_str());
    }

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

    // 模型文件指纹,供 buildGpuDelegate() 拼 kernel 缓存键。size+mtime 足够
    // 区分两个不同的文件,又不用把整个模型读一遍算 hash(冷启动路径上)。
    // 必须在 delegate 试用循环之前算好 —— buildGpuDelegate() 在那之内被调。
    m_model_fingerprint.clear();
    {
        struct stat st;
        if (stat(model_path, &st) == 0) {
            char fp[64];
            snprintf(fp, sizeof(fp), "%llx_%llx",
                     (unsigned long long)st.st_size,
                     (unsigned long long)st.st_mtime);
            m_model_fingerprint = fp;
        } else {
            LOGW("stat(%s) failed — GPU kernel cache will be disabled", model_path);
        }
    }
    m_gpu_token.clear();

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
            // 委托根本建不出来 = 设备级结论(skel 版本不匹配等),可以缓存。
            if (trial.name == "QNN HTP") s_qnn_usable = -1;
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
            if (trial.name == "QNN HTP") s_qnn_usable = 1;
            LOGD("Interpreter created with %s delegate", trial.name.c_str());
            interpreter_created = true;
            break;
        } else {
            // 委托建出来了但 interpreter 创建失败,通常是**这个模型**的算子
            // HTP 接不下,不是设备用不了 HTP。这里不再置 -1:那是进程内不可逆的
            // 设备级结论,一个坏模型会让之后所有模型都跳过预热。
            LOGW("%s delegate: interpreter creation failed (model-specific, "
                 "device verdict unchanged)", trial.name.c_str());
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

    // Get output tensor info.
    //
    // 这一段是整个 native 侧最需要校验的地方:模型是用户自己导的(见
    // ModelRepository),检测头形状完全不可控,而后处理按
    // data[(4+c)*m_num_outputs + i] 直接索引。维度取错就是越界读,没有中间态。
    // 以前这里既不查 rank 也不核元素数,几种真实会遇到的形状都会走过去:
    //   - rank < 2:TfLiteTensorDim 不做边界检查(直接 dims->data[i]),读的是
    //     TfLiteIntArray 之后的堆内存,拿到垃圾维度
    //   - [1, 4, N](只出框、无类别分):channels-4=0 被夹成 1,于是多读一整行
    //   - [1, N, C](未转置的导出):两个维度的角色互换
    //   - 2 维输出:dim(1) 和 dim(ndim-1) 取到同一个数,索引量级变成 C^2
    // 对不上就在这里拒掉,让上层看到「模型不支持」,而不是带着错的维度进热路径。
    {
        const TfLiteTensor* out = TfLiteInterpreterGetOutputTensor(m_interpreter, 0);
        if (!out) {
            LOGE("Output tensor 0 missing");
            release();
            return false;
        }
        const int ndim = TfLiteTensorNumDims(out);
        if (ndim < 2) {
            LOGE("Unsupported output rank %d (expected [1, 4+nc, N])", ndim);
            release();
            return false;
        }
        const int channels = TfLiteTensorDim(out, 1);
        m_num_outputs = TfLiteTensorDim(out, ndim - 1);
        m_num_classes = channels - 4;
        if (m_num_classes < 1) m_num_classes = 1;

        // 后处理只有两条路:kTfLiteInt8 走反量化,其余一律 static_cast 成
        // float* 读。uint8 输出掉进 float 分支就是把 N 字节当 4N 字节读 ——
        // 一样是越界,所以认不出的类型在这里就拒。
        const TfLiteType ot = TfLiteTensorType(out);
        size_t esz = 0;
        if (ot == kTfLiteInt8) esz = sizeof(int8_t);
        else if (ot == kTfLiteFloat32) esz = sizeof(float);
        if (0 == esz) {
            LOGE("Unsupported output tensor type %d (only int8 / float32)", (int)ot);
            release();
            return false;
        }

        if (channels <= 4 || m_num_outputs <= 0) {
            LOGE("Output shape not usable: dims=%d channels=%d num_outputs=%d "
                 "(expected [1, 4+nc, N] with nc >= 1)", ndim, channels, m_num_outputs);
            release();
            return false;
        }

        // 索引上限:最大下标是 (3+m_num_classes)*m_num_outputs + (m_num_outputs-1),
        // 即需要 (4+m_num_classes)*m_num_outputs 个元素。
        const size_t have = TfLiteTensorByteSize(out) / esz;
        const size_t need = (size_t)(4 + m_num_classes) * (size_t)m_num_outputs;
        if (need > have) {
            LOGE("Output shape not usable: dims=%d channels=%d num_outputs=%d "
                 "num_classes=%d need=%zu have=%zu elements",
                 ndim, channels, m_num_outputs, m_num_classes, need, have);
            release();
            return false;
        }
        LOGD("Output: dims=%d, channels=%d, num_outputs=%d, num_classes=%d, elems=%zu",
             ndim, channels, m_num_outputs, m_num_classes, have);
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

    // 裁剪区越界就直接不推理。下面的 srcX/srcY_lut 是逐像素的读取下标,负的
    // offset 会从 src(ImageReader 的 direct buffer)之前开始读。见 common.h
    // 的 cropInBounds 注释。
    if (!cropInBounds(offsetX, offsetY, regionWidth, regionHeight,
                      screenWidth, screenHeight)) {
        LOGE("detect: crop out of bounds off=(%d,%d) region=%dx%d screen=%dx%d",
             offsetX, offsetY, regionWidth, regionHeight, screenWidth, screenHeight);
        return {};
    }

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
    } else if (output_type == kTfLiteFloat32) {
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
    } else {
        // init() 已经把非 int8/float32 的输出拒掉了,走到这里说明张量类型在
        // init 之后变了。原来这里是无条件的 else,uint8 输出会被当 float 读 ——
        // N 字节当 4N 字节,直接越界。
        LOGE("detect: unexpected output type %d", (int)output_type);
        return {};
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
