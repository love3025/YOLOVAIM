#pragma once

#include "inference_engine.h"
#include <ncnn/net.h>

class NcnnEngine : public InferenceEngine {
public:
    NcnnEngine();
    ~NcnnEngine() override;

    bool init(const char* model_path) override;
    void release() override;

    std::vector<Detection> detect(
        uint8_t* src,
        int offsetX, int offsetY,
        int regionWidth, int regionHeight,
        int screenWidth, int screenHeight,
        int rowStride, int pixelStride
    ) override;

    std::string getBackendType() const override { return "NCNN"; }
    bool isInitialized() const override { return m_initialized; }

private:
    std::vector<Detection> parseOfficialFormat(
        const ncnn::Mat& out, int W, int H,
        int offsetX, int offsetY,
        int regionWidth, int regionHeight,
        float invW, float invH);

    std::vector<Detection> parseLegacyFormat(
        ncnn::Extractor& ex,
        const ncnn::Mat& out0, int W, int H,
        int offsetX, int offsetY,
        int regionWidth, int regionHeight,
        float invW, float invH);

    ncnn::Net* m_net = nullptr;
    bool m_initialized = false;
};
