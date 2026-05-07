//==============================================================================
//  TFLite with NNAPI Delegate
//  Uses Android NNAPI for Hexagon DSP/NPU acceleration
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
#include <tensorflow/lite/delegates/nnapi/nnapi_delegate_c_api.h>

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "TFLite_NNAPI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "TFLite_NNAPI", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "TFLite_NNAPI", __VA_ARGS__)

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
static TfLiteDelegate* g_nnapi_delegate = nullptr;
static int g_input_height = 256;
static int g_input_width = 256;
static int g_num_outputs = 1344;

//==============================================================================
//  Build NNAPI Delegate Options
//==============================================================================
static TfLiteDelegate* buildNnapiDelegate() {
    LOGD("Building NNAPI delegate...");

    // NNAPI delegate options - use this instead of QNN delegate
    // On Qualcomm devices, NNAPI will use Hexagon DSP/NPU via QNN backend
    TfLiteNnapiDelegateOptions nnapi_options = TfLiteNnapiDelegateOptionsDefault();
    // Disable using NNAPI CPU delegate - force hardware acceleration
    nnapi_options.disallow_nnapi_cpu = 1;
    nnapi_options.execution_preference = TfLiteNnapiDelegateOptions::kSustainedSpeed;

    TfLiteDelegate* delegate = TfLiteNnapiDelegateCreate(&nnapi_options);
    if (!delegate) {
        LOGE("Failed to create NNAPI delegate");
        return nullptr;
    }

    LOGD("NNAPI delegate created successfully");
    return delegate;
}

//==============================================================================
//  Init
//==============================================================================
extern "C"
JNIEXPORT jboolean JNICALL
Java_team_maodie_aimbot_JniCallBack_init(JNIEnv* env, jobject /*thiz*/, jstring model_path) {
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGD("init called with path: %s", path);

    // Release existing resources
    if (g_interpreter) {
        TfLiteInterpreterDelete(g_interpreter);
        g_interpreter = nullptr;
    }
    if (g_model) {
        TfLiteModelDelete(g_model);
        g_model = nullptr;
    }
    if (g_nnapi_delegate) {
        TfLiteNnapiDelegateDelete(g_nnapi_delegate);
        g_nnapi_delegate = nullptr;
    }

    // Load model
    g_model = TfLiteModelCreateFromFile(path);
    if (!g_model) {
        LOGE("Failed to load model from: %s", path);
        env->ReleaseStringUTFChars(model_path, path);
        return JNI_FALSE;
    }

    LOGD("Model loaded successfully");

    // Build interpreter options
    TfLiteInterpreterOptions* options = TfLiteInterpreterOptionsCreate();
    if (!options) {
        LOGE("Failed to create interpreter options");
        TfLiteModelDelete(g_model);
        env->ReleaseStringUTFChars(model_path, path);
        return JNI_FALSE;
    }

    // Try NNAPI delegate for hardware acceleration
    g_nnapi_delegate = buildNnapiDelegate();
    if (g_nnapi_delegate) {
        TfLiteInterpreterOptionsAddDelegate(options, g_nnapi_delegate);
        LOGD("NNAPI delegate added to options");
    } else {
        LOGW("NNAPI delegate not available, using CPU fallback");
    }

    // Set number of threads
    TfLiteInterpreterOptionsSetNumThreads(options, 1);

    // Create interpreter
    g_interpreter = TfLiteInterpreterCreate(g_model, options);
    TfLiteInterpreterOptionsDelete(options);

    if (!g_interpreter) {
        LOGE("Failed to create interpreter");
        TfLiteModelDelete(g_model);
        env->ReleaseStringUTFChars(model_path, path);
        return JNI_FALSE;
    }

    LOGD("Interpreter created");

    // Allocate tensors
    if (TfLiteInterpreterAllocateTensors(g_interpreter) != kTfLiteOk) {
        LOGE("Failed to allocate tensors");
        TfLiteInterpreterDelete(g_interpreter);
        TfLiteModelDelete(g_model);
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
            LOGD("Input: %dx%d, Output tensors: %d",
                 g_input_height, g_input_width,
                 TfLiteInterpreterGetOutputTensorCount(g_interpreter));
        }
    }

    // Get output tensor info
    int output_count = TfLiteInterpreterGetOutputTensorCount(g_interpreter);
    if (output_count > 0) {
        const TfLiteTensor* output_tensor = TfLiteInterpreterGetOutputTensor(g_interpreter, 0);
        if (output_tensor) {
            if (TfLiteTensorNumDims(output_tensor) >= 3) {
                g_num_outputs = TfLiteTensorDim(output_tensor, 2);
            }
            LOGD("Output tensor: [%d, %d, %d]",
                 TfLiteTensorDim(output_tensor, 0),
                 TfLiteTensorDim(output_tensor, 1),
                 TfLiteTensorDim(output_tensor, 2));
            LOGD("Output type: %d", TfLiteTensorType(output_tensor));
        }
    }

    LOGD("TFLite + QNN HTP initialized successfully");
    LOGD("  Input: %dx%d, Outputs: %d", g_input_height, g_input_width, g_num_outputs);

    env->ReleaseStringUTFChars(model_path, path);
    return JNI_TRUE;
}

//==============================================================================
//  NMS
//==============================================================================
static std::vector<std::tuple<float, float, float, float, float, float>> nms(
    std::vector<std::tuple<float, float, float, float, float, float>>& boxes,
    float iouThreshold
) {
    if (boxes.empty()) return {};

    std::sort(boxes.begin(), boxes.end(),
        [](const auto& a, const auto& b) {
            return std::get<4>(a) > std::get<4>(b);
        });

    std::vector<bool> suppressed(boxes.size(), false);
    std::vector<std::tuple<float, float, float, float, float, float>> result;

    for (size_t i = 0; i < boxes.size(); ++i) {
        if (suppressed[i]) continue;
        result.push_back(boxes[i]);

        for (size_t j = i + 1; j < boxes.size(); ++j) {
            if (suppressed[j]) continue;

            float x1a = std::get<0>(boxes[i]), y1a = std::get<1>(boxes[i]);
            float x2a = std::get<2>(boxes[i]), y2a = std::get<3>(boxes[i]);
            float x1b = std::get<0>(boxes[j]), y1b = std::get<1>(boxes[j]);
            float x2b = std::get<2>(boxes[j]), y2b = std::get<3>(boxes[j]);

            float interX1 = std::max(x1a, x1b);
            float interY1 = std::max(y1a, y1b);
            float interX2 = std::min(x2a, x2b);
            float interY2 = std::min(y2a, y2b);

            float interW = std::max(0.0f, interX2 - interX1);
            float interH = std::max(0.0f, interY2 - interY1);
            float interArea = interW * interH;

            float areaA = (x2a - x1a) * (y2a - y1a);
            float areaB = (x2b - x1b) * (y2b - y1b);
            float unionArea = areaA + areaB - interArea;

            float iou = unionArea > 0 ? interArea / unionArea : 0.0f;

            if (iou > iouThreshold) {
                suppressed[j] = true;
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
            for (int x = 0; x < W; ++x) {
                int srcX = offsetX + x * regionWidth / W;
                int srcY = offsetY + y * regionHeight / H;
                int srcIdx = srcY * rowStride + srcX * pixelStride;

                float r = src[srcIdx + 0] / 255.0f;
                float g = src[srcIdx + 1] / 255.0f;
                float b = src[srcIdx + 2] / 255.0f;

                int idx = (y * W + x) * 3;
                data[idx + 0] = (int8_t)std::round(r / input_scale + input_zero_point);
                data[idx + 1] = (int8_t)std::round(g / input_scale + input_zero_point);
                data[idx + 2] = (int8_t)std::round(b / input_scale + input_zero_point);
            }
        }
    } else if (input_type == kTfLiteUInt8) {
        uint8_t* data = static_cast<uint8_t*>(input_data);
        float input_scale = qp_input.scale;
        int input_zero_point = qp_input.zero_point;

        for (int y = 0; y < H; ++y) {
            for (int x = 0; x < W; ++x) {
                int srcX = offsetX + x * regionWidth / W;
                int srcY = offsetY + y * regionHeight / H;
                int srcIdx = srcY * rowStride + srcX * pixelStride;

                float r = src[srcIdx + 0] / 255.0f;
                float g = src[srcIdx + 1] / 255.0f;
                float b = src[srcIdx + 2] / 255.0f;

                int idx = (y * W + x) * 3;
                data[idx + 0] = (uint8_t)std::round(r / input_scale + input_zero_point);
                data[idx + 1] = (uint8_t)std::round(g / input_scale + input_zero_point);
                data[idx + 2] = (uint8_t)std::round(b / input_scale + input_zero_point);
            }
        }
    } else {
        // Float32 or other
        float* data = static_cast<float*>(input_data);

        for (int y = 0; y < H; ++y) {
            for (int x = 0; x < W; ++x) {
                int srcX = offsetX + x * regionWidth / W;
                int srcY = offsetY + y * regionHeight / H;
                int srcIdx = srcY * rowStride + srcX * pixelStride;

                int idx = (y * W + x) * 3;
                data[idx + 0] = src[srcIdx + 0] / 255.0f;
                data[idx + 1] = src[srcIdx + 1] / 255.0f;
                data[idx + 2] = src[srcIdx + 2] / 255.0f;
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
    LOGD("Preprocess: %lld us, Inference: %lld us", t1 - t0, t2 - t1);

    // Get output tensor
    const TfLiteTensor* output_tensor = TfLiteInterpreterGetOutputTensor(g_interpreter, 0);
    if (!output_tensor) {
        LOGE("Failed to get output tensor");
        return nullptr;
    }

    // Output shape: [1, 5, num_outputs] - YOLOv8n format
    std::vector<std::tuple<float, float, float, float, float, float>> detections;

    const float CONF_THRESH = 0.25f;
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

            if (score < CONF_THRESH) continue;
            if (bw <= 0 || bh <= 0) continue;
            if (cx < 0 || cx > 1 || cy < 0 || cy > 1) continue;

            // xywh to xyxy
            float x1 = cx - bw * 0.5f;
            float y1 = cy - bh * 0.5f;
            float x2 = cx + bw * 0.5f;
            float y2 = cy + bh * 0.5f;

            // Convert to screen coordinates
            float screenX1 = offsetX + x1 * regionWidth;
            float screenY1 = offsetY + y1 * regionHeight;
            float screenX2 = offsetX + x2 * regionWidth;
            float screenY2 = offsetY + y2 * regionHeight;

            detections.emplace_back(
                screenX1 / screenWidth,
                screenY1 / screenHeight,
                screenX2 / screenWidth,
                screenY2 / screenHeight,
                score,
                0.0f
            );
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

            if (score < CONF_THRESH) continue;
            if (bw <= 0 || bh <= 0) continue;
            if (cx < 0 || cx > 1 || cy < 0 || cy > 1) continue;

            // xywh to xyxy
            float x1 = cx - bw * 0.5f;
            float y1 = cy - bh * 0.5f;
            float x2 = cx + bw * 0.5f;
            float y2 = cy + bh * 0.5f;

            // Convert to screen coordinates
            float screenX1 = offsetX + x1 * regionWidth;
            float screenY1 = offsetY + y1 * regionHeight;
            float screenX2 = offsetX + x2 * regionWidth;
            float screenY2 = offsetY + y2 * regionHeight;

            detections.emplace_back(
                screenX1 / screenWidth,
                screenY1 / screenHeight,
                screenX2 / screenWidth,
                screenY2 / screenHeight,
                score,
                0.0f
            );
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
    std::vector<float> result_flat;
    result_flat.reserve(finalDetections.size() * 6);

    for (size_t i = 0; i < finalDetections.size(); ++i) {
        result_flat.push_back(std::get<5>(finalDetections[i]));  // classId
        result_flat.push_back(std::get<4>(finalDetections[i]));  // score
        result_flat.push_back(std::get<0>(finalDetections[i]));  // x1
        result_flat.push_back(std::get<1>(finalDetections[i]));  // y1
        result_flat.push_back(std::get<2>(finalDetections[i]));  // x2
        result_flat.push_back(std::get<3>(finalDetections[i]));  // y2
    }

    env->SetFloatArrayRegion(res, 0, result_flat.size(), result_flat.data());

    return res;
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
    if (g_nnapi_delegate) {
        TfLiteNnapiDelegateDelete(g_nnapi_delegate);
        g_nnapi_delegate = nullptr;
    }
    if (g_model) {
        TfLiteModelDelete(g_model);
        g_model = nullptr;
    }
    LOGD("TFLite + QNN released");
}