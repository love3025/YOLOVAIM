#pragma once

#include <vector>
#include <algorithm>
#include <memory>
#include <string>
#include <cstdio>
#include <cstring>
#include <cmath>
#include <sys/time.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cerrno>
#include <android/log.h>

#define LOG_TAG "YOLOVAIM"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

// Per-stage latency log. Use only with `us` and `count` arguments so the format
// matches the Kotlin side ("YolovaimLatency") for easy grep.
#define LAT_TAG "YolovaimLatency"
#define LOGLAT(...) __android_log_print(ANDROID_LOG_DEBUG, LAT_TAG, __VA_ARGS__)

// Per-frame trace logging — compiled out by default in *every* build type.
//
// These sites fire once per inference frame (100-300/s on QNN HTP). Each costs
// a printf-style format plus an __android_log_write socket round-trip, on the
// same core the inference loop runs on, and logd burns comparable CPU on the
// other end. Nothing strips them in release either: the app sets
// isMinifyEnabled = false and no NDEBUG guard existed here. At that rate the
// output is unreadable as a debugging aid anyway.
//
// They are also measured nowhere: every one of these sits *outside* the
// tPreStart..tPostEnd window that getInferTimings() reports, so the cost never
// showed up in the in-app "推理 xx fps" readout.
//
// Build with -DYOLOVAIM_TRACE to turn them back on. Low-frequency init and
// error logging deliberately keeps using LOGD/LOGE so release builds stay
// diagnosable.
#ifdef YOLOVAIM_TRACE
#define LOGTRACE(...)    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGTRACELAT(...) __android_log_print(ANDROID_LOG_DEBUG, LAT_TAG, __VA_ARGS__)
#else
#define LOGTRACE(...)    do {} while (0)
#define LOGTRACELAT(...) do {} while (0)
#endif

struct Detection {
    float x1, y1, x2, y2, score, classId;
};

// 包名。原来在 qnn_engine.cpp 和 litert_engine.cpp 里各硬编码了一份缓存路径,
// 改包名就会静默失效(两处缓存都是失败不报错、只是每次冷启动重新编译)。
#define YOLOVAIM_PKG "io.github.love3025.yolovaim"

// 应用私有 cache 子目录的绝对路径,按当前 Android 用户解析,并确保它存在。
//
// 不能写死 /data/data/<pkg>/cache/...:/data/data 是 /data/user/0 的符号链接,
// 那条路径只在主用户下正确。分身 / 工作资料 / 多用户下 uid = userId*100000 +
// appId,真实目录是 /data/user/<userId>/<pkg>,写主用户的目录会 EACCES ——
// 而 TFLite GPU 的 kernel 序列化和 QNN 的图缓存都是失败不报错,表现只是「每次
// 启动都要多花一两秒重新编译」,没人会想到是路径写死了。
//
// 目录不存在时序列化同样静默不生效,所以顺手建一次(父目录 cache/ 由系统保证
// 存在)。返回空串表示不可用 —— 调用方据此关掉序列化,而不是带着一个坏路径走。
inline std::string appCacheDir(const char* leaf) {
    const unsigned long userId = (unsigned long)getuid() / 100000UL;
    char buf[256];
    snprintf(buf, sizeof(buf), "/data/user/%lu/%s/cache/%s", userId, YOLOVAIM_PKG, leaf);
    if (mkdir(buf, 0700) != 0 && errno != EEXIST) {
        LOGW("appCacheDir: mkdir %s failed (errno=%d)", buf, errno);
        return std::string();
    }
    return std::string(buf);
}

// 裁剪区是否整块落在源图内。
//
// offsetX/offsetY 是预处理的读取起点,而逐像素采样(litert 的 srcX/srcY_lut、
// ncnn 的 src_ptr、mtk 的 srcX_lut)一律不做边界检查 —— 负的 offset 就是从
// ImageReader 的 direct buffer 之前开始读,offset+region 超过屏幕就是读到它
// 之后。「截取范围」滑条上限 800(边长 1600px)而横屏采集高度典型 1080,所以
// 这不是理论情况:滑条拖过短边的一半就越界。
//
// Kotlin 侧已经按当前采集尺寸夹过一次,这里是各后端 detect() 入口的防线:
// 采集尺寸随旋转变、offset 的计算又分散在调用方,越界的代价是读别的进程的
// 图形内存或直接 SIGSEGV,不值得只靠一处校验。
inline bool cropInBounds(int offsetX, int offsetY,
                         int regionWidth, int regionHeight,
                         int screenWidth, int screenHeight) {
    if (regionWidth <= 0 || regionHeight <= 0) return false;
    if (screenWidth <= 0 || screenHeight <= 0) return false;
    if (offsetX < 0 || offsetY < 0) return false;
    if (offsetX > screenWidth - regionWidth) return false;
    if (offsetY > screenHeight - regionHeight) return false;
    return true;
}

inline long long getTimeUs() {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return tv.tv_sec * 1000000LL + tv.tv_usec;
}

inline float sigmoid(float x) {
    return 1.0f / (1.0f + expf(-x));
}

inline std::vector<Detection> nms(std::vector<Detection>& boxes, float iouThreshold) {
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
