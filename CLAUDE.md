# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Generic Android screen-object-detection + touch-automation framework. Captures screen via
MediaProjection, runs YOLO inference (NCNN or TFLite via QNN HTP delegate), draws detection
overlays on a full-screen transparent overlay, and supports auto-aim + trigger bot via virtual
touch injection through Shizuku or Root (uinput).

**No models are bundled and the app targets no specific application.** All detection models are
imported by the user at runtime via SAF; class semantics are user-supplied. See `ModelRepository`.

Fork note: the upstream project gated `MainActivity` behind a license-code check implemented in a
closed-source `libaimbot_license.so`. That gate, `LoginActivity`, `LicenseManager`, the `.so`, and
the now-unused `INTERNET` permission were all removed in this fork. The QNN prewarm pass that used
to piggyback on the login screen now lives in `MainActivity.startPrewarmInBackground()`.

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
git tag -a v1.0.x -m "Release v1.0.x"
git push origin v1.0.x
```

### Release 流程

1. 确保所有代码已 commit 且测试通过
2. 修改 `app/build.gradle.kts` 的 `versionName` / `versionCode`
3. 更新 `dialog_changelog.xml` 添加新版本条目
4. `./gradlew assembleRelease` 构建 release APK
5. `git tag` 打 tag 并 push
6. GitHub Release 页面创建 Release，上传 APK，标题格式 `Aimbot Android v1.0.x`

### 注意事项

- 使用 `git tag -a v1.0.x -m "Release v1.0.x"` 创建 annotated tag，不要只用 lightweight tag
- 确保 tag 指向正确的 commit，push 前用 `git log v1.0.x --oneline -1` 确认
- Release APK 路径：`app/build/outputs/apk/release/app-release.apk`

## Architecture

### Kotlin/Java 层

```
ui/
├── MainActivity.kt              # Entry point (LAUNCHER) — permissions, model import/selection/edit,
│                                #   disclaimer, config import/export, QNN prewarm
└── SettingsActivity.kt          # CPU inference toggle, thread count slider

service/
├── FloatService.kt              # Foreground service — owns overlays, MediaProjection, inference loop
└── RemoteInjectorService.java   # Shizuku UserService (separate process) — uinput or InputManager

controller/
├── AimController.kt             # PID + Bezier aim modes, target lock, per-class offsets
└── TriggerController.kt         # Trigger bot, reaction delay, auto-stop joystick

manager/
├── ConfigManager.kt             # JSON config persistence (config.json), export/import
├── ModelRepository.kt           # User-imported model registry — SAF import, tensor-shape probe,
│                                #   metadata edit, delete. Files in filesDir/models/,
│                                #   metadata in filesDir/models/registry.json
├── InferenceManager.kt          # Inference loop, ImageReader, VirtualDisplay, dataset saving, recording
└── OverlayManager.kt            # Touch display and area overlay lifecycle

model/
├── DetectionInfo.kt             # rect, classId, className
├── AreaConfig.kt                # x, y, width, height, name, color
├── AimingState.kt               # PID state: pointerDown, position, errors, integral, lockedTarget
└── BezierMover.kt               # Smoothstep easing timer

view/
├── FloatBallView.kt             # Draggable FAB toggle
├── OverlayCanvasView.kt         # Full-screen overlay (detection boxes, crosshair, range)
├── GuiPanelView.kt              # MD3 side-nav control panel (自瞄/扳机/防闪/模型/系统 tabs)
├── TriggerOverlayView.kt        # Trigger zone visualizer
├── TouchDisplayView.kt          # Aim touch point visualizer
├── AreaSettingsView.kt          # Area configuration (fire/trigger/aim/joystick zones)
└── BezierCurveView.kt           # Bezier curve preview

injector/
├── TouchInjectorInterface.kt    # Interface: tap, swipe, moveTo, lift, trigger, zone queries
├── ShizukuInjectorClient.java   # AIDL client for RemoteInjectorService
├── RootInjectorClient.kt        # Root alternative via su + stdin/stdout protocol
└── UinputInjector.java          # Legacy standalone injector (superseded)

inference/
├── JniCallBack.kt               # JNI bridge → libaimbot.so
└── TfliteClassifier.kt          # Alternative pure-Java TFLite classifier (unused)

util/
└── ProjectionHolder.kt          # Singleton: MediaProjection, model list, state, callback listeners
```

### C++ Native 层 (app/src/main/cpp/)

```
src/inference/
├── inference_engine.h           # Abstract base: init, detect, release, setConfidence, setInputSize
├── common.h                     # Detection struct, NMS, sigmoid, timing
├── aimbot.cpp                   # JNI bridge — creates NcnnEngine or LiteRtEngine by model extension
├── ncnn_engine.h/cpp            # NCNN inference (YOLOv8 DFL + legacy format, float32 + int8)
├── litert_engine.h/cpp          # TFLite inference (QNN HTP → GPU → CPU fallback chain)
├── qnn_engine.h/cpp             # QNN HTP delegate builder (Qualcomm detection, FastRPC preload)
└── qnn_wrapper.h/cpp            # Direct QNN API wrapper (experimental)

src/injection/
├── touch_core.h/cpp             # Shared touch logic: uinput device, EVIOCGRAB, zone detection, coord mapping
└── uinput_inject.cpp            # JNI wrapper over touch_core

src/daemon/
└── root_daemon.cpp              # Standalone su daemon, stdin/stdout command protocol (20+ commands)

CMakeLists.txt                   # 3 targets: aimbot (shared), uinput_inject (shared), root_daemon (exec)
                                 # + touch_core (static) shared between injection targets
                                 # Links NCNN with Vulkan + OpenMP
```

### Data Flow

1. `MediaProjection` captures screen into `ImageReader`
2. `InferenceManager` calls `JniCallBack.detect()` via JNI
3. `aimbot.cpp` selects engine by model extension (`.param` → NCNN, `.tflite` → LiteRt/QNN)
4. Returns `[classId, score, x1, y1, x2, y2, ...]`
5. `FloatService` converts to screen pixels, posts to `OverlayCanvasView` for overlay
6. `AimController`: PID or Bezier moves virtual finger via injector client → uinput
7. `TriggerController`: fires tap when crosshair enters detection box
8. Hold-to-fire: physical finger must be in trigger zone before auto-aim activates

### Touch Injection (Dual Path)

```
FloatService
    ├── RootInjectorClient (su + stdin/stdout)     ← preferred
    │       └── root_daemon.cpp → touch_core.cpp
    └── ShizukuInjectorClient (AIDL)               ← fallback
            └── RemoteInjectorService.java → uinput_inject.cpp → touch_core.cpp
```

`touch_core.cpp` is the shared native library used by both paths. Creates uinput device, EVIOCGRAB on real touch devices, zone detection, coordinate mapping with 90° rotation.

### AIDL (IRemoteInjector.aidl)

28 methods covering: tap/swipe/move/lift, trigger operations (triggerDown/triggerUp/triggerTap), zone configuration (setTriggerZone/setFireZone/setJoystickZone), physical finger detection (isFingerInTriggerZone/isFingerInFireZone/isFingerInJoystickZone), device management, lifecycle.

### Area Settings

Four configurable zones (via AreaSettingsView):
- `savedAreas[0]`: Fire area — random tap position within on trigger
- `savedAreas[1]`: Trigger zone — physical finger must be here for hold-to-fire
- `savedAreas[2]`: Aim area — random touch start position for auto-aim
- `savedAreas[3]`: Joystick zone — auto-stop lifts joystick finger before firing

## Inference Backends

### NCNN (primary for `.param`/`.bin` models)

- `NcnnEngine` in `ncnn_engine.cpp`
- Supports official YOLOv8 format (2D output, DFL decode) and legacy 3-output format
- Both float32 and int8 (quantized) outputs
- Vulkan GPU acceleration + OpenMP threading
- Static link: `libncnn.a` + Vulkan SPIR-V libs

### LiteRt / TFLite (for `.tflite` models)

- `LiteRtEngine` in `litert_engine.cpp`
- Delegate chain: QNN HTP → GPU → CPU fallback
- QNN HTP: `TfLiteQnnDelegateCreate` with `kHtpBackend`, preloads `libcdsprpc.so`
- GPU: `TfLiteGpuDelegateV2Create`
- CPU: default TFLite (final fallback)
- `cache_dir=/data/data/team.maodie.aimbot/cache/qnn`

### Output Format

All engines return `float[count * 6]` = `[classId, score, x1, y1, x2, y2, ...]`

YOLOv8 output shape: `[1, 5, num_outputs]` — cx, cy, bw, bh, objectness (all normalized [0,1])

## Aim Modes

### PID Controller (`AimController`)

- **Kp** = 0.30, **Ki** = 0.02, **Kd** = 0.08
- Anti-windup: integral resets on error zero-crossing, clamped to ±100
- Max per-frame movement: 1200px
- Convergence threshold: <10px → lift pointer
- Max drag distance: 20% screen diagonal → lift + re-down
- Target lock: hysteresis by center distance <150px

### Bezier Curve (`AimController` + `BezierMover`)

- Smoothstep easing (slow-fast-slow)
- Configurable duration, control offset, random spread
- Alternative to PID for smoother aim movement

### Both Modes Support

- Per-class Y offsets and box aim ratios
- Priority class target selection
- Sway simulation
- Aim area for random touch start position

## Trigger Bot (`TriggerController`)

- Two-phase: first shot uses reaction speed delay, subsequent shots use cooldown
- Per-class trigger Y offsets
- Auto-stop: lifts joystick finger before firing (joystick zone)
- Fire area for random tap position

## Config System

`ConfigManager.kt` persists `AppConfig` (40+ settings) to `config.json` in app filesDir:
- Aim settings (Kp/Ki/Kd, Bezier params, target lock, sway)
- Trigger settings (reaction speed, cooldown, per-class offsets)
- Per-class configuration (aim/trigger enable, offsets, box aim ratio)
- Area settings (fire/trigger/aim/joystick zones)
- CPU inference settings (force CPU, thread count)
- Display options (overlay, crosshair)
- Export/import via content URI

## Dataset & Recording

Built into `InferenceManager`:
- **Dataset auto-save**: JPEG screenshots + YOLO format labels (`class cx cy w h`)
- **Screen recording**: HEVC MP4 at 32Mbps/60fps via MediaCodec

## Model Files

**Nothing ships in `app/src/main/assets/`.** Models are user-imported at runtime.

`ModelRepository` owns the lifecycle:

- **Storage**: model files in `filesDir/models/`, metadata in `filesDir/models/registry.json`
- **Import**: `ActivityResultContracts.OpenMultipleDocuments` (mime `*/*` — `.tflite`/`.param`/`.bin`
  have no registered MIME type). Multi-select exists so ncnn users can grab `.param` + `.bin`
  together; a `.bin` is copied but never registered as its own entry.
- **Probe**: `probeTflite()` opens the file with the TFLite Java `Interpreter` and reads tensor
  shapes. Value derivation deliberately mirrors `LiteRtEngine::init()` in `litert_engine.cpp:136-173`
  so the UI shows the numbers the engine will actually use:
  - `inputSize` = input `shape[1]` (falls back to `shape[2]` when `shape[1] == 3`, i.e. NCHW —
    native takes `dim1` unconditionally there, which is a native-side bug this does not copy)
  - `precision` = input tensor dtype (UINT8/INT8 -> `INT8`), i.e. what the engine feeds
  - `outputSize` = output last dim; `numClasses` = output `shape[1] - 4`
  - `probeNcnnParam()` parses the `Input` layer's `0=W 1=H` from the `.param` text; ncnn params
    carry no class count, so classes default to a single `class_0` for the user to edit
- **Failure mode**: a failed probe never blocks import — it yields `UNKNOWN`/0 and the user fills
  the metadata in by hand.
- **Class names**: default `class_0`, `class_1`, ... Semantics are user-supplied by design; the app
  makes no assumption about what a model detects.

`ProjectionHolder.ModelEntry` carries an absolute `path`, so `FloatService` / `InferenceManager`
resolve models directly with no assets fallback. A missing file aborts the model switch and restarts
the infer loop rather than throwing.

## Model Conversion

```python
from ultralytics import YOLO
model = YOLO('best.pt')
model.export(format='tflite', int8=True, data='your_dataset.yaml')
```

Output: `best_saved_model/best_full_integer_quant.tflite` — import it in-app.

Expected output tensor: `[1, 4+nc, num_anchors]`. Exports that bake in NMS, or non-YOLO heads
(SSD/EfficientDet), are not supported by the postprocess in `litert_engine.cpp`.

## Dependencies

- `org.tensorflow:tensorflow-lite:2.14.0` — TFLite runtime
- `com.microsoft.onnxruntime:onnxruntime-android:1.17.1` — ONNX Runtime (unused in current flow)
- `dev.rikka.shizuku:api:13.1.5` / `dev.rikka.shizuku:provider:13.1.5` — Shizuku
- `com.google.android.material:material:1.13.0` — MD3 components
- AndroidX libraries
- NCNN (static lib in `app/src/main/cpp/ncnn/`)
- QNN SDK (prebuilt in `app/src/main/cpp/lib/`)

## Build Config

- compileSdk=36, minSdk=31, targetSdk=35
- NDK: arm64-v8a only
- CMake 3.22.1
- ViewBinding + AIDL enabled, no Compose
- AGP 8.10.0, Kotlin 2.0.21

## Important Notes

- Models are user-imported into `filesDir/models/`; nothing is extracted from assets
- Inference at `THREAD_PRIORITY_URGENT_DISPLAY` on single thread executor
- Confidence threshold: `JniCallBack.setConfidence(0.10~0.90)`, default 0.25
- `ProjectionHolder` uses callback listeners (not broadcasts) for Android 14+ compatibility
- Disclaimer dialog: 30-second countdown with scroll-to-bottom before accepting
- Root injector preferred, Shizuku as fallback — `FloatService` tries Root first

## Known Issues

### QNN HTP Init Failure

If QNN delegate fails to create, falls back to GPU, then CPU. Check `getBackend()` for current backend.

### Slow Inference

Likely CPU fallback. Check: `adb shell dumpsys neuralnetworks` for NNAPI support, or verify QNN libs are present.

### libcdsprpc.so Not Found

`/vendor/lib64/` path — QNN HTP delegate handles this internally. If `buildQnnDelegate()` returns null, falls back.
