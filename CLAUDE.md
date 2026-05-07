# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android FPS game AI aiming assistant using TFLite with NNAPI delegate for real-time object detection on Qualcomm Snapdragon (Hexagon DSP/NPU). The app captures the screen via MediaProjection, runs YOLOv8n inference, and draws detection overlays.

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing config)
./gradlew assembleRelease

# Clean and rebuild
./gradlew clean assembleDebug

# Install to device
./gradlew installDebug
```

## Architecture

```
MainActivity.kt                    # Entry point - permissions, model loading from JSON
    ↓
FloatService.kt                    # Foreground service - owns the UI layer
    ├── FloatBallView.kt           # Draggable toggle widget (black/white circle)
    ├── OverlayCanvasView.kt       # Full-screen transparent overlay (detection boxes)
    └── GuiPanelView.kt           # Control panel (Aimbot/Triggerbot/AntiFlash tabs)
                                     ↓
JniCallBack.kt ───────────────────# JNI bridge (native libaimbot.so)
    └── aimbot.cpp (cpp/)          # TFLite inference with NNAPI delegate
            └── TFLite C API       # Uses NNAPI for Hexagon DSP/NPU acceleration
```

### Data Flow

1. `MediaProjection` captures screen into `ImageReader`
2. Inference thread reads `ImageReader.acquireLatestImage()` via JNI
3. `JniCallBack.detect()` runs TFLite model via NNAPI, returns detection boxes
4. `FloatService` converts normalized coords to pixels, posts to `OverlayCanvasView`
5. Aiming logic finds closest detection within `rangeRadius`, calls touch injection (TODO)

### Key Classes

- **ProjectionHolder**: Static singleton holding MediaProjection result code/data between Activity and Service
- **FloatService**: Owns all overlay views, the inference executor, and the aimbot state machine
- **GuiPanelView**: Build UI programmatically via `buildUI()` - rebuilds entire view on tab switch
- **models.json**: Dynamic model configuration (filename, displayName, precision, inputSize, outputSize)

## Model Files

Models are stored in `app/src/main/assets/` and loaded dynamically via `models.json`:

| File | Description |
|------|-------------|
| `models.json` | Model configuration (TFLite files below) |
| `yolov8n_float_192.tflite` | Float32 model (onnx2tf conversion) |
| `yolov8n_int8_192_calibrated.tflite` | INT8 model (ultralytics export with valorant.yaml calibration) |
| `yolov8n_int8_256_calibrated.tflite` | INT8 256x256 model (same calibration) |

**Note**: Model files are excluded from git (too large). Download from release or convert from .pt files.

## Model Conversion

### INT8 Quantization (Recommended)

Use ultralytics to export with INT8 quantization and calibration:

```python
from ultralytics import YOLO

model = YOLO('best_192.pt')
model.export(
    format='tflite',
    int8=True,
    data='valorant.yaml'  # Use your dataset yaml for calibration
)
```

Output: `best_192_saved_model/best_192_full_integer_quant.tflite`

Rename to `yolov8n_int8_192_calibrated.tflite` and place in `app/src/main/assets/`.

### Float32 (For testing)

If INT8 has issues, use onnx2tf for Float32 conversion:

```python
import onnx2tf
onnx2tf.convert(
    input_onnx_file_path='best_192.onnx',
    output_folder_path='output_dir',
    non_verbose=True
)
```

## Native Code (cpp/aimbot.cpp)

### TFLite with NNAPI Delegate

- Uses TFLite C API (`libtensorflowlite_jni.so`)
- NNAPI delegate provides Hexagon DSP/NPU acceleration on Qualcomm devices
- `disallow_nnapi_cpu=1` forces hardware acceleration (no CPU fallback)
- Supports INT8 and Float32 models automatically

### INT8 Preprocessing

Models are calibrated with normalized [0,1] input:
```cpp
float r = src[pixelIdx] / 255.0f;  // Normalize to [0,1]
data[idx] = (int8_t)std::round(r / input_scale + input_zero_point);
```

### Output Format

YOLOv8n output shape: `[1, 5, num_outputs]`
- Channel 0: cx (center X, normalized [0,1])
- Channel 1: cy (center Y, normalized [0,1])
- Channel 2: bw (box width, normalized [0,1])
- Channel 3: bh (box height, normalized [0,1])
- Channel 4: objectness score

Detection threshold: 0.25 (configurable in aimbot.cpp)

## Known Issues

### 1. OnePlus Pad Pro - libcdsprpc.so Not Found

**症状**:
```
dlopen failed: library "libcdsprpc.so" not found
QnnDsp <E> Transport layer setup failed: 14001
```

**原因**: `libcdsprpc.so` is in `/vendor/lib64/` (vendor system library), inaccessible to third-party apps.

**解决**: Use TFLite with NNAPI delegate instead of direct QNN API. NNAPI handles DSP communication internally.

### 2. Inference Very Slow on Some Devices

**症状**: Inference takes 50ms+ instead of 10-15ms

**原因**: NNAPI fell back to CPU execution (no DSP/NPU available)

**解决**: Check if your device supports NNAPI acceleration. Some devices may need different TFLite builds.

## Dependencies

- `org.tensorflow:tensorflow-lite` - TFLite runtime
- `com.github.topjohnwu.libsu:core` / `libsu:io` - Root shell access
- AndroidX libraries - standard Android components

## Important Notes

- minSdk=31 (Android 12), targetSdk=35
- Model files are copied from assets to internal storage on first launch
- Inference runs on single-threaded `Executors.newSingleThreadExecutor` at `THREAD_PRIORITY_URGENT_DISPLAY`
- Touch injection stub exists in FloatService.kt but is commented out (TODO)
- INT8 quantization must use calibration dataset (valorant.yaml) for proper objectness output
