# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android FPS game AI aiming assistant using Qualcomm QNN (Snapdragon Neural Processing) for real-time object detection with Hexagon DSP/NPU acceleration. The app captures the screen via MediaProjection, runs inference on a DLC model, and draws detection overlays.

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing config)
./gradlew assembleRelease

# Clean and rebuild
./gradlew clean assembleDebug

# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest
```

## Architecture

```
MainActivity.kt                    # Entry point - permissions, AI init
    ↓
FloatService.kt                    # Foreground service - owns the UI layer
    ├── FloatBallView.kt            # Draggable toggle widget (black/white circle)
    ├── OverlayCanvasView.kt        # Full-screen transparent overlay (detection boxes)
    └── GuiPanelView.kt             # Control panel (Aimbot/Triggerbot/AntiFlash tabs)
                                     ↓
JniCallBack.kt ──────────────────── # JNI bridge (native libaimbot.so)
    └── aimbot.cpp (cpp/)           # QNN DLC inference with HTP backend
            ├── libQnnHtp.so        # QNN HTP runtime
            ├── libQnnSystem.so     # QNN System API
            ├── libQnnHtpV75Stub.so  # HTP Stub (V75 version)
            ├── libQnnHtpV75Skel.so # HTP Skeleton (DSP bytecode - loaded at runtime)
            └── libcdsprpc.so        # DSP RPC library (vendor system lib)
```

### Data Flow

1. `MediaProjection` captures screen into `ImageReader`
2. Inference thread reads `ImageReader.acquireLatestImage()` via JNI
3. `JniCallBack.detect()` runs QNN DLC model on Hexagon DSP, returns detection boxes
4. `FloatService` converts normalized coords to pixels, posts to `OverlayCanvasView`
5. Aiming logic finds closest detection within `rangeRadius`, calls touch injection (TODO)

### Key Classes

- **ProjectionHolder**: Static singleton holding MediaProjection result code/data between Activity and Service
- **FloatService**: Owns all overlay views, the inference executor, and the aimbot state machine
- **GuiPanelView**: Build UI programmatically via `buildUI()` - rebuilds entire view on tab switch

## Native Code (cpp/)

### QNN API Implementation

- **aimbot.cpp**: Rewritten from ONNX Runtime to QNN API for HTP (Hexagon Tensor Processor) acceleration
- Uses QNN v2.34 API with System API v1.9 for DLC model loading
- Supports multiple HTP versions: V68, V69, V73, V75, V79, V81
- Skeleton (.skel.so) and Stub (.stub.so) libraries for each HTP version

### QNN Library Dependencies

The QNN HTP backend requires several library types:

| Library Type | Example | Purpose |
|-------------|---------|---------|
| Main Runtime | libQnnHtp.so, libQnnSystem.so | Core QNN functionality |
| HTP Stub | libQnnHtpV75Stub.so | DSP transport layer interface |
| HTP Skeleton | libQnnHtpV75Skel.so | DSP bytecode (loaded at runtime via dlopen) |
| DSP RPC | libcdsprpc.so | Vendor system library - DSP communication |

**Important**: Skeleton (.skel.so) libraries are NOT linked at build time. They are loaded at runtime via `dlopen()` from the app's files directory.

## ONNX to DLC Conversion (QNN SDK)

QNN SDK 需要 Python 3.8 来运行转换器（Python 3.11+ 不兼容 SDK 的 .pyd DLL）。

### 环境准备

假设 QNN SDK 在 `G:\qnn\v2.45.0.260326`，Python 3.8 在 `G:\Python380`。

1. 安装 Python 3.8（如果还没有）
2. 安装 pip 和依赖：
```bash
G:\Python380\python.exe -m ensurepip
G:\Python380\python.exe -m pip install numpy pyyaml onnx packaging pandas
```
3. 给 QNN SDK 的 transform_manager.py 打补丁（添加 `from __future__ import annotations`）：
```python
# 在 transform_manager.py 的 import 部分添加
from __future__ import annotations
```
或者手动在文件开头加这一行。

### 转换命令

```bash
cd G:\ai\模型\2026-0505_V1

PYTHONPATH="G:\qnn\v2.45.0.260326\qairt\2.45.0.260326\lib\python" \
G:\Python380\python.exe \
G:\qnn\v2.45.0.260326\qairt\2.45.0.260326\bin\x86_64-windows-msvc\qnn-onnx-converter \
--input_network best_192.onnx \
--output_path best_192.dlc
```

输出文件：`best_192.dlc`, `best_192.bin`

### 常见问题

- `ModuleNotFoundError: No module named 'numpy'` → 装依赖：`pip install numpy pyyaml onnx packaging pandas`
- `TypeError: unsupported operand type(s) for |: 'type' and 'ABCMeta'` → 需要 Python 3.8，或者给 SDK 的 Python 文件加 `from __future__ import annotations`
- `PermissionError` 输出路径是 `.` → 输出路径必须指定具体文件名（如 `best_192.dlc`），不能只写目录

## Known Issues

### 1. libcdsprpc.so Not Found on OnePlus Pad Pro

**症状**:
```
dlopen failed: library "libcdsprpc.so" not found: needed by .../libQnnHtpV75Stub.so
QnnDsp <E> Transport layer setup failed: 14001
```

**原因**: `libcdsprpc.so` 位于 `/vendor/lib64/` 目录下，这是供应商系统库，第三方应用无法访问。QNN HTP DSP 传输层需要这个库才能与 Hexagon DSP 通信。

**受影响设备**: OnePlus Pad Pro (Snapdragon 8 Gen 3)

**状态**: 未解决 - 需要以下之一:
- Root 设备后复制 `libcdsprpc.so` 到应用私有目录
- 使用 QNN CPU 后端（无 DSP 加速）
- 使用已获系统签名或 OEM 合作的 app

### 2. QNN HTP Backend requires Skeleton Libraries

**症状**: DSP 推理失败，错误码 14001

**解决**: Skeleton (.skel.so) 文件必须存在于 APK 中，并通过 `dlopen()` 在运行时从应用 files 目录加载。参考 `VisiAim_99999.apk` 获取完整的 QNN 库集。

### 3. Skeleton Libraries Incompatible with aarch64-linux

**症状**: 链接器错误 "incompatible with aarch64linux"

**原因**: 不要将 Skeleton 库添加到 CMake `target_link_libraries()`。它们应该在运行时加载，不是在链接时。

## Dependencies

- `onnxruntime.android` - 原 ONNX 推理（已弃用）
- `com.github.topjohnwu.libsu:core` / `libsu:io` - Root shell access
- AndroidX libraries - standard Android components

## Important Notes

- minSdk=31 (Android 12), targetSdk=35
- Model file (`.dlc`) is copied from assets to internal storage on first launch
- Inference runs on a single-threaded `Executors.newSingleThreadExecutor` at `THREAD_PRIORITY_URGENT_DISPLAY`
- Touch injection stub exists at line 278 of `FloatService.kt` but is commented out (TODO)
- libsu (Superuser) is used for root detection and shell commands
- QNN SDK 版本: v2.34.0 (QNN API v2.34, System API v1.9)
