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
MainActivity.kt                    # Entry point - permissions, model loading, Shizuku auth
    ↓
FloatService.kt                    # Foreground service - owns the UI layer
    ├── FloatBallView.kt           # Draggable toggle (blue MD3 FAB)
    ├── OverlayCanvasView.kt       # Full-screen transparent overlay (detection boxes)
    ├── GuiPanelView.kt           # MD3 control panel (自瞄/扳机/防闪/模型 tabs)
    ├── TouchInjector.kt          # Touch injection via Shizuku (+ IInputManager reflection)
    └── JniCallBack.kt            # JNI bridge (native libaimbot.so)
            └── aimbot.cpp         # TFLite inference with NNAPI delegate
                    └── TFLite C API
```

### Data Flow

1. `MediaProjection` captures screen into `ImageReader`
2. Inference thread reads `ImageReader.acquireLatestImage()` via JNI
3. `JniCallBack.detect()` runs TFLite model via NNAPI, returns detection boxes
4. `FloatService` converts normalized coords to pixels, posts to `OverlayCanvasView`
5. Detection overlay only — touch injection is NOT auto-triggered, must be called explicitly

### Key Classes

- **ProjectionHolder**: Static singleton holding MediaProjection result code/data + model list between Activity and Service
- **FloatService**: Owns all overlay views, the inference executor, and TouchInjector
- **GuiPanelView**: MD3 control panel with side navigation, Slider/Switch/ScrollView — rebuilt on tab switch
- **TouchInjector**: Shizuku + IInputManager reflection for touch injection, provides `tap()`, `swipe()`, `aimAt()`
- **JniCallBack**: JNI bridge to native `libaimbot.so`, also exposes `setConfidence(threshold)`
- **models.json**: Dynamic model configuration

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

Detection threshold: configurable via `JniCallBack.setConfidence(threshold)`, default 0.25

## Touch Injection API

Touch injection is handled by `TouchInjector.kt` via Shizuku + IInputManager reflection.
The ShizukuBinderWrapper proxies Binder calls to Shizuku's process (shell UID, which has `INJECT_EVENTS` permission).

### Setup

```kotlin
val injector = TouchInjector()
injector.init()  // requires Shizuku running + permission granted
```

### Methods

| Method | Description |
|--------|-------------|
| `tap(x, y)` | Tap at screen coordinates (DOWN + UP, 8ms interval) |
| `swipe(x1, y1, x2, y2, durationMs)` | Touch DOWN at (x1,y1), MOVE to (x2,y2), UP after duration |
| `aimAt(targetX, targetY, centerX, centerY, speed, screenW, screenH)` | Calculated swipe from right-side virtual joystick toward target |

### Triggering (from FloatService inference loop)

```kotlin
// tap to shoot:
touchInjector?.tap(fireButtonX, fireButtonY)

// smooth aim correction:
touchInjector?.aimAt(
    targetX = bestX, targetY = bestY,
    centerX = centerX, centerY = centerY,
    speed = currentSpeed,
    screenW = screenWidth, screenH = screenHeight
)
```

### Requirements

- Shizuku app installed and running (via wireless debugging)
- App authorized in Shizuku
- `rikka.shizuku.ShizukuProvider` declared in AndroidManifest (with `exported="true"`)

### Detection avoidance

- Events use `SOURCE_TOUCHSCREEN` (identical to real touch at framework level)
- No `/dev/uinput` virtual devices created (more detectable)
- Primary risk is MediaProjection (screen capture) and TYPE_APPLICATION_OVERLAY, not injection method

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
- `dev.rikka.shizuku:api` / `dev.rikka.shizuku:provider` - Shizuku (shell privilege via ADB)
- `com.google.android.material` - MD3 components (MaterialCardView, Slider, Switch, etc.)
- AndroidX libraries - standard Android components

## Important Notes

- minSdk=31 (Android 12), targetSdk=35
- Model files are copied from assets to internal storage on first launch
- Inference runs on single-threaded `Executors.newSingleThreadExecutor` at `THREAD_PRIORITY_URGENT_DISPLAY`
- Touch injection is NOT auto-triggered — FloatService inference loop only detects + overlays
- INT8 quantization must use calibration dataset (valorant.yaml) for proper objectness output
- Confidence threshold is configurable via `JniCallBack.setConfidence(0.10~0.90)`, default 0.25
