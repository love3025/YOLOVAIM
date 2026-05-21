//==============================================================================
//  TFLite with Qualcomm QNN HTP Delegate
//  Uses Hexagon DSP/NPU via QNN SDK for hardware acceleration
//==============================================================================
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cmath>
#include <cstring>
#include <sys/time.h>
#include <dlfcn.h>

// TFLite C API
#include <tensorflow/lite/c/c_api.h>
#include <tensorflow/lite/c/common.h>
#include <qnn/TFLiteDelegate/QnnTFLiteDelegate.h>
#include <tensorflow/lite/delegates/nnapi/nnapi_delegate_c_api.h>

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "TFLite_QNN", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "TFLite_QNN", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "TFLite_QNN", __VA_ARGS__)

static inline long long getTimeUs() {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return tv.tv_sec * 1000000LL + tv.tv_usec;
}

//==============================================================================
//  Globals
//==============================================================================
static TfLiteModel* g_model = nullptr;
static TfLiteInterpreter* g_interpreter = nullptr;
static TfLiteDelegate* g_delegate = nullptr;
static std::string g_backend_type = "QNN HTP";
static int g_input_height = 256;
static int g_input_width = 256;
static int g_num_outputs = 1344;
static float g_conf_thresh = 0.25f;

static void deleteDelegate() {
    if (g_delegate) {
        if (g_backend_type == "QNN HTP") {
            TfLiteQnnDelegateDelete(g_delegate);
        } else {
            TfLiteNnapiDelegateDelete(g_delegate);
        }
        g_delegate = nullptr;
    }
}

//==============================================================================
//  CPU Architecture Detection
//==============================================================================
static bool isQualcommSnapdragon() {
    // Check /proc/cpuinfo for Qualcomm/Snapdragon string
    FILE* f = fopen("/proc/cpuinfo", "r");
    if (f) {
        char line[512];
        while (fgets(line, sizeof(line), f)) {
            if (strstr(line, "Qualcomm") || strstr(line, "qcom") || strstr(line, "Snapdragon")) {
                fclose(f);
                return true;
            }
        }
        fclose(f);
    }
    // Also check ro.hardware via getprop (Qualcomm devices set this to "qcom")
    FILE* p = popen("getprop ro.hardware", "r");
    if (p) {
        char line[128];
        if (fgets(line, sizeof(line), p)) {
            if (strstr(line, "qcom")) {
                pclose(p);
                return true;
            }
        }
        pclose(p);
    }
    return false;
}

//==============================================================================
//  Build QNN TFLite Delegate Options
//==============================================================================
static TfLiteDelegate* buildQnnDelegate() {
    // Skip QNN on non-Qualcomm (MediaTek Dimensity, Samsung Exynos, etc.)
    if (!isQualcommSnapdragon()) {
        LOGW("Non-Qualcomm CPU detected, skipping QNN HTP");
        return nullptr;
    }

    // Preload vendor DSP RPC libraries required by QNN HTP backend.
    static bool preloaded = false;
    static char g_native_lib_dir[512] = {0};

    if (!preloaded) {
        // FastRPC — required on all Qualcomm platforms
        void* h = dlopen("libcdsprpc.so", RTLD_NOW);
        if (h) {
            LOGD("libcdsprpc.so preloaded");
        } else {
            LOGW("libcdsprpc.so not available: %s", dlerror());
        }

        // ADSP RPC — present on some platforms
        h = dlopen("libadsprpc.so", RTLD_NOW);
        if (h) {
            LOGD("libadsprpc.so preloaded");
        } else {
            LOGW("libadsprpc.so not available: %s", dlerror());
        }

        // Resolve native library directory — needed for QNN skel library path
        Dl_info info;
        if (dladdr((void*)buildQnnDelegate, &info)) {
            std::string libPath(info.dli_fname);
            size_t pos = libPath.find_last_of('/');
            if (pos != std::string::npos) {
                std::string dir = libPath.substr(0, pos);
                strncpy(g_native_lib_dir, dir.c_str(), sizeof(g_native_lib_dir) - 1);
                LOGD("Native lib dir: %s", g_native_lib_dir);
            }
        }

        preloaded = true;
    }

    TfLiteQnnDelegateOptions qnn_options = TfLiteQnnDelegateOptionsDefault();
    qnn_options.backend_type = kHtpBackend;
    qnn_options.skel_library_dir = g_native_lib_dir;
    qnn_options.cache_dir = "/data/data/team.maodie.aimbot/cache/qnn";
    qnn_options.model_token = "yolov8n_htp_v1";

    return TfLiteQnnDelegateCreate(&qnn_options);
}

//==============================================================================
//  Init
//==============================================================================
extern "C"
JNIEXPORT jboolean JNICALL
Java_team_maodie_aimbot_JniCallBack_init(JNIEnv* env, jobject /*thiz*/, jstring model_path) {
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGD("init: model_path = %s", path);

    // Release existing resources
    if (g_interpreter) {
        TfLiteInterpreterDelete(g_interpreter);
        g_interpreter = nullptr;
    }
    if (g_model) {
        TfLiteModelDelete(g_model);
        g_model = nullptr;
    }
    deleteDelegate();

    // Load model
    g_model = TfLiteModelCreateFromFile(path);
    if (!g_model) {
        LOGE("Failed to load model from: %s", path);
        env->ReleaseStringUTFChars(model_path, path);
        return JNI_FALSE;
    }

    // Build interpreter options
    TfLiteInterpreterOptions* options = TfLiteInterpreterOptionsCreate();
    if (!options) {
        LOGE("Failed to create interpreter options");
        TfLiteModelDelete(g_model);
        g_model = nullptr;
        env->ReleaseStringUTFChars(model_path, path);
        return JNI_FALSE;
    }

    // Try QNN HTP delegate first
    g_delegate = buildQnnDelegate();
    if (g_delegate) {
        g_backend_type = "QNN HTP";
        LOGD("Using QNN HTP delegate");
    } else {
        // Fallback to NNAPI delegate
        LOGW("QNN HTP unavailable, falling back to NNAPI delegate");
        TfLiteNnapiDelegateOptions nnapi_opts = TfLiteNnapiDelegateOptionsDefault();
        nnapi_opts.disallow_nnapi_cpu = 1;
        g_delegate = TfLiteNnapiDelegateCreate(&nnapi_opts);
        if (g_delegate) {
            g_backend_type = "NNAPI";
            LOGD("Using NNAPI delegate");
        } else {
            LOGE("Both QNN HTP and NNAPI delegates unavailable.");
            TfLiteInterpreterOptionsDelete(options);
            TfLiteModelDelete(g_model);
            g_model = nullptr;
            env->ReleaseStringUTFChars(model_path, path);
            return JNI_FALSE;
        }
    }
    TfLiteInterpreterOptionsAddDelegate(options, g_delegate);
    TfLiteInterpreterOptionsSetNumThreads(options, 1);

    // Create interpreter
    g_interpreter = TfLiteInterpreterCreate(g_model, options);
    TfLiteInterpreterOptionsDelete(options);

    if (!g_interpreter) {
        LOGE("Failed to create interpreter with %s delegate.", g_backend_type.c_str());
        deleteDelegate();
        TfLiteModelDelete(g_model);
        g_model = nullptr;
        env->ReleaseStringUTFChars(model_path, path);
        return JNI_FALSE;
    }

    // Allocate tensors
    if (TfLiteInterpreterAllocateTensors(g_interpreter) != kTfLiteOk) {
        LOGE("Failed to allocate tensors");
        TfLiteInterpreterDelete(g_interpreter);
        g_interpreter = nullptr;
        deleteDelegate();
        TfLiteModelDelete(g_model);
        g_model = nullptr;
        env->ReleaseStringUTFChars(model_path, path);
        return JNI_FALSE;
    }

    // Get input tensor info
    int input_count = TfLiteInterpreterGetInputTensorCount(g_interpreter);
    if (input_count > 0) {
        const TfLiteTensor* input_tensor = TfLiteInterpreterGetInputTensor(g_interpreter, 0);
        if (input_tensor) {
            g_input_height = TfLiteTensorDim(input_tensor, 1);
            g_input_width = TfLiteTensorDim(input_tensor, 2);
        }
    }

    // Get output tensor info — num_outputs is the detection count per channel
    {
        const TfLiteTensor* out = TfLiteInterpreterGetOutputTensor(g_interpreter, 0);
        if (out) {
            int ndim = TfLiteTensorNumDims(out);
            g_num_outputs = TfLiteTensorDim(out, ndim - 1);
            LOGD("Input: %dx%d, Output dims: %d, num_outputs: %d",
                 g_input_width, g_input_height, ndim, g_num_outputs);
        }
    }

    LOGD("TFLite + QNN HTP initialized successfully");

    env->ReleaseStringUTFChars(model_path, path);
    return JNI_TRUE;
}

//==============================================================================
//  NMS
//==============================================================================
struct Detection {
    float x1, y1, x2, y2, score, classId;
};

static std::vector<Detection> nms(std::vector<Detection>& boxes, float iouThreshold) {
    if (boxes.empty()) return {};

    std::sort(boxes.begin(), boxes.end(),
        [](const Detection& a, const Detection& b) {
            return a.score > b.score;
        });

    auto suppressed = std::make_unique<uint8_t[]>(boxes.size());
    memset(suppressed.get(), 0, boxes.size());
    std::vector<Detection> result;
    result.reserve(boxes.size());

    for (size_t i = 0; i < boxes.size(); ++i) {
        if (suppressed[i]) continue;
        result.push_back(boxes[i]);

        for (size_t j = i + 1; j < boxes.size(); ++j) {
            if (suppressed[j]) continue;

            float x1a = boxes[i].x1, y1a = boxes[i].y1;
            float x2a = boxes[i].x2, y2a = boxes[i].y2;
            float x1b = boxes[j].x1, y1b = boxes[j].y1;
            float x2b = boxes[j].x2, y2b = boxes[j].y2;

            float interW = std::max(0.0f, std::min(x2a, x2b) - std::max(x1a, x1b));
            float interH = std::max(0.0f, std::min(y2a, y2b) - std::max(y1a, y1b));
            float interArea = interW * interH;
            float unionArea = (x2a - x1a) * (y2a - y1a) + (x2b - x1b) * (y2b - y1b) - interArea;

            if (unionArea > 0 && interArea / unionArea > iouThreshold) {
                suppressed[j] = 1;
            }
        }
    }

    return result;
}

//==============================================================================
//  Detect
//==============================================================================
extern "C"
JNIEXPORT jfloatArray JNICALL
Java_team_maodie_aimbot_JniCallBack_detect(
    JNIEnv* env, jobject /*thiz*/,
    jobject buffer,
    jint offsetX, jint offsetY,
    jint regionWidth, jint regionHeight,
    jint screenWidth, jint screenHeight,
    jint rowStride, jint pixelStride
) {
    if (!g_interpreter) {
        LOGE("Interpreter not initialized");
        return nullptr;
    }

    uint8_t* src = (uint8_t*)env->GetDirectBufferAddress(buffer);
    if (!src) {
        LOGE("Failed to get buffer address");
        return nullptr;
    }

    long long t0 = getTimeUs();

    // Get input tensor
    int input_idx = 0;
    TfLiteTensor* input_tensor = TfLiteInterpreterGetInputTensor(g_interpreter, input_idx);
    if (!input_tensor) {
        LOGE("Failed to get input tensor");
        return nullptr;
    }

    int H = g_input_height;
    int W = g_input_width;

    // Precompute coordinate LUTs for center-crop + nearest-neighbor resize
    // Use dynamic allocation since input dims are set at runtime
    std::vector<int> srcX_lut(W);
    std::vector<int> srcY_lut(H);
    for (int x = 0; x < W; ++x) srcX_lut[x] = offsetX + x * regionWidth / W;
    for (int y = 0; y < H; ++y) srcY_lut[y] = offsetY + y * regionHeight / H;

    static const float inv255 = 1.0f / 255.0f;

    // Fill input tensor based on type
    TfLiteType input_type = TfLiteTensorType(input_tensor);
    void* input_data = TfLiteTensorData(input_tensor);

    if (!input_data) {
        LOGE("Failed to get input tensor data");
        return nullptr;
    }

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
        // Float32 or other
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

    // Run inference
    if (TfLiteInterpreterInvoke(g_interpreter) != kTfLiteOk) {
        LOGE("Inference failed");
        return nullptr;
    }

    long long t2 = getTimeUs();
    LOGD("Preprocess: %lld us, Inference: %lld us, Total: %lld us", t1 - t0, t2 - t1, t2 - t0);

    // Get output tensor
    const TfLiteTensor* output_tensor = TfLiteInterpreterGetOutputTensor(g_interpreter, 0);
    if (!output_tensor) {
        LOGE("Failed to get output tensor");
        return nullptr;
    }

    // Output shape: [1, 5, num_outputs] - YOLOv8n format
    std::vector<Detection> detections;
    detections.reserve(g_num_outputs);

    float invW = 1.0f / screenWidth;
    float invH = 1.0f / screenHeight;

    TfLiteType output_type = TfLiteTensorType(output_tensor);
    void* output_data = const_cast<void*>(TfLiteTensorData(output_tensor));
    TfLiteQuantizationParams qp_output = TfLiteTensorQuantizationParams(output_tensor);

    if (output_type == kTfLiteInt8) {
        int8_t* data = static_cast<int8_t*>(output_data);
        float out_scale = qp_output.scale;
        int out_zp = qp_output.zero_point;

        for (int i = 0; i < g_num_outputs; ++i) {
            float cx = (data[i] - out_zp) * out_scale;
            float cy = (data[g_num_outputs + i] - out_zp) * out_scale;
            float bw = (data[2 * g_num_outputs + i] - out_zp) * out_scale;
            float bh = (data[3 * g_num_outputs + i] - out_zp) * out_scale;
            float score = (data[4 * g_num_outputs + i] - out_zp) * out_scale;

            if (score < g_conf_thresh) continue;
            if (bw <= 0 || bh <= 0) continue;
            if (cx < 0 || cx > 1 || cy < 0 || cy > 1) continue;

            float hw = bw * 0.5f, hh = bh * 0.5f;
            detections.push_back({
                (offsetX + (cx - hw) * regionWidth) * invW,
                (offsetY + (cy - hh) * regionHeight) * invH,
                (offsetX + (cx + hw) * regionWidth) * invW,
                (offsetY + (cy + hh) * regionHeight) * invH,
                score,
                0.0f
            });
        }
    } else {
        // Float32 output
        float* data = static_cast<float*>(output_data);

        for (int i = 0; i < g_num_outputs; ++i) {
            float cx = data[i];
            float cy = data[g_num_outputs + i];
            float bw = data[2 * g_num_outputs + i];
            float bh = data[3 * g_num_outputs + i];
            float score = data[4 * g_num_outputs + i];

            if (score < g_conf_thresh) continue;
            if (bw <= 0 || bh <= 0) continue;
            if (cx < 0 || cx > 1 || cy < 0 || cy > 1) continue;

            float hw = bw * 0.5f, hh = bh * 0.5f;
            detections.push_back({
                (offsetX + (cx - hw) * regionWidth) * invW,
                (offsetY + (cy - hh) * regionHeight) * invH,
                (offsetX + (cx + hw) * regionWidth) * invW,
                (offsetY + (cy + hh) * regionHeight) * invH,
                score,
                0.0f
            });
        }
    }

    LOGD("Raw detections: %zu", detections.size());

    // Apply NMS
    auto finalDetections = nms(detections, 0.45f);
    LOGD("After NMS: %zu", finalDetections.size());

    if (finalDetections.empty()) {
        return nullptr;
    }

    jfloatArray res = env->NewFloatArray(finalDetections.size() * 6);
    if (!res) {
        LOGE("Failed to allocate jfloatArray");
        return nullptr;
    }
    float* dst = env->GetFloatArrayElements(res, nullptr);
    if (!dst) {
        LOGE("Failed to get float array elements");
        env->DeleteLocalRef(res);
        return nullptr;
    }
    for (size_t i = 0; i < finalDetections.size(); ++i) {
        dst[i * 6 + 0] = finalDetections[i].classId;
        dst[i * 6 + 1] = finalDetections[i].score;
        dst[i * 6 + 2] = finalDetections[i].x1;
        dst[i * 6 + 3] = finalDetections[i].y1;
        dst[i * 6 + 4] = finalDetections[i].x2;
        dst[i * 6 + 5] = finalDetections[i].y2;
    }
    env->ReleaseFloatArrayElements(res, dst, 0);

    return res;
}

//==============================================================================
//  Set confidence threshold
//==============================================================================
extern "C"
JNIEXPORT void JNICALL
Java_team_maodie_aimbot_JniCallBack_setConfidence(JNIEnv* /*env*/, jobject /*thiz*/, jfloat threshold) {
    g_conf_thresh = threshold;
    LOGD("Confidence threshold set to %.2f", g_conf_thresh);
}

//==============================================================================
//  Get backend type
//==============================================================================
extern "C"
JNIEXPORT jstring JNICALL
Java_team_maodie_aimbot_JniCallBack_getBackend(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(g_backend_type.c_str());
}

//==============================================================================
//  Release
//==============================================================================
extern "C"
JNIEXPORT void JNICALL
Java_team_maodie_aimbot_JniCallBack_release(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_interpreter) {
        TfLiteInterpreterDelete(g_interpreter);
        g_interpreter = nullptr;
    }
    deleteDelegate();
    if (g_model) {
        TfLiteModelDelete(g_model);
        g_model = nullptr;
    }
}