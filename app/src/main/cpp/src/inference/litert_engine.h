#pragma once

#include "inference_engine.h"
#include "qnn_engine.h"
#include <tensorflow/lite/c/c_api.h>
#include <tensorflow/lite/c/common.h>
#include <tensorflow/lite/delegates/gpu/delegate.h>
#include <functional>
#include <string>

class LiteRtEngine : public InferenceEngine {
public:
    LiteRtEngine();
    ~LiteRtEngine() override;

    bool init(const char* model_path) override;
    void release() override;

    std::vector<Detection> detect(
        uint8_t* src,
        int offsetX, int offsetY,
        int regionWidth, int regionHeight,
        int screenWidth, int screenHeight,
        int rowStride, int pixelStride
    ) override;

    std::string getBackendType() const override { return m_backend_type; }
    bool isInitialized() const override { return m_initialized; }

    // Per-model QNN HTP cache token. Each model should get a unique token
    // (typically its basename) so QNN HTP graph cache lives in its own file
    // and warm-up compiles survive across cold launches.
    void setQnnModelToken(const char* token);

    // QNN HTP 在这台设备上到底能不能用。init() 里试过一次之后就有结论，
    // 供 prewarmQnn 判断要不要白跑一趟(见 yolovaim.cpp)。
    //   0 = 还没试过   1 = 可用   -1 = 试过，这台机器用不了
    // 用不了的典型原因是 SoC 的 HTP 版本没有对应 skel(如 SM8250 是 V66,
    // 而随包只有 V68-V81) —— 换机器就变，所以只能靠试，不能查芯片表。
    static int qnnUsable();

private:
    // GPU delegate builder (universal, stays here)
    TfLiteDelegate* buildGpuDelegate();
    void deleteDelegate();

    // Delegate builders (independent modules)
    QnnEngine m_qnn_engine;      // Qualcomm HTP
    // NeuronEngine m_neuron_engine;  // MediaTek Neuron - TODO

    TfLiteModel* m_model = nullptr;
    TfLiteInterpreter* m_interpreter = nullptr;
    TfLiteDelegate* m_delegate = nullptr;
    std::string m_backend_type = "LiteRT";
    bool m_initialized = false;
    std::string m_qnn_model_token;  // held by-value so m_qnn_engine can borrow .c_str()
    // GPU delegate 的 OpenCL kernel 序列化缓存键。同样按值持有:
    // TfLiteGpuDelegateOptionsV2.model_token 只借指针，不拷贝。
    std::string m_gpu_token;
    // 模型文件的 size+mtime 指纹。缓存键必须标定模型本身,而 basename 会撞
    // (best.tflite 是 YOLO 的默认导出名) —— 详见 buildGpuDelegate()。
    std::string m_model_fingerprint;
    // 序列化目录。serialization_dir 同样只借指针，所以按值留在对象上。
    std::string m_gpu_cache_dir;
};
