#pragma once

#include "inference_engine.h"
#include "lite_rt_api.h"

//==============================================================================
//  MtkEngine - MediaTek NPU Inference Engine
//  Uses LiteRT + DispatchLibraryDir auto-discovery (matches Google sample)
//==============================================================================
class MtkEngine : public InferenceEngine {
public:
    MtkEngine();
    ~MtkEngine() override;

    bool init(const char* model_path) override;
    void release() override;

    std::vector<Detection> detect(
        uint8_t* src,
        int offsetX, int offsetY,
        int regionWidth, int regionHeight,
        int screenWidth, int screenHeight,
        int rowStride, int pixelStride
    ) override;

    std::string getBackendType() const override { return "MTK NPU"; }
    bool isInitialized() const override { return m_initialized; }

private:
    static bool isMediaTekDevice();

    LiteRtApi m_api;

    LiteRtEnvironment    m_environment = nullptr;
    LiteRtOptions        m_options = nullptr;
    LiteRtModel          m_model = nullptr;
    LiteRtCompiledModel  m_compiled_model = nullptr;

    LiteRtTensorBuffer   m_input_buffer = nullptr;
    LiteRtRankedTensorType m_input_tensor_type = {};
    size_t m_input_packed_size = 0;

    std::vector<OutputInfo> m_outputs;
    LiteRtLayout m_input_runtime_layout = {};
    std::vector<LiteRtLayout> m_output_runtime_layouts;
    bool m_initialized = false;
};
