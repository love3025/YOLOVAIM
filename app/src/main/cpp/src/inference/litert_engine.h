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
};
