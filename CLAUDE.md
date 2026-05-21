# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android FPS game AI aiming assistant. Captures screen via MediaProjection, runs YOLOv8n inference through Qualcomm QNN HTP delegate (Hexagon DSP/NPU acceleration), draws detection overlays on a full-screen transparent overlay, and supports auto-aim + trigger bot via virtual touch injection through a Shizuku UserService with uinput access.

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

## Git Workflow

```bash
# 提交代码
git add <files>
git commit -m "描述"
git push origin master

# 创建 Release tag
git tag -a v1.0.2 -m "Release v1.0.2"
git push origin v1.0.2
```

### Release 流程

1. 确保所有代码已 commit 且测试通过
2. 修改 `app/build.gradle.kts` 的 `versionName` / `versionCode`
3. 更新 `dialog_changelog.xml` 添加新版本条目
4. `./gradlew assembleRelease` 构建 release APK
5. `git tag` 打 tag 并 push
6. GitHub Release 页面创建 Release，上传 APK，标题格式 `Aimbot Android v1.0.2`

### 注意事项

- 使用 `git tag -a v1.0.x -m "Release v1.0.x"` 创建 annotated tag，不要只用 lightweight tag
- 确保 tag 指向正确的 commit，push 前用 `git log v1.0.x --oneline -1` 确认
- Release APK 路径：`app/build/outputs/apk/release/app-release.apk`

## Architecture

```
MainActivity.kt                    # Entry point — permissions, model loading, Shizuku auth
    ↓
FloatService.kt                    # Foreground service — owns all UI and inference
    ├── FloatBallView.kt           # Draggable toggle button (blue FAB)
    ├── OverlayCanvasView.kt       # Full-screen transparent overlay (detection boxes, crosshair, range)
    ├── GuiPanelView.kt           # MD3 side-nav control panel (自瞄/扳机/防闪/模型/系统 tabs)
    ├── TriggerOverlayView.kt      # Trigger zone visualizer (circle overlay)
    ├── TouchDisplayView.kt        # Aim touch point visualizer
    ├── AreaSettingsView.kt        # Area configuration UI (fire/trigger/aim zones)
    ├── AreaConfig.kt              # Area data class
    ├── ShizukuInjectorClient.java # AIDL client for RemoteInjectorService
    └── JniCallBack.kt             # JNI bridge → libaimbot.so
            └── aimbot.cpp          # TFLite + QNN HTP delegate inference
RemoteInjectorService.java         # Shizuku UserService (separate process) with uinput access
    └── uinput_inject.cpp           # Native uinput touch injection + physical finger reader
TfliteClassifier.kt                # Alternative classifier (unused in current flow)
```

### Data Flow

1. `MediaProjection` captures screen into `ImageReader`
2. Inference thread calls `JniCallBack.detect()` via JNI
3. `aimbot.cpp` runs TFLite model via QNN HTP delegate, returns `[classId, score, x1, y1, x2, y2, ...]`
4. `FloatService` converts to screen pixels, posts to `OverlayCanvasView` for overlay
5. Auto-aim: PID controller moves virtual finger via `ShizukuInjectorClient` → `RemoteInjectorService` → uinput
6. Trigger bot: fires tap when crosshair is over detected target
7. Hold-to-fire: requires physical finger in trigger zone before auto-aim activates

### Key Classes

- **ProjectionHolder**: Static singleton holding MediaProjection result + model list between Activity and Service
- **FloatService**: Foreground service owning all overlay views, inference executor, and touch injection client. Contains PID auto-aim, target lock, trigger bot, hold-to-fire logic, and area settings.
- **GuiPanelView**: MD3 side-nav control panel with tabs for 自瞄(PID)/扳机(trigger)/防闪(blank-tab)/模型(model)/系统(system)
- **ShizukuInjectorClient**: AIDL client connecting to `RemoteInjectorService` via Shizuku
- **RemoteInjectorService**: Shizuku UserService with uinput device creation and physical finger detection
- **uinput_inject.cpp**: Native uinput touch injection with 90° coordinate rotation + EVIOCGRAB physical touch reader
- **JniCallBack**: JNI bridge to `libaimbot.so`, exposes `init()`, `detect()`, `setConfidence()`, `getBackend()`
- **aimbot.cpp**: TFLite with QNN HTP delegate (Hexagon DSP/NPU) — fallback to NNAPI if QNN unavailable

## Native Backend (cpp/aimbot.cpp)

### Primary: QNN HTP Delegate

- Uses `TfLiteQnnDelegateCreate` with `kHtpBackend`
- Preloads `libcdsprpc.so` / `libadsprpc.so` for FastRPC
- `cache_dir=/data/data/team.maodie.aimbot/cache/qnn` for compiled model cache

### Fallback: NNAPI Delegate

- `TfLiteNnapiDelegateCreate` with `disallow_nnapi_cpu=1`
- Used when QNN HTP is unavailable

### Output Format

YOLOv8n output shape: `[1, 5, num_outputs]`
- Channel 0: cx (center X, normalized [0,1])
- Channel 1: cy (center Y, normalized [0,1])
- Channel 2: bw (box width, normalized [0,1])
- Channel 3: bh (box height, normalized [0,1])
- Channel 4: objectness score

Detection returns `float[count * 6]` = `[classId, score, x1, y1, x2, y2, ...]`

## Touch Injection

### Flow

```
FloatService
    └── ShizukuInjectorClient (AIDL)
            └── RemoteInjectorService (Shizuku UserService, separate process)
                    └── uinput_inject.cpp (uinput device + EVIOCGRAB reader)
```

### Coordinate Mapping (OnePlus Pad Pro OPD2404)

- Screen: 3000x2120 (landscape)
- Touch panel ABS: X=[0,21199], Y=[0,29999] (portrait, 90° rotated)
- Landscape mapping:
  ```
  dev_x = (screen_h - y) * dev_abs_max_x / screen_h
  dev_y = x * dev_abs_max_y / screen_w
  ```
- Portrait mapping: direct proportional

### RemoteInjectorService Methods

| Method | Description |
|--------|-------------|
| `tap(x, y)` | Tap at screen coordinates (DOWN + UP, 8ms interval) |
| `swipe(x1, y1, x2, y2, durationMs)` | DOWN → MOVE → UP sequence |
| `moveTo(x, y)` | Continuous move (pointer must be down) |
| `lift()` | Lift pointer |
| `triggerDown/Up/Tap` | Separate trigger slot (TRACKING_ID=2000) |
| `setTriggerZone` | Set physical finger detection zone |
| `isFingerInTriggerZone` | Check if physical finger is in zone |

### Hold-to-Fire

Physical finger must be in trigger zone (savedAreas[1]) before auto-aim activates:
```kotlin
val holdToAimActive = if (aimHoldEnabled) shizukuClient?.isFingerInTriggerZone() ?: false else true
if (aimbotOn.get() && rectCount > 0 && holdToAimActive) { ... }
```

### Area Settings

Three configurable zones (via AreaSettingsView):
- `savedAreas[0]`: Fire area — random tap position within on trigger
- `savedAreas[1]`: Trigger zone — physical finger must be here for hold-to-fire
- `savedAreas[2]`: Aim area — random touch start position for auto-aim

## Model Files

Stored in `app/src/main/assets/`, loaded via `models.json`:

| File | Description |
|------|-------------|
| `models.json` | Model configuration |
| `yolov8n_float_192.tflite` | Float32 192x192 |
| `yolov8n_int8_256_calibrated.tflite` | INT8 256x256 (ultralytics export) |
| `yolov8n_int8_192_calibrated.tflite` | INT8 192x192 (ultralytics export) |

## Model Conversion

```python
from ultralytics import YOLO
model = YOLO('best_192.pt')
model.export(format='tflite', int8=True, data='valorant.yaml')
```

Output: `best_192_saved_model/best_192_full_integer_quant.tflite`

Rename to `yolov8n_int8_192_calibrated.tflite` and place in `app/src/main/assets/`.

## PID Auto-Aim

Proportional-integral-derivative controller in `FloatService`:
- **Kp** = proportional gain (default 0.30)
- **Ki** = integral gain (default 0.02), anti-windup via integral limiting
- **Kd** = derivative gain (default 0.08)
- Anti-windup: integral resets on error zero-crossing, clamped to ±100
- Max per-frame movement: 600px
- Convergence threshold: <10px error → lift pointer
- Max drag distance: 20% of screen diagonal → lift + re-down

Target lock: hysteresis by center distance <150px (22500 sq) to avoid flickering.

## Known Issues

### QNN HTP Init Failure

If QNN delegate fails to create, falls back to NNAPI. If both fail, model loading returns false.

### OnePlus Pad Pro — libcdsprpc.so Not Found

dlopen fails because `libcdsprpc.so` is in `/vendor/lib64/`. QNN HTP delegate handles this internally — if `buildQnnDelegate()` returns null, falls back to NNAPI.

### Slow Inference

NNAPI fell back to CPU. Check device NNAPI support: `adb shell dumpsys neuralnetworks`

## Dependencies

- `org.tensorflow:tensorflow-lite` — TFLite runtime
- `dev.rikka.shizuku:api` / `dev.rikka.shizuku:provider` — Shizuku
- `com.google.android.material` — MD3 components
- AndroidX libraries

## Important Notes

- minSdk=31 (Android 12), targetSdk=35
- Model files copied from assets to internal storage on first launch
- Inference at `THREAD_PRIORITY_URGENT_DISPLAY` on single thread executor
- Touch injection NOT auto-triggered — FloatService inference loop only detects + overlays
- Confidence threshold: `JniCallBack.setConfidence(0.10~0.90)`, default 0.25
- State broadcast: `ProjectionHolder.updateState(state, modelName)` (0=standby, 1=running, 2=inferencing)