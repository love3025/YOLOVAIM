#pragma once

#include <tensorflow/lite/c/common.h>
#include <string>

// QNN HTP delegate builder for Qualcomm Hexagon NPU
class QnnEngine {
public:
    QnnEngine();
    ~QnnEngine();

    // Build QNN HTP delegate, returns nullptr if not available
    TfLiteDelegate* buildDelegate();

    // Delete the delegate
    void deleteDelegate();

    // Set model_token used by QNN cache (cache_dir/<token>_<fingerprint>.bin).
    // Different token → different cache file, so per-model caching stays
    // isolated. Pass nullptr to fall back to the legacy default token.
    // Pointer must outlive buildDelegate(); the typical caller is the owning
    // InferenceEngine which holds a std::string for that lifetime.
    void setModelToken(const char* token) { m_model_token = token; }

    // Get backend name
    std::string getBackendName() const { return "QNN HTP"; }

private:
    static bool isQualcommSnapdragon();

    TfLiteDelegate* m_delegate = nullptr;
    bool m_preloaded = false;
    char m_native_lib_dir[512] = {0};
    const char* m_model_token = nullptr;
};
