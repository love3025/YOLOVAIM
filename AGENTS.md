# AGENTS.md

## Project

Android FPS game AI aim-assist. Single-module Gradle app (`:app`) — Kotlin/Java + C++ NDK. Captures screen via MediaProjection, runs YOLOv8/v11 inference via TFLite with Qualcomm QNN HTP delegate (Hexagon NPU), draws overlays, injects touch via Shizuku + uinput.

Package: `team.maodie.aimbot` | minSdk=31 | targetSdk=35 | compileSdk=36

## Build

```bash
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # release APK (needs app/release.jks)
./gradlew installDebug           # build + install to device
./gradlew clean assembleDebug    # full rebuild
```

Release APK: `app/build/outputs/apk/release/app-release.apk`

No CI, no lint/typecheck scripts, no pre-commit hooks. Build errors are the only check.

## Architecture

Entry: `MainActivity.kt` → permissions + model loading → starts `FloatService` (foreground service).
`FloatService` owns all overlay views, inference loop, and touch injection.

```
MainActivity.kt          — entry, permissions, Shizuku auth
FloatService.kt          — foreground service, inference loop, PID auto-aim, trigger bot
├── FloatBallView.kt     — draggable FAB toggle
├── OverlayCanvasView.kt — detection boxes overlay
├── GuiPanelView.kt      — MD3 side panel (自瞄/扳机/模型/系统 tabs)
├── AreaSettingsView.kt  — zone config UI
└── JniCallBack.kt       — JNI bridge → libaimbot.so
        └── aimbot.cpp   — TFLite + QNN HTP inference

RemoteInjectorService.java — Shizuku UserService (separate process)
└── uinput_inject.cpp      — uinput touch injection + EVIOCGRAB reader

root_daemon.cpp            — standalone binary for su-based injection (stdin/stdout protocol)
```

## Critical Details

### Native Code (CMake at `app/src/main/cpp/CMakeLists.txt`)

- Three native targets: `aimbot` (inference), `uinput_inject` (Shizuku path), `root_daemon` (su path)
- Prebuilt `.so` files in `app/src/main/cpp/lib/` — QNN SDK libs, TFLite JNI, ONNX Runtime, SNPE stubs
- `root_daemon` is compiled as `libroot_daemon.so` (executable disguised as .so for Gradle packaging)
- `aimbot.cpp` JNI methods: `init()`, `detect()`, `setConfidence()`, `getBackend()`, `release()`
- Input format auto-detected: NCHW `[1,3,H,W]` or NHWC `[1,H,W,3]`
- Output format: `[1, 4+num_classes, num_outputs]` — YOLOv8 transpose layout
- QNN cache dir: `/data/data/team.maodie.aimbot/cache/qnn`

### Touch Injection (Two Paths)

1. **Shizuku path**: `ShizukuInjectorClient.java` → AIDL → `RemoteInjectorService.java` → native uinput_inject.cpp
2. **Root path**: `RootInjectorClient.kt` → su → `root_daemon` (stdin/stdout text protocol)

Both use uinput with virtual multitouch device "AimbotTouch". Touch device ABS range auto-detected via `getevent -p`.

### Coordinate System

- Screen coords (landscape) → device ABS coords: `dev_x = (screen_h - y) * max_x / screen_h`, `dev_y = x * max_y / screen_w`
- Default device: OnePlus Pad Pro — screen 3000x2120, ABS X=[0,21199] Y=[0,29999]

### Models

Config: `app/src/main/assets/models.json`. Models copied to internal storage on first launch.
Supported: YOLOv8n, YOLOv11s, YOLOv26n/s. Single-class (head only) and multi-class (body/head/friendly/etc).

### AIDL

`app/src/main/aidl/team/maodie/aimbot/IRemoteInjector.aidl` — touch injection IPC interface.

## Conventions

- No test suite beyond placeholder `ExampleUnitTest.kt`. Don't expect tests to validate changes.
- `CLAUDE.md` exists with detailed architecture docs — read it for deeper context on PID tuning, NMS, area settings.
- Signing config is in `app/build.gradle.kts` (passwords in plaintext — `release.jks` in `app/`).
- ViewBinding enabled, no Compose.
- Mixed Kotlin/Java codebase — Java files: `ShizukuInjectorClient.java`, `RemoteInjectorService.java`, `UinputInjector.java`.
- Shizuku dependency hardcoded to `13.1.5` (not in version catalog).
- `models.json` `outputSize` field = `num_outputs` (e.g., 756 for 192 input, 1344 for 256 input).

## Gotchas

- QNN HTP only works on Qualcomm Snapdragon 8 Gen 1+. `aimbot.cpp` checks `/proc/cpuinfo` for "Qualcomm" and falls back to NNAPI.
- `libcdsprpc.so` / `libadsprpc.so` are vendor libs — may not be found via dlopen on some devices. QNN delegate handles this internally.
- `extractNativeLibs=true` in manifest — required for prebuilt .so files to be accessible at runtime.
- The `root_daemon` binary uses stdin/stdout text protocol (not AIDL). Responses are `OK`, `OK:<value>`, or `ERR:<message>`. Debug output goes to stderr only.
- `FloatService` inference runs on a single-thread executor at `THREAD_PRIORITY_URGENT_DISPLAY`.
- Touch injection is NOT auto-triggered — inference only detects + overlays. Auto-aim/trigger controlled via `aimbotOn` AtomicBoolean in FloatService.
- `OverlayCanvasView` draws on a full-screen `TYPE_APPLICATION_OVERLAY` window.
