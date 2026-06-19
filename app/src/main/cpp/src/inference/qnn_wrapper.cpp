//==============================================================================
//  QNN AI Engine Implementation
//==============================================================================
#include "qnn_wrapper.h"

QnnNativeEngine::QnnEngine() : m_libHtp(nullptr), m_libSystem(nullptr), m_libBackend(nullptr),
                         m_interface(nullptr), m_device(nullptr),
                         m_backend(nullptr), m_context(nullptr) {}

QnnNativeEngine::~QnnEngine() {
    release();
}

bool QnnNativeEngine::init(const char* model_path) {
    if (!loadLibrary()) return false;
    if (!getProviders()) return false;

    // 访问 v2_34 接口
    auto& v2_34 = m_interface->v2_34;

    // 创建设备
    Qnn_ErrorHandle_t err = v2_34.deviceCreate(nullptr, nullptr, &m_device);
    if (err != QNN_SUCCESS) {
        LOGD_QNN("deviceCreate 失败: %d", err);
        return false;
    }
    LOGD_QNN("Device 创建成功");

    // 创建后端
    err = v2_34.backendCreate(m_device, nullptr, &m_backend);
    if (err != QNN_SUCCESS) {
        LOGD_QNN("backendCreate 失败: %d", err);
        return false;
    }
    LOGD_QNN("Backend 创建成功");

    // 加载模型
    if (!loadModel(model_path)) return false;

    m_initialized = true;
    LOGD_QNN("QNN 初始化成功: %s", model_path);
    return true;
}

bool QnnNativeEngine::loadLibrary() {
    // 加载 QNN HTP 库
    m_libHtp = dlopen("libQnnHtp.so", RTLD_NOW);
    if (!m_libHtp) {
        LOGD_QNN("加载 libQnnHtp.so 失败: %s", dlerror());
        return false;
    }
    LOGD_QNN("libQnnHtp.so 加载成功");

    // 加载 System 库
    m_libSystem = dlopen("libQnnSystem.so", RTLD_NOW);
    if (!m_libSystem) {
        LOGD_QNN("加载 libQnnSystem.so 失败: %s", dlerror());
        return false;
    }
    LOGD_QNN("libQnnSystem.so 加载成功");

    return true;
}

bool QnnNativeEngine::getProviders() {
    // 直接从 libQnnHtp.so 获取 QnnInterface_getProviders
    QnnInterface_GetProvidersFn_t getProvidersFn =
        (QnnInterface_GetProvidersFn_t)dlsym(m_libHtp, "QnnInterface_getProviders");

    if (!getProvidersFn) {
        LOGD_QNN("dlsym QnnInterface_getProviders 失败: %s", dlerror());
        return false;
    }

    const QnnInterface_t** providers = nullptr;
    uint32_t numProviders = 0;

    Qnn_ErrorHandle_t err = getProvidersFn(&providers, &numProviders);
    if (err != QNN_SUCCESS || numProviders == 0) {
        LOGD_QNN("QnnInterface_getProviders 失败: err=%d, numProviders=%d", err, numProviders);
        return false;
    }

    for (uint32_t i = 0; i < numProviders; i++) {
        const char* name = providers[i]->providerName ? providers[i]->providerName : "NULL";
        LOGD_QNN("Provider[%u]: %s", i, name);
    }

    m_interface = providers[0];
    LOGD_QNN("使用 Provider: %s", m_interface->providerName ? m_interface->providerName : "NULL");
    return true;
}

bool QnnNativeEngine::createDevice() {
    return true;
}

bool QnnNativeEngine::createBackend() {
    return true;
}

bool QnnNativeEngine::loadModel(const char* model_path) {
    FILE* f = fopen(model_path, "rb");
    if (!f) {
        LOGD_QNN("打开模型文件失败: %s", model_path);
        return false;
    }

    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);

    uint8_t* modelBuffer = new uint8_t[size];
    size_t readSize = fread(modelBuffer, 1, size, f);
    fclose(f);

    if (readSize != (size_t)size) {
        LOGD_QNN("读取模型文件失败");
        delete[] modelBuffer;
        return false;
    }

    LOGD_QNN("模型文件大小: %ld bytes", size);

    auto& v2_34 = m_interface->v2_34;
    Qnn_ContextBinarySize_t contextSize = size;
    Qnn_ContextHandle_t context = nullptr;

    Qnn_ErrorHandle_t err = v2_34.contextCreateFromBinary(
        m_backend, m_device, nullptr, modelBuffer, contextSize, &context, nullptr);

    delete[] modelBuffer;

    if (err != QNN_SUCCESS) {
        LOGD_QNN("contextCreateFromBinary 失败: %d", err);
        return false;
    }

    m_context = context;
    LOGD_QNN("Context 创建成功");
    return true;
}

void QnnNativeEngine::release() {
    auto& v2_34 = m_interface->v2_34;

    if (m_context) {
        v2_34.contextFree(m_context, nullptr);
        m_context = nullptr;
    }
    if (m_backend) {
        v2_34.backendFree(m_backend);
        m_backend = nullptr;
    }
    if (m_device) {
        v2_34.deviceFree(m_device);
        m_device = nullptr;
    }
    if (m_libSystem) {
        dlclose(m_libSystem);
        m_libSystem = nullptr;
    }
    if (m_libHtp) {
        dlclose(m_libHtp);
        m_libHtp = nullptr;
    }
    LOGD_QNN("QNN 资源已释放");
}

bool QnnNativeEngine::execute(float* input_data, int input_size, float* output_data, int output_size) {
    if (!m_initialized) {
        LOGD_QNN("QNN 未初始化");
        return false;
    }
    LOGD_QNN("QNN execute 暂未实现完整逻辑");
    return false;
}