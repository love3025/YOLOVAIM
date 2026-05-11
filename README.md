# AI Aimbot for Android

An Android FPS game AI aiming assistant using TFLite with NNAPI delegate for real-time object detection on Qualcomm Snapdragon (Hexagon DSP/NPU). The app captures the screen via MediaProjection, runs YOLOv8n inference, and draws detection overlays.

[中文](#中文说明)

---

## Features

- **Real-time Detection**: YOLOv8n inference via TFLite + NNAPI on Hexagon DSP/NPU
- **Screen Capture**: MediaProjection API with ImageReader for low-latency frame acquisition
- **Detection Overlay**: Transparent full-screen canvas showing bounding boxes
- **Touch Injection**: Shizuku-based injection for auto-aim (right-side virtual joystick mode)
- **Configurable**: Confidence threshold, aim speed, and model selection via GUI panel

## Architecture

```
MainActivity.kt                    # Entry point - permissions, model loading, Shizuku auth
    ↓
FloatService.kt                    # Foreground service - owns the UI layer
    ├── FloatBallView.kt           # Draggable toggle (blue MD3 FAB)
    ├── OverlayCanvasView.kt       # Full-screen transparent overlay (detection boxes)
    ├── GuiPanelView.kt           # MD3 control panel (自瞄/扳机/防闪/模型 tabs)
    ├── TouchInjector.kt          # Touch injection via Shizuku (+ IInputManager reflection)
    ├── UinputInjector.kt         # Direct uinput from app process (fallback)
    ├── ShizukuInjectorClient.kt  # AIDL client for RemoteInjectorService
    └── JniCallBack.kt            # JNI bridge (native libaimbot.so)
            └── aimbot.cpp         # TFLite inference with NNAPI delegate
RemoteInjectorService.java         # Shizuku UserService with uinput access (separate process)
    └── uinput_inject.cpp         # Native uinput touch injection with 90° rotation
```

### Data Flow

1. `MediaProjection` captures screen into `ImageReader`
2. Inference thread reads `ImageReader.acquireLatestImage()` via JNI
3. `JniCallBack.detect()` runs TFLite model via NNAPI, returns detection boxes
4. `FloatService` converts normalized coords to pixels, posts to `OverlayCanvasView`
5. Detection overlay only — touch injection is NOT auto-triggered, must be called explicitly

## Build

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

## Model Files

Models are stored in `app/src/main/assets/`. Model files are **excluded from git** (too large). Download from [Releases](https://github.com/xiangsu1145/Auto-aim_android-yolo/releases) or convert from .pt files.

| File | Description |
|------|-------------|
| `models.json` | Model configuration |
| `yolov8n_float_192.tflite` | Float32 model (onnx2tf conversion) |
| `yolov8n_int8_192_calibrated.tflite` | INT8 model (calibrated with valorant.yaml) |
| `yolov8n_int8_256_calibrated.tflite` | INT8 256x256 model |

### Model Conversion

**INT8 Quantization (Recommended)**:

```python
from ultralytics import YOLO

model = YOLO('best_192.pt')
model.export(
    format='tflite',
    int8=True,
    data='valorant.yaml'  # Your calibration dataset
)
```

**Float32 (For testing)**:

```python
import onnx2tf
onnx2tf.convert(
    input_onnx_file_path='best_192.onnx',
    output_folder_path='output_dir',
    non_verbose=True
)
```

## Touch Injection

### Coordinate Mapping (OnePlus Pad Pro OPD2404)

- Screen resolution: 3000x2120 (landscape)
- Touch device ABS range: X=[0,21199], Y=[0,29999] (portrait)
- **90° rotation required**: screen Y → device X, screen X → device Y

```cpp
dev_x = y * device_abs_max_x / screen_height;
dev_y = x * device_abs_max_y / screen_width;
```

### Setup Requirements

- Shizuku app installed and running (via wireless debugging)
- App authorized in Shizuku
- `rikka.shizuku.ShizukuProvider` declared in AndroidManifest (with `exported="true"`)

### API

```kotlin
val injector = TouchInjector()
injector.init()

injector.tap(x, y)                            // Tap at screen coordinates
injector.swipe(x1, y1, x2, y2, durationMs) // Swipe gesture
injector.aimAt(targetX, targetY, centerX, centerY, speed, screenW, screenH)  // Auto-aim
```

## Known Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| `dlopen failed: library "libcdsprpc.so" not found` | `libcdsprpc.so` is in `/vendor/lib64/`, inaccessible to third-party apps | Use TFLite with NNAPI delegate instead of direct QNN API |
| Inference takes 50ms+ instead of 10-15ms | NNAPI fell back to CPU execution | Check if your device supports NNAPI acceleration |

## Dependencies

- `org.tensorflow:tensorflow-lite` - TFLite runtime
- `dev.rikka.shizuku:api` / `dev.rikka.shizuku:provider` - Shizuku
- `com.google.android.material` - MD3 components
- AndroidX libraries

## Requirements

- minSdk=31 (Android 12)
- targetSdk=35
- Qualcomm Snapdragon device with NNAPI support

---

## 中文说明

Android FPS 游戏 AI 瞄准辅助工具，使用 TFLite + NNAPI 在高通骁龙（Hexagon DSP/NPU）上进行实时目标检测。应用通过 MediaProjection 截取屏幕，运行 YOLOv8n 推理并绘制检测框。

### 主要功能

- **实时检测**：通过 TFLite + NNAPI 在 Hexagon DSP/NPU 上运行 YOLOv8n 推理
- **屏幕捕获**：使用 MediaProjection + ImageReader 实现低延迟画面获取
- **检测覆盖层**：透明全屏画布显示边界框
- **触控注入**：基于 Shizuku 的虚拟摇杆式自动瞄准
- **可配置**：通过 GUI 面板调节置信度阈值、瞄准速度和模型选择

### 项目结构

主要代码位于 `app/src/main/` 目录，核心文件：

| 文件 | 说明 |
|------|------|
| `MainActivity.kt` | 入口，处理权限、模型加载、Shizuku 认证 |
| `FloatService.kt` | 前台服务，拥有所有 UI 视图和推理执行器 |
| `FloatBallView.kt` | 可拖动的悬浮球（蓝色 MD3 FAB） |
| `OverlayCanvasView.kt` | 全屏透明覆盖层（检测框） |
| `GuiPanelView.kt` | MD3 控制面板（自瞄/扳机/防闪/模型标签页） |
| `TouchInjector.kt` | 通过 Shizuku + IInputManager 反射实现触控注入 |
| `uinput_inject.cpp` | 原生 uinput 触控注入（含 90° 坐标旋转） |
| `aimbot.cpp` | TFLite 推理（NNAPI 委托） |

### 构建

```bash
./gradlew assembleDebug    # Debug 构建
./gradlew assembleRelease   # Release 构建（需要签名配置）
./gradlew installDebug      # 安装到设备
```

### 模型文件

模型文件存储在 `app/src/main/assets/`，由于体积过大已从 Git 排除。请从 [Releases](https://github.com/xiangsu1145/Auto-aim_android-yolo/releases) 下载或自行转换。

推荐使用 INT8 量化模型，推理速度更快。

### 触控注入说明

触控注入**不会自动触发**——FloatService 推理循环仅负责检测和显示。如需自动瞄准，需在代码中调用 `touchInjector?.aimAt()` 等方法。

### 设备兼容

- 需要高通骁龙设备（Snapdragon 865+）
- 需要 NNAPI 硬件加速支持
- 测试设备：OnePlus Pad Pro (OPD2404)

---

## License

MIT License - See [LICENSE](LICENSE)