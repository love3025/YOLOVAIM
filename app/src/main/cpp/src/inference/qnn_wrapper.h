//==============================================================================
//
//  QNN AI Engine Wrapper - 替代 OnnxRuntime
//
//==============================================================================
#pragma once

#include "qnn/QnnInterface.h"
#include "qnn/QnnContext.h"
#include "qnn/QnnGraph.h"
#include "qnn/QnnTensor.h"
#include "qnn/QnnDevice.h"
#include "qnn/QnnBackend.h"
#include <android/log.h>
#include <dlfcn.h>
#include <cstring>
#include <cstdio>

#define LOGD_QNN(...) __android_log_print(ANDROID_LOG_DEBUG, "QNN_Wrapper", __VA_ARGS__)

// QNN Provider 函数类型
typedef Qnn_ErrorHandle_t (*QnnInterface_GetProvidersFn_t)(
    const QnnInterface_t*** providerList, uint32_t* numProviders);

class QnnNativeEngine {
public:
    QnnEngine();
    ~QnnEngine();

    bool init(const char* model_path);
    void release();

    bool execute(float* input_data, int input_size, float* output_data, int output_size);
    bool isInitialized() const { return m_initialized; }

private:
    bool loadLibrary();
    bool getProviders();
    bool createDevice();
    bool createBackend();
    bool loadModel(const char* model_path);

    bool m_initialized = false;
    void* m_libHtp = nullptr;
    void* m_libSystem = nullptr;
    void* m_libBackend = nullptr;
    const QnnInterface_t* m_interface = nullptr;
    Qnn_DeviceHandle_t m_device = nullptr;
    Qnn_BackendHandle_t m_backend = nullptr;
    Qnn_ContextHandle_t m_context = nullptr;
};