//==============================================================================
//  JNI Bridge - Delegates to InferenceEngine implementations
//==============================================================================
#include <jni.h>
#include <memory>
#include "inference_engine.h"
#include "ncnn_engine.h"
#include "litert_engine.h"

static std::unique_ptr<InferenceEngine> g_engine;

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
    } else {
        g_engine = std::make_unique<LiteRtEngine>();
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
    if (g_engine) g_engine->setForceCpu(useCpu);
    LOGD("Force CPU: %s", useCpu ? "ON" : "OFF");
}

extern "C"
JNIEXPORT void JNICALL
Java_team_maodie_aimbot_inference_JniCallBack_setCpuThreads(JNIEnv*, jobject, jint threads) {
    if (g_engine) g_engine->setCpuThreads(threads);
    LOGD("CPU threads: %d", threads);
}

extern "C"
JNIEXPORT void JNICALL
Java_team_maodie_aimbot_inference_JniCallBack_setInputSize(JNIEnv*, jobject, jint width, jint height) {
    if (g_engine) g_engine->setInputSize(width, height);
    LOGD("Input size: %dx%d", width, height);
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
