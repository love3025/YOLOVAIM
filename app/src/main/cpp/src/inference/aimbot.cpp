//==============================================================================
//  JNI Bridge - Delegates to InferenceEngine implementations
//==============================================================================
#include <jni.h>
#include <memory>
#include "inference_engine.h"
#include "ncnn_engine.h"
#include "litert_engine.h"
#include "mediatek_engine.h"

static std::unique_ptr<InferenceEngine> g_engine;
static bool g_force_cpu = false;
static int g_cpu_threads = 1;
static int g_input_size = 256;
static int g_output_format = 0;

//==============================================================================
//  Init
//==============================================================================
extern "C"
JNIEXPORT jboolean JNICALL
Java_team_maodie_aimbot_inference_JniCallBack_init(JNIEnv* env, jobject, jstring model_path) {
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGD("init: %s", path);

    if (g_engine) {
        g_engine->release();
        g_engine.reset();
    }

    if (InferenceEngine::isNcnnModel(path)) {
        g_engine = std::make_unique<NcnnEngine>();
        g_engine->setForceCpu(g_force_cpu);
        g_engine->setCpuThreads(g_cpu_threads);
        g_engine->setInputSize(g_input_size, g_input_size);
        g_engine->setOutputFormat(g_output_format);
    } else if (g_force_cpu) {
        // Force CPU: skip MTK NPU and LiteRT hardware delegates, use CPU only
        LOGD("Force CPU enabled, skipping NPU/GPU, using CPU only");
        g_engine = std::make_unique<LiteRtEngine>();
        g_engine->setForceCpu(true);
        g_engine->setCpuThreads(g_cpu_threads);
        g_engine->setInputSize(g_input_size, g_input_size);
        g_engine->setOutputFormat(g_output_format);
    } else {
        // Fallback chain: MTK NPU → LiteRT (QNN HTP → GPU → CPU)
        g_engine = std::make_unique<MtkEngine>();
        g_engine->setOutputFormat(g_output_format);
        if (g_engine->init(path)) {
            env->ReleaseStringUTFChars(model_path, path);
            return JNI_TRUE;
        }
        LOGD("MTK NPU unavailable, falling back to LiteRT");

        g_engine = std::make_unique<LiteRtEngine>();
        g_engine->setCpuThreads(g_cpu_threads);
        g_engine->setInputSize(g_input_size, g_input_size);
        g_engine->setOutputFormat(g_output_format);
    }

    bool ok = g_engine->init(path);
    env->ReleaseStringUTFChars(model_path, path);

    if (!ok) {
        g_engine.reset();
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

//==============================================================================
//  Detect
//==============================================================================
extern "C"
JNIEXPORT jfloatArray JNICALL
Java_team_maodie_aimbot_inference_JniCallBack_detect(
    JNIEnv* env, jobject,
    jobject buffer,
    jint offsetX, jint offsetY,
    jint regionWidth, jint regionHeight,
    jint screenWidth, jint screenHeight,
    jint rowStride, jint pixelStride)
{
    if (!g_engine || !g_engine->isInitialized()) {
        LOGE("Engine not initialized");
        return nullptr;
    }

    uint8_t* src = (uint8_t*)env->GetDirectBufferAddress(buffer);
    if (!src) {
        LOGE("Failed to get buffer address");
        return nullptr;
    }

    auto detections = g_engine->detect(
        src, offsetX, offsetY,
        regionWidth, regionHeight,
        screenWidth, screenHeight,
        rowStride, pixelStride);

    if (detections.empty()) return nullptr;

    jfloatArray res = env->NewFloatArray(detections.size() * 6);
    if (!res) return nullptr;

    float* dst = env->GetFloatArrayElements(res, nullptr);
    for (size_t i = 0; i < detections.size(); ++i) {
        dst[i * 6 + 0] = detections[i].classId;
        dst[i * 6 + 1] = detections[i].score;
        dst[i * 6 + 2] = detections[i].x1;
        dst[i * 6 + 3] = detections[i].y1;
        dst[i * 6 + 4] = detections[i].x2;
        dst[i * 6 + 5] = detections[i].y2;
    }
    env->ReleaseFloatArrayElements(res, dst, 0);
    return res;
}

//==============================================================================
//  Config
//==============================================================================
extern "C"
JNIEXPORT void JNICALL
Java_team_maodie_aimbot_inference_JniCallBack_setConfidence(JNIEnv*, jobject, jfloat threshold) {
    if (g_engine) g_engine->setConfidence(threshold);
    LOGD("Confidence: %.2f", threshold);
}

extern "C"
JNIEXPORT void JNICALL
Java_team_maodie_aimbot_inference_JniCallBack_setForceCpu(JNIEnv*, jobject, jboolean useCpu) {
    g_force_cpu = useCpu;
    if (g_engine) g_engine->setForceCpu(useCpu);
    LOGD("Force CPU: %s", useCpu ? "ON" : "OFF");
}

extern "C"
JNIEXPORT void JNICALL
Java_team_maodie_aimbot_inference_JniCallBack_setCpuThreads(JNIEnv*, jobject, jint threads) {
    g_cpu_threads = threads;
    if (g_engine) g_engine->setCpuThreads(threads);
    LOGD("CPU threads: %d", threads);
}

extern "C"
JNIEXPORT void JNICALL
Java_team_maodie_aimbot_inference_JniCallBack_setInputSize(JNIEnv*, jobject, jint width, jint height) {
    g_input_size = (width > 0) ? width : height;
    if (g_engine) g_engine->setInputSize(width, height);
    LOGD("Input size: %dx%d", width, height);
}

extern "C"
JNIEXPORT void JNICALL
Java_team_maodie_aimbot_inference_JniCallBack_setOutputFormat(JNIEnv*, jobject, jint format) {
    g_output_format = format;
    if (g_engine) g_engine->setOutputFormat(format);
    LOGD("Output format: %s", format == 1 ? "xyxy" : "cxcywh");
}

//==============================================================================
//  Metadata
//==============================================================================
extern "C"
JNIEXPORT jstring JNICALL
Java_team_maodie_aimbot_inference_JniCallBack_getBackend(JNIEnv* env, jobject) {
    if (g_engine) return env->NewStringUTF(g_engine->getBackendType().c_str());
    return env->NewStringUTF("none");
}

//==============================================================================
//  Release
//==============================================================================
extern "C"
JNIEXPORT void JNICALL
Java_team_maodie_aimbot_inference_JniCallBack_release(JNIEnv*, jobject) {
    if (g_engine) {
        g_engine->release();
        g_engine.reset();
    }
}
