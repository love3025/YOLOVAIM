//==============================================================================
//  TFLite with Qualcomm QNN HTP Delegate + NCNN Support
//  Uses Hexagon DSP/NPU via QNN SDK for hardware acceleration
//  Supports NCNN models with Vulkan GPU acceleration
//==============================================================================
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <functional>
#include <cmath>
#include <cstring>
#include <sys/time.h>
#include <dlfcn.h>

// TFLite C API
#include <tensorflow/lite/c/c_api.h>
#include <tensorflow/lite/c/common.h>
#include <qnn/TFLiteDelegate/QnnTFLiteDelegate.h>
#include <tensorflow/lite/delegates/nnapi/nnapi_delegate_c_api.h>
// GPU delegate (OpenCL / OpenGL, Qualcomm + MediaTek universal)
#include <tensorflow/lite/delegates/gpu/delegate.h>

// NCNN
#include <ncnn/net.h>
#include <ncnn/mat.h>

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "TFLite_QNN", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "TFLite_QNN", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "TFLite_QNN", __VA_ARGS__)

static inline long long getTimeUs() {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return tv.tv_sec * 1000000LL + tv.tv_usec;
}

static inline float sigmoid(float x) {
    return 1.0f / (1.0f + expf(-x));
}

//==============================================================================
//  Globals
//==============================================================================
// TFLite globals
static TfLiteModel* g_model = nullptr;
static TfLiteInterpreter* g_interpreter = nullptr;
static TfLiteDelegate* g_delegate = nullptr;

// NCNN globals
static ncnn::Net* g_ncnn_net = nullptr;
static ncnn::Extractor* g_ncnn_ex = nullptr;

// Common globals
static bool g_use_ncnn = false;  // true = using NCNN backend
static std::string g_backend_type = "QNN HTP";
static int g_input_height = 256;
static int g_input_width = 256;
static bool g_input_nhwc = false;  // false=NCHW [1,3,H,W], true=NHWC [1,H,W,3]
static int g_num_outputs = 1344;
static int g_num_classes = 1;  // 1 = single-class (score only), >1 = multi-class
static float g_conf_thresh = 0.25f;
static bool g_force_cpu = false;
static int g_cpu_threads = 4;

static void deleteDelegate() {
    if (g_delegate) {
        if (g_backend_type == "QNN HTP") {
            TfLiteQnnDelegateDelete(g_delegate);
        } else if (g_backend_type == "GPU") {
            TfLiteGpuDelegateV2Delete(g_delegate);
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
//  Build TFLite GPU Delegate (OpenCL/OpenGL — Qualcomm + MediaTek universal)
//==============================================================================
static TfLiteDelegate* buildGpuDelegate() {
    TfLiteGpuDelegateOptionsV2 gpu_options = TfLiteGpuDelegateOptionsV2Default();
    // Default already includes: ENABLE_QUANT (INT8 support), FP16 allowed
    // Set inference preference for single-shot detection (not sustained throughput)
    gpu_options.inference_preference = TFLITE_GPU_INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER;
    // Priority: MIN_LATENCY first, then MAX_PRECISION
    gpu_options.inference_priority1 = TFLITE_GPU_INFERENCE_PRIORITY_MIN_LATENCY;
    gpu_options.inference_priority2 = TFLITE_GPU_INFERENCE_PRIORITY_MAX_PRECISION;
    gpu_options.inference_priority3 = TFLITE_GPU_INFERENCE_PRIORITY_AUTO;

    TfLiteDelegate* delegate = TfLiteGpuDelegateV2Create(&gpu_options);
    if (delegate) {
        LOGD("GPU delegate created successfully (OpenCL/OpenGL)");
    } else {
        LOGW("GPU delegate creation failed");
    }
    return delegate;
}

//==============================================================================
//  Init
//==============================================================================
extern "C"
JNIEXPORT jboolean JNICALL
Java_team_maodie_aimbot_inference_JniCallBack_init(JNIEnv* env, jobject /*thiz*/, jstring model_path) {
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

    // Release NCNN resources if switching to TFLite
    if (g_ncnn_ex) {
        delete g_ncnn_ex;
        g_ncnn_ex = nullptr;
    }
    if (g_ncnn_net) {
        delete g_ncnn_net;
        g_ncnn_net = nullptr;
    }

    // Check if model is NCNN (.param extension)
    std::string path_str(path);
    bool is_ncnn = (path_str.size() >= 6 && path_str.substr(path_str.size() - 6) == ".param");

    if (is_ncnn) {
        LOGD("Detected NCNN model, initializing NCNN backend...");

        // Find bin file (same directory, same name but .bin extension)
        std::string bin_path = path_str.substr(0, path_str.size() - 6) + ".bin";

        g_ncnn_net = new ncnn::Net();

        // CPU mode (more stable, Vulkan can crash on some devices)
        g_ncnn_net->opt.use_vulkan_compute = false;
        g_ncnn_net->opt.num_threads = g_cpu_threads;

        // Register PNNX/ONNX layer name mappings for quantized models
        // QuantizeLinear -> Quantize
        g_ncnn_net->register_custom_layer("QuantizeLinear",
            [](void*) -> ncnn::Layer* { return ncnn::create_layer("Quantize"); },
            [](ncnn::Layer* layer, void*) { delete layer; });
        // DequantizeLinear -> Dequantize
        g_ncnn_net->register_custom_layer("DequantizeLinear",
            [](void*) -> ncnn::Layer* { return ncnn::create_layer("Dequantize"); },
            [](ncnn::Layer* layer, void*) { delete layer; });
        // pnnx.Expression -> MemoryData (constant generator)
        g_ncnn_net->register_custom_layer("pnnx.Expression",
            [](void*) -> ncnn::Layer* { return ncnn::create_layer("MemoryData"); },
            [](ncnn::Layer* layer, void*) { delete layer; });

        // Load model
        int ret_param = g_ncnn_net->load_param(path_str.c_str());
        int ret_model = g_ncnn_net->load_model(bin_path.c_str());

        if (ret_param != 0 || ret_model != 0) {
            LOGE("NCNN model load failed: param=%d, model=%d", ret_param, ret_model);
            delete g_ncnn_net;
            g_ncnn_net = nullptr;
            env->ReleaseStringUTFChars(model_path, path);
            return JNI_FALSE;
        }

        // Input dimensions will be set by setInputSize() before init,
        // or use defaults (256x256)
        g_input_nhwc = false;  // NCNN uses NCHW internally

        g_use_ncnn = true;
        g_backend_type = "NCNN Vulkan";

        LOGD("NCNN initialized successfully: %s, input=%dx%d, Vulkan=%s",
             path_str.c_str(), g_input_width, g_input_height,
             g_ncnn_net->opt.use_vulkan_compute ? "ON" : "OFF");
        env->ReleaseStringUTFChars(model_path, path);
        return JNI_TRUE;
    }

    // TFLite path (existing code)
    g_use_ncnn = false;

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

    // Try hardware delegates with fallback chain:
    // QNN HTP → GPU → NNAPI → CPU
    // For each delegate: create delegate → create interpreter → if fails, try next
    struct DelegateTrial {
        std::function<TfLiteDelegate*()> builder;
        std::string name;
        bool skip;  // true = skip this trial
    };

    std::vector<DelegateTrial> trials;
    if (!g_force_cpu) {
        trials.push_back({[]{ return buildQnnDelegate(); }, "QNN HTP", false});
        trials.push_back({[]{ return buildGpuDelegate(); }, "GPU", false});
    }

    bool interpreter_created = false;
    for (auto& trial : trials) {
        if (trial.skip) continue;

        LOGD("Trying %s delegate...", trial.name.c_str());
        TfLiteDelegate* del = trial.builder();
        if (!del) {
            LOGD("%s delegate not available, skipping", trial.name.c_str());
            continue;
        }

        // Build fresh options for this attempt
        TfLiteInterpreterOptions* trial_opts = TfLiteInterpreterOptionsCreate();
        TfLiteInterpreterOptionsAddDelegate(trial_opts, del);
        TfLiteInterpreterOptionsSetNumThreads(trial_opts, 1);

        TfLiteInterpreter* interp = TfLiteInterpreterCreate(g_model, trial_opts);
        TfLiteInterpreterOptionsDelete(trial_opts);

        if (interp) {
            // Success
            g_delegate = del;
            g_interpreter = interp;
            g_backend_type = trial.name;
            LOGD("✓ Interpreter created with %s delegate", trial.name.c_str());
            interpreter_created = true;
            break;
        } else {
            // This delegate failed at interpreter creation, clean up and try next
            LOGW("%s delegate: interpreter creation failed, trying next...", trial.name.c_str());
            if (trial.name == "QNN HTP") {
                TfLiteQnnDelegateDelete(del);
            } else if (trial.name == "GPU") {
                TfLiteGpuDelegateV2Delete(del);
            } else {
                TfLiteNnapiDelegateDelete(del);
            }
        }
    }

    if (!interpreter_created) {
        // All delegates failed, use CPU
        g_delegate = nullptr;
        g_backend_type = "CPU";
        LOGD("All delegates failed, falling back to CPU");
        TfLiteInterpreterOptions* cpu_opts = TfLiteInterpreterOptionsCreate();
        TfLiteInterpreterOptionsSetNumThreads(cpu_opts, g_cpu_threads);
        g_interpreter = TfLiteInterpreterCreate(g_model, cpu_opts);
        TfLiteInterpreterOptionsDelete(cpu_opts);

        if (!g_interpreter) {
            LOGE("Failed to create interpreter even with CPU");
            TfLiteModelDelete(g_model);
            g_model = nullptr;
            env->ReleaseStringUTFChars(model_path, path);
            return JNI_FALSE;
        }
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
            int ndim = TfLiteTensorNumDims(input_tensor);
            int dim1 = TfLiteTensorDim(input_tensor, 1);
            int dim2 = TfLiteTensorDim(input_tensor, 2);
            if (ndim >= 4) {
                int dim3 = TfLiteTensorDim(input_tensor, 3);
                // NCHW: [1, 3, H, W] → dim1==3, dim3==W
                // NHWC: [1, H, W, 3] → dim3==3, dim2==W
                if (dim3 == 3 && dim1 != 3) {
                    g_input_nhwc = true;
                    g_input_height = dim1;
                    g_input_width = dim2;
                } else {
                    g_input_nhwc = false;
                    g_input_height = dim1;
                    g_input_width = dim2;
                }
            } else {
                g_input_nhwc = false;
                g_input_height = dim1;
                g_input_width = dim2;
            }
            LOGD("Input format: %s, H=%d, W=%d", g_input_nhwc ? "NHWC" : "NCHW", g_input_height, g_input_width);
        }
    }

    // Get output tensor info — num_outputs is the detection count per channel
    {
        const TfLiteTensor* out = TfLiteInterpreterGetOutputTensor(g_interpreter, 0);
        if (out) {
            int ndim = TfLiteTensorNumDims(out);
            g_num_outputs = TfLiteTensorDim(out, ndim - 1);
            int channels = TfLiteTensorDim(out, 1);  // [1, channels, num_outputs]
            g_num_classes = channels - 4;  // 4 bbox params (cx, cy, w, h)
            if (g_num_classes < 1) g_num_classes = 1;
            LOGD("Input: %dx%d, Output dims: %d, channels: %d, num_outputs: %d, num_classes: %d",
                 g_input_width, g_input_height, ndim, channels, g_num_outputs, g_num_classes);
        }
    }

    LOGD("TFLite initialized successfully, backend: %s", g_backend_type.c_str());

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

            if (unionArea > 0 && interArea / unionArea > iouThreshold &&
                boxes[i].classId == boxes[j].classId) {
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
Java_team_maodie_aimbot_inference_JniCallBack_detect(
    JNIEnv* env, jobject /*thiz*/,
    jobject buffer,
    jint offsetX, jint offsetY,
    jint regionWidth, jint regionHeight,
    jint screenWidth, jint screenHeight,
    jint rowStride, jint pixelStride
) {
    // Check if backend is initialized
    if (!g_use_ncnn && !g_interpreter) {
        LOGE("Neither NCNN nor TFLite interpreter initialized");
        return nullptr;
    }
    if (g_use_ncnn && !g_ncnn_net) {
        LOGE("NCNN net not initialized");
        return nullptr;
    }

    uint8_t* src = (uint8_t*)env->GetDirectBufferAddress(buffer);
    if (!src) {
        LOGE("Failed to get buffer address");
        return nullptr;
    }

    long long t0 = getTimeUs();

    //==========================================================================
    //  NCNN Inference Path
    //==========================================================================
    if (g_use_ncnn) {
        // Validate inputs
        if (!g_ncnn_net) {
            LOGE("NCNN net is null!");
            return nullptr;
        }
        if (regionWidth <= 0 || regionHeight <= 0) {
            LOGE("Invalid region: %dx%d", regionWidth, regionHeight);
            return nullptr;
        }

        int H = g_input_height;
        int W = g_input_width;

        LOGD("NCNN detect: region=%dx%d, offset=(%d,%d), stride=%d, pixel=%d",
             regionWidth, regionHeight, offsetX, offsetY, rowStride, pixelStride);

        // Determine pixel format based on pixelStride
        int pixel_type;
        if (pixelStride == 4) {
            pixel_type = ncnn::Mat::PIXEL_RGBA2BGR;
        } else if (pixelStride == 3) {
            pixel_type = ncnn::Mat::PIXEL_RGB2BGR;
        } else {
            LOGE("Unsupported pixelStride: %d", pixelStride);
            return nullptr;
        }

        // Calculate safe source pointer
        const unsigned char* src_ptr = src + offsetY * rowStride + offsetX * pixelStride;

        // Create NCNN Mat from source pixels with resize
        ncnn::Mat in;
        try {
            in = ncnn::Mat::from_pixels_resize(
                src_ptr,
                pixel_type,
                regionWidth,
                regionHeight,
                rowStride,
                W, H
            );
        } catch (const std::exception& e) {
            LOGE("NCNN from_pixels_resize failed: %s", e.what());
            return nullptr;
        }

        if (in.empty()) {
            LOGE("NCNN input mat is empty!");
            return nullptr;
        }

        // Normalize: /255.0
        const float mean_vals[3] = {0.f, 0.f, 0.f};
        const float norm_vals[3] = {1/255.f, 1/255.f, 1/255.f};
        in.substract_mean_normalize(mean_vals, norm_vals);

        long long t1 = getTimeUs();

        // Create fresh extractor for this inference
        ncnn::Extractor ex = g_ncnn_net->create_extractor();

        // List all blob names for debugging (first time only)
        static bool logged_blobs = false;
        if (!logged_blobs) {
            LOGD("NCNN model has %d layers", g_ncnn_net->layers().size());
            for (int i = 0; i < g_ncnn_net->layers().size() && i < 20; i++) {
                const ncnn::Layer* layer = g_ncnn_net->layers()[i];
                LOGD("  Layer[%d]: type=%s, name=%s", i, layer->type.c_str(), layer->name.c_str());
            }
            logged_blobs = true;
        }

        // Set input - try common names
        int ret_input = ex.input("in0", in);
        if (ret_input != 0) {
            ret_input = ex.input("images", in);
            if (ret_input != 0) {
                ret_input = ex.input("input", in);
                if (ret_input != 0) {
                    LOGE("NCNN input failed for all names, ret=%d", ret_input);
                    return nullptr;
                }
            }
        }

        // Try official format first: single output "out0" with shape [h, w]
        // w = 64 + num_classes (DFL bbox format with reg_max_1=16)
        ncnn::Mat out;
        int ret_out = ex.extract("out0", out);

        if (ret_out != 0) {
            LOGE("NCNN extract out0 failed: %d", ret_out);
            return nullptr;
        }

        long long t2 = getTimeUs();
        LOGD("NCNN Preprocess: %lld us, Inference: %lld us, Total: %lld us", t1 - t0, t2 - t1, t2 - t0);

        // Parse output based on format
        float invW = 1.0f / screenWidth;
        float invH = 1.0f / screenHeight;

        std::vector<Detection> detections;

        // Check if this is official YOLOv8 format: 2D mat [h, w]
        // w = reg_max_1 * 4 + num_classes = 64 + num_classes
        const int reg_max_1 = 16;
        bool is_official_format = (out.dims == 2 && out.w > reg_max_1 * 4);

        // Check if output is int8 quantized
        bool is_int8_output = (out.elemsize == 1);  // int8 = 1 byte per element

        if (is_official_format) {
            // Official YOLOv8 NCNN format: [num_anchors, 64 + num_classes]
            int num_anchors = out.h;
            int feature_dim = out.w;
            int num_class = feature_dim - reg_max_1 * 4;
            if (num_class < 1) num_class = 1;
            g_num_classes = num_class;

            LOGD("NCNN official format: num_anchors=%d, feature_dim=%d, num_class=%d",
                 num_anchors, feature_dim, num_class);

            // YOLOv8 strides
            std::vector<int> strides = {8, 16, 32};

            int pred_row_offset = 0;
            for (size_t s = 0; s < strides.size(); s++) {
                int stride = strides[s];
                int num_grid_x = W / stride;
                int num_grid_y = H / stride;
                int num_grid = num_grid_x * num_grid_y;

                for (int i = 0; i < num_grid; i++) {
                    int row_idx = pred_row_offset + i;
                    const float* pred_row = out.row(row_idx);

                    // Find max class score
                    int label = -1;
                    float score = -1e9f;
                    for (int c = 0; c < num_class; c++) {
                        float s = pred_row[reg_max_1 * 4 + c];
                        if (s > score) {
                            score = s;
                            label = c;
                        }
                    }
                    score = sigmoid(score);

                    if (score < g_conf_thresh) continue;

                    // DFL bbox decode
                    float pred_ltrb[4];
                    for (int k = 0; k < 4; k++) {
                        // Apply softmax to reg_max_1 values
                        float softmax_sum = 0.f;
                        float softmax_max = -1e9f;
                        for (int l = 0; l < reg_max_1; l++) {
                            float v = pred_row[k * reg_max_1 + l];
                            if (v > softmax_max) softmax_max = v;
                        }
                        for (int l = 0; l < reg_max_1; l++) {
                            softmax_sum += expf(pred_row[k * reg_max_1 + l] - softmax_max);
                        }
                        float dis = 0.f;
                        for (int l = 0; l < reg_max_1; l++) {
                            float prob = expf(pred_row[k * reg_max_1 + l] - softmax_max) / softmax_sum;
                            dis += l * prob;
                        }
                        pred_ltrb[k] = dis * stride;
                    }

                    // Grid center
                    int grid_x = i % num_grid_x;
                    int grid_y = i / num_grid_x;
                    float pb_cx = (grid_x + 0.5f) * stride;
                    float pb_cy = (grid_y + 0.5f) * stride;

                    float x0 = pb_cx - pred_ltrb[0];
                    float y0 = pb_cy - pred_ltrb[1];
                    float x1 = pb_cx + pred_ltrb[2];
                    float y1 = pb_cy + pred_ltrb[3];

                    // Convert to normalized coordinates relative to input
                    x0 /= W; y0 /= H;
                    x1 /= W; y1 /= H;

                    // Clip
                    if (x0 < 0) x0 = 0;
                    if (y0 < 0) y0 = 0;
                    if (x1 > 1) x1 = 1;
                    if (y1 > 1) y1 = 1;

                    if (x1 <= x0 || y1 <= y0) continue;

                    detections.push_back({
                        (offsetX + x0 * regionWidth) * invW,
                        (offsetY + y0 * regionHeight) * invH,
                        (offsetX + x1 * regionWidth) * invW,
                        (offsetY + y1 * regionHeight) * invH,
                        score,
                        (float)label
                    });
                }
                pred_row_offset += num_grid;
            }
        } else {
            // Legacy format: 3D mat [channels, h, w] with 3 outputs
            // OR single output int8 format [1, channels, num_anchors]
            ncnn::Mat out0 = out;

            // Try to extract all 3 outputs (legacy format)
            ncnn::Mat out1, out2;
            int ret1 = ex.extract("out1", out1);
            int ret2 = ex.extract("out2", out2);

            int channels, total_anchors;
            bool is_legacy_3output = (ret1 == 0 && ret2 == 0);

            if (is_legacy_3output) {
                // Legacy 3-output format
                channels = out0.c;
                total_anchors = out0.w * out0.h + out1.w * out1.h + out2.w * out2.h;
            } else {
                // Single output format: [1, channels, num_anchors] or [channels, num_anchors]
                if (out0.dims == 3) {
                    channels = out0.c;
                    total_anchors = out0.w * out0.h;
                } else if (out0.dims == 2) {
                    channels = out0.h;
                    total_anchors = out0.w;
                } else {
                    LOGE("NCNN unsupported output dims: %d", out0.dims);
                    return nullptr;
                }
            }

            int num_class = channels - 4;
            if (num_class < 1) num_class = 1;
            g_num_classes = num_class;

            LOGD("NCNN format: channels=%d, total_anchors=%d, num_class=%d, int8=%d, legacy_3out=%d",
                 channels, total_anchors, num_class, is_int8_output, is_legacy_3output);

            // Dequantization parameters for int8 output
            // From user: (int8 - (-128)) * 0.004291
            const int8_t zero_point = -128;
            const float scale = 0.004291f;

            // Helper lambda to get value from output
            auto get_value = [&](int channel, int anchor) -> float {
                if (is_int8_output) {
                    int8_t val;
                    if (is_legacy_3output) {
                        // 3-output legacy format
                        int offset = 0;
                        if (anchor < out0.w * out0.h) {
                            val = ((const int8_t*)out0.data)[channel * out0.w * out0.h + anchor];
                        } else if (anchor < out0.w * out0.h + out1.w * out1.h) {
                            offset = out0.w * out0.h;
                            val = ((const int8_t*)out1.data)[channel * out1.w * out1.h + (anchor - offset)];
                        } else {
                            offset = out0.w * out0.h + out1.w * out1.h;
                            val = ((const int8_t*)out2.data)[channel * out2.w * out2.h + (anchor - offset)];
                        }
                    } else {
                        // Single output format
                        if (out0.dims == 3) {
                            val = ((const int8_t*)out0.data)[channel * out0.w * out0.h + anchor];
                        } else {
                            val = ((const int8_t*)out0.data)[channel * out0.w + anchor];
                        }
                    }
                    return (val - zero_point) * scale;
                } else {
                    // Float output
                    if (is_legacy_3output) {
                        const float* data = (const float*)out0.data;
                        int offset = 0;
                        if (anchor < out0.w * out0.h) {
                            return data[channel * out0.w * out0.h + anchor];
                        } else if (anchor < out0.w * out0.h + out1.w * out1.h) {
                            offset = out0.w * out0.h;
                            return ((const float*)out1.data)[channel * out1.w * out1.h + (anchor - offset)];
                        } else {
                            offset = out0.w * out0.h + out1.w * out1.h;
                            return ((const float*)out2.data)[channel * out2.w * out2.h + (anchor - offset)];
                        }
                    } else {
                        if (out0.dims == 3) {
                            return ((const float*)out0.data)[channel * out0.w * out0.h + anchor];
                        } else {
                            return ((const float*)out0.data)[channel * out0.w + anchor];
                        }
                    }
                }
            };

            for (int i = 0; i < total_anchors; ++i) {
                float cx = get_value(0, i);
                float cy = get_value(1, i);
                float bw = get_value(2, i);
                float bh = get_value(3, i);

                float score;
                int classId = 0;

                if (num_class <= 1) {
                    score = get_value(4, i);
                } else {
                    float maxProb = -1e9f;
                    int maxClass = 0;
                    for (int c = 0; c < num_class; c++) {
                        float prob = get_value(4 + c, i);
                        if (prob > maxProb) { maxProb = prob; maxClass = c; }
                    }
                    score = maxProb;
                    classId = maxClass;
                }

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
                    (float)classId
                });
            }
        }

        LOGD("NCNN Raw detections: %zu, num_classes: %d, conf_thresh: %.2f",
             detections.size(), g_num_classes, g_conf_thresh);

        // Apply NMS
        auto finalDetections = nms(detections, 0.45f);
        LOGD("NCNN After NMS: %zu", finalDetections.size());

        if (finalDetections.empty()) {
            return nullptr;
        }

        // Return results
        jfloatArray res = env->NewFloatArray(finalDetections.size() * 6);
        if (!res) {
            LOGE("Failed to allocate jfloatArray");
            return nullptr;
        }
        float* dst = env->GetFloatArrayElements(res, nullptr);
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

    //==========================================================================
    //  TFLite Inference Path (existing code)
    //==========================================================================

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
    float debugMaxScore = -1e9f;
    int debugMaxClass = -1;

    TfLiteType output_type = TfLiteTensorType(output_tensor);
    void* output_data = const_cast<void*>(TfLiteTensorData(output_tensor));
    TfLiteQuantizationParams qp_output = TfLiteTensorQuantizationParams(output_tensor);

    // Auto-detect bbox format: normalized [0,1] vs pixel coords (0~input_size)
    // If any bbox coord > 1.5, treat as pixel coords and normalize
    auto normalizeIfNeeded = [](float cx, float cy, float bw, float bh,
                                 float& ncx, float& ncy, float& nbw, float& nbh) {
        if (cx > 1.5f || cy > 1.5f) {
            float inv = 1.0f / (float)g_input_width;
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

        for (int i = 0; i < g_num_outputs; ++i) {
            float cx_raw = (data[i] - out_zp) * out_scale;
            float cy_raw = (data[g_num_outputs + i] - out_zp) * out_scale;
            float bw_raw = (data[2 * g_num_outputs + i] - out_zp) * out_scale;
            float bh_raw = (data[3 * g_num_outputs + i] - out_zp) * out_scale;
            float cx, cy, bw, bh;
            normalizeIfNeeded(cx_raw, cy_raw, bw_raw, bh_raw, cx, cy, bw, bh);

            float score;
            int classId = 0;

            if (g_num_classes <= 1) {
                // Single-class: channel 4 = objectness score
                score = (data[4 * g_num_outputs + i] - out_zp) * out_scale;
            } else {
                // Multi-class: channels 4..4+num_classes-1 = class probabilities
                float maxProb = -1e9f;
                int maxClass = 0;
                for (int c = 0; c < g_num_classes; c++) {
                    float prob = (data[(4 + c) * g_num_outputs + i] - out_zp) * out_scale;
                    if (prob > maxProb) { maxProb = prob; maxClass = c; }
                }
                score = maxProb;
                classId = maxClass;
            }

            if (score > debugMaxScore) { debugMaxScore = score; debugMaxClass = classId; }
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
                (float)classId
            });
        }
    } else {
        // Float32 output
        float* data = static_cast<float*>(output_data);

        for (int i = 0; i < g_num_outputs; ++i) {
            float cx_raw = data[i];
            float cy_raw = data[g_num_outputs + i];
            float bw_raw = data[2 * g_num_outputs + i];
            float bh_raw = data[3 * g_num_outputs + i];
            float cx, cy, bw, bh;
            normalizeIfNeeded(cx_raw, cy_raw, bw_raw, bh_raw, cx, cy, bw, bh);

            float score;
            int classId = 0;

            if (g_num_classes <= 1) {
                // Single-class: channel 4 = objectness score
                score = data[4 * g_num_outputs + i];
            } else {
                // Multi-class: channels 4..4+num_classes-1 = class probabilities
                float maxProb = -1e9f;
                int maxClass = 0;
                for (int c = 0; c < g_num_classes; c++) {
                    float prob = data[(4 + c) * g_num_outputs + i];
                    if (prob > maxProb) { maxProb = prob; maxClass = c; }
                }
                score = maxProb;
                classId = maxClass;
            }

            if (score > debugMaxScore) { debugMaxScore = score; debugMaxClass = classId; }
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
                (float)classId
            });
        }
    }

    LOGD("Raw detections: %zu, num_classes: %d, max_raw_score: %.3f (classId=%d), conf_thresh: %.2f",
         detections.size(), g_num_classes, debugMaxScore, debugMaxClass, g_conf_thresh);

    // Apply NMS
    auto finalDetections = nms(detections, 0.45f);
    LOGD("After NMS: %zu", finalDetections.size());
    for (size_t k = 0; k < finalDetections.size() && k < 5; ++k) {
        LOGD("  det[%zu] classId=%.0f score=%.3f box=(%.2f,%.2f,%.2f,%.2f)",
             k, finalDetections[k].classId, finalDetections[k].score,
             finalDetections[k].x1, finalDetections[k].y1,
             finalDetections[k].x2, finalDetections[k].y2);
    }

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
Java_team_maodie_aimbot_inference_JniCallBack_setConfidence(JNIEnv* /*env*/, jobject /*thiz*/, jfloat threshold) {
    g_conf_thresh = threshold;
    LOGD("Confidence threshold set to %.2f", g_conf_thresh);
}

//==============================================================================
//  Set force CPU mode
//==============================================================================
extern "C"
JNIEXPORT void JNICALL
Java_team_maodie_aimbot_inference_JniCallBack_setForceCpu(JNIEnv* /*env*/, jobject /*thiz*/, jboolean useCpu) {
    g_force_cpu = useCpu;
    LOGD("Force CPU mode: %s", useCpu ? "ON" : "OFF");
}

//==============================================================================
//  Set CPU thread count
//==============================================================================
extern "C"
JNIEXPORT void JNICALL
Java_team_maodie_aimbot_inference_JniCallBack_setCpuThreads(JNIEnv* /*env*/, jobject /*thiz*/, jint threads) {
    g_cpu_threads = threads;
    LOGD("CPU threads set to %d", threads);
}

//==============================================================================
//  Set input size (for NCNN models before init)
//==============================================================================
extern "C"
JNIEXPORT void JNICALL
Java_team_maodie_aimbot_inference_JniCallBack_setInputSize(JNIEnv* /*env*/, jobject /*thiz*/, jint width, jint height) {
    g_input_width = width;
    g_input_height = height;
    LOGD("Input size set to %dx%d", width, height);
}

//==============================================================================
//  Get backend type
//==============================================================================
extern "C"
JNIEXPORT jstring JNICALL
Java_team_maodie_aimbot_inference_JniCallBack_getBackend(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(g_backend_type.c_str());
}

//==============================================================================
//  Release
//==============================================================================
extern "C"
JNIEXPORT void JNICALL
Java_team_maodie_aimbot_inference_JniCallBack_release(JNIEnv* /*env*/, jobject /*thiz*/) {
    // Release TFLite resources
    if (g_interpreter) {
        TfLiteInterpreterDelete(g_interpreter);
        g_interpreter = nullptr;
    }
    deleteDelegate();
    if (g_model) {
        TfLiteModelDelete(g_model);
        g_model = nullptr;
    }

    // Release NCNN resources
    if (g_ncnn_ex) {
        delete g_ncnn_ex;
        g_ncnn_ex = nullptr;
    }
    if (g_ncnn_net) {
        delete g_ncnn_net;
        g_ncnn_net = nullptr;
    }
    g_use_ncnn = false;
}