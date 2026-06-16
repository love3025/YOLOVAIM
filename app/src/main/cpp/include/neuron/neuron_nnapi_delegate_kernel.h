/*
 * Copyright (C) 2021 MediaTek Inc.
 * Stub implementation - NNAPI fallback removed since aimbot.cpp
 * handles the delegate fallback chain (QNN HTP -> Neuron -> CPU).
 */

#ifndef TENSORFLOW_LITE_EXPERIMENTAL_DELEGATES_NEURON_NNAPI_KERNEL_H_
#define TENSORFLOW_LITE_EXPERIMENTAL_DELEGATES_NEURON_NNAPI_KERNEL_H_

#include <memory>
#include "tensorflow/lite/c/common.h"
#include "tensorflow/lite/minimal_logging.h"

namespace tflite {

// Forward declarations to avoid pulling in full NNAPI delegate
class StatefulNnApiDelegate {
 public:
  struct Options {
    enum ExecutionPreference {
      kLowPower = 0,
      kFastSingleAnswer = 1,
      kSustainedSpeed = 2,
      kTurboBoost = 3,
    };
    ExecutionPreference execution_preference = kFastSingleAnswer;
    bool allow_fp16 = false;
  };
  explicit StatefulNnApiDelegate(const Options&) {}
};

namespace neuron {

// Stub: NNAPI fallback is not needed since aimbot.cpp handles the chain
class NeuronNNAPIDelegateKernel : public SimpleDelegateKernelInterface {
 public:
  explicit NeuronNNAPIDelegateKernel(
      StatefulNnApiDelegate::Options options =
          StatefulNnApiDelegate::Options()) {
    TFLITE_LOG_PROD(tflite::TFLITE_LOG_INFO,
                    "NeuronNNAPIDelegateKernel stub ctor() - NNAPI disabled");
  }

  ~NeuronNNAPIDelegateKernel() {}

  TfLiteStatus Init(TfLiteContext* context,
                    const TfLiteDelegateParams* params) override {
    TFLITE_LOG_PROD(tflite::TFLITE_LOG_ERROR,
                    "NeuronNNAPIDelegateKernel::Init called but NNAPI is disabled");
    return kTfLiteError;
  }

  TfLiteStatus Prepare(TfLiteContext* context, TfLiteNode* node) override {
    return kTfLiteError;
  }

  TfLiteStatus Eval(TfLiteContext* context, TfLiteNode* node) override {
    return kTfLiteError;
  }
};

}  // namespace neuron
}  // namespace tflite

#endif  // TENSORFLOW_LITE_EXPERIMENTAL_DELEGATES_NEURON_NNAPI_KERNEL_H_
