/*
 * ANativeWindowCreator.h — create a SurfaceFlinger layer directly through the
 * private libgui SurfaceComposerClient interface, resolved at runtime with
 * dlopen/dlsym. No NDK-public API or JNI involved.
 *
 * Adapted from the "雪花内核" project (jni/include/native_surface/ANativeWindowCreator.h).
 * Differences from the original, all deliberate:
 *
 *   1. We NEVER dlclose libgui/libutils. The original runs inside an app process
 *      where libgui is already a dependency and stays loaded. root_daemon is a
 *      standalone executable that would otherwise hold the only reference —
 *      dlclose would unmap the library and leave every resolved function
 *      pointer dangling.
 *   2. Critical failures are logged to stderr as well as logcat. The daemon's
 *      stdout is the IPC channel with the app and must stay clean, but stderr
 *      survives and logcat is readable by root, so a silently-missing symbol
 *      (the known failure mode of this technique) becomes diagnosable.
 *   3. SymbolsReady() reports whether every symbol needed for the current
 *      Android version path actually resolved, so callers can tell "mechanism
 *      unsupported on this ROM" apart from "layer creation failed".
 *   4. CreateSurface() takes a z-order parameter and always applies it (plus
 *      the trusted-overlay flag on 12+). The original relied on the default
 *      layer z of 0, which lands below app windows.
 *
 * Anti-capture mechanism, per Android version:
 *   9        -> windowType 441731 passed as a createSurface argument
 *   10 / 11  -> LayerMetadata.setInt32(2, 441731)   (key 2 = METADATA_WINDOW_TYPE)
 *   12+      -> createSurface flags |= 0x40 (eSkipScreenshot)
 *               + Transaction::setTrustedOverlay(true) + a faked-but-null
 *               parent binder handle
 * On mismatched ROMs the flag silently fails to apply — callers must verify
 * empirically (see the PoC in hud_poc.cpp) and be prepared to fall back.
 */

#ifndef A_NATIVE_WINDOW_CREATOR_H // !A_NATIVE_WINDOW_CREATOR_H
#define A_NATIVE_WINDOW_CREATOR_H

#include <android/native_window.h>
#include <android/log.h>
#include <dlfcn.h>
#include <sys/system_properties.h>

#include <cstddef>
#include <cstdio>
#include <unordered_map>
#include <string>
#include <vector>

#define ResolveMethod(ClassName, MethodName, Handle, MethodSignature)                                                                    \
    ClassName##__##MethodName = reinterpret_cast<decltype(ClassName##__##MethodName)>(symbolMethod.Find(Handle, MethodSignature));       \
    if (nullptr == ClassName##__##MethodName)                                                                                            \
    {                                                                                                                                    \
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "[-] Method not found: %s -> %s::%s", MethodSignature, #ClassName, #MethodName); \
        fprintf(stderr, "[%s] method not found: %s (%s::%s)\n", LOG_TAG, MethodSignature, #ClassName, #MethodName);                       \
    }

static const char* const LOG_TAG = "YolovaimHUD";

// 多候选符号解析:同一函数在不同 OEM ROM 上 mangled 形态不同(命名空间迁移、
// const 成员差异等),按参数顺序试,第一个命中的生效。全部失败才告警。
#define ResolveMethodFirstOf(ClassName, MethodName, Handle, ...)                                          \
    do {                                                                                                  \
        static const char* const _candidates[] = {__VA_ARGS__};                                           \
        for (const char* _sig : _candidates) {                                                            \
            ClassName##__##MethodName = reinterpret_cast<decltype(ClassName##__##MethodName)>(            \
                symbolMethod.Find(Handle, _sig));                                                         \
            if (nullptr != ClassName##__##MethodName)                                                     \
                break;                                                                                    \
        }                                                                                                 \
        if (nullptr == ClassName##__##MethodName)                                                         \
        {                                                                                                 \
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "[-] Method not found (all %zu candidates) -> %s::%s", \
                                sizeof(_candidates) / sizeof(_candidates[0]), #ClassName, #MethodName);   \
            fprintf(stderr, "[%s] method not found (all candidates) (%s::%s)\n", LOG_TAG, #ClassName, #MethodName); \
        }                                                                                                 \
    } while (0)

namespace android {
    namespace detail {
        namespace ui {
            // A LayerStack identifies a Z-ordered group of layers. A layer can only be associated to a single
            // LayerStack, but a LayerStack can be associated to multiple displays, mirroring the same content.
            struct LayerStack
            {
                uint32_t id = UINT32_MAX;
            };

            enum class Rotation
            {
                Rotation0 = 0,
                Rotation90 = 1,
                Rotation180 = 2,
                Rotation270 = 3
            };

            // A simple value type representing a two-dimensional size.
            struct Size
            {
                int32_t width = -1;
                int32_t height = -1;
            };

            // Transactional state of physical or virtual display. Note that libgui defines
            // android::DisplayState as a superset of android::ui::DisplayState.
            struct DisplayState
            {
                LayerStack layerStack;
                Rotation orientation = Rotation::Rotation0;
                Size layerStackSpaceRect;
            };

            typedef int64_t nsecs_t; // nano-seconds
            struct DisplayInfo
            {
                uint32_t w{0};
                uint32_t h{0};
                float xdpi{0};
                float ydpi{0};
                float fps{0};
                float density{0};
                uint8_t orientation{0};
                bool secure{false};
                nsecs_t appVsyncOffset{0};
                nsecs_t presentationDeadline{0};
                uint32_t viewportW{0};
                uint32_t viewportH{0};
            };

            enum class DisplayType
            {
                DisplayIdMain = 0,
                DisplayIdHdmi = 1
            };

            struct PhysicalDisplayId
            {
                uint64_t value;
            };
        }

        struct String8;

        struct LayerMetadata;

        struct Surface;

        struct SurfaceControl;

        struct SurfaceComposerClientTransaction;

        struct SurfaceComposerClient;

        template <typename any_t>
        struct StrongPointer
        {
            union
            {
                any_t *pointer;
                char padding[sizeof(std::max_align_t)];
            };

            inline any_t *operator->() const { return pointer; }
            inline any_t *get() const { return pointer; }
            inline explicit operator bool() const { return nullptr != pointer; }
        };

        struct Functionals
        {
            struct SymbolMethod
            {
                void *(*Open)(const char *filename, int flag) = nullptr;
                void *(*Find)(void *handle, const char *symbol) = nullptr;
                int (*Close)(void *handle) = nullptr;
            };

            size_t systemVersion = 13;

            void (*RefBase__IncStrong)(void *thiz, void *id) = nullptr;
            void (*RefBase__DecStrong)(void *thiz, void *id) = nullptr;

            void (*String8__Constructor)(void *thiz, const char *const data) = nullptr;
            void (*String8__Destructor)(void *thiz) = nullptr;

            void (*LayerMetadata__Constructor)(void *thiz) = nullptr;
            void (*LayerMetadata__setInt32)(void *thiz, uint32_t key, int32_t value) = nullptr;

            void (*SurfaceComposerClient__Constructor)(void *thiz) = nullptr;
            void (*SurfaceComposerClient__Destructor)(void *thiz) = nullptr;
            StrongPointer<void> (*SurfaceComposerClient__CreateSurface)(void *thiz, void *name, uint32_t w, uint32_t h, int32_t format, uint32_t flags, void *parentHandle, void *layerMetadata, uint32_t *outTransformHint) = nullptr;
            StrongPointer<void> (*SurfaceComposerClient__CreateSurface_and9)(void *thiz, void *name, uint32_t w, uint32_t h, int32_t format, uint32_t flags, void *parentHandle, int32_t windowType, int32_t ownerUid) = nullptr;
            StrongPointer<void> (*SurfaceComposerClient__GetInternalDisplayToken)() = nullptr;
            StrongPointer<void> (*SurfaceComposerClient__GetBuiltInDisplay)(ui::DisplayType type) = nullptr;
            int32_t (*SurfaceComposerClient__GetDisplayState)(StrongPointer<void> &display, ui::DisplayState *displayState) = nullptr;
            int32_t (*SurfaceComposerClient__GetDisplayInfo)(StrongPointer<void> &display, ui::DisplayInfo *displayInfo) = nullptr;
            std::vector<ui::PhysicalDisplayId> (*SurfaceComposerClient__GetPhysicalDisplayIds)() = nullptr;
            StrongPointer<void> (*SurfaceComposerClient__GetPhysicalDisplayToken)(ui::PhysicalDisplayId displayId) = nullptr;

            void (*SurfaceComposerClient__Transaction__Constructor)(void *thiz) = nullptr;
            void *(*SurfaceComposerClient__Transaction__SetLayer)(void *thiz, StrongPointer<void> &surfaceControl, int32_t z) = nullptr;
            void *(*SurfaceComposerClient__Transaction__SetPosition)(void *thiz, StrongPointer<void> &surfaceControl, float x, float y) = nullptr;
            void *(*SurfaceComposerClient__Transaction__SetTrustedOverlay)(void *thiz, StrongPointer<void> &surfaceControl, bool isTrustedOverlay) = nullptr;
            int32_t (*SurfaceComposerClient__Transaction__Apply)(void *thiz, bool synchronous, bool oneWay) = nullptr;

            int32_t (*SurfaceControl__Validate)(void *thiz) = nullptr;
            StrongPointer<Surface> (*SurfaceControl__GetSurface)(void *thiz) = nullptr;
            void (*SurfaceControl__DisConnect)(void *thiz) = nullptr;

            Functionals(const SymbolMethod &symbolMethod)
            {
                std::string systemVersionString(128, 0);

                systemVersionString.resize(__system_property_get("ro.build.version.release", systemVersionString.data()));
                if (!systemVersionString.empty())
                    systemVersion = std::stoi(systemVersionString);

                if (9 > systemVersion)
                {
                    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "[-] Unsupported system version: %zu", systemVersion);
                    fprintf(stderr, "[%s] unsupported system version: %zu\n", LOG_TAG, systemVersion);
                    return;
                }

                static std::unordered_map<size_t, std::unordered_map<void **, const char *>> patchesTable = {
                    {
                        14,
                        {
                            {reinterpret_cast<void **>(&LayerMetadata__Constructor), "_ZN7android3gui13LayerMetadataC2Ev"},
                            {reinterpret_cast<void **>(&SurfaceComposerClient__CreateSurface), "_ZN7android21SurfaceComposerClient13createSurfaceERKNS_7String8EjjiiRKNS_2spINS_7IBinderEEENS_3gui13LayerMetadataEPj"},
                        },
                    },
                    {
                        12,
                        {
                            {reinterpret_cast<void **>(&SurfaceComposerClient__Transaction__Apply), "_ZN7android21SurfaceComposerClient11Transaction5applyEb"},
                        },
                    },
                    {
                        11,
                        {
                            {reinterpret_cast<void **>(&SurfaceComposerClient__CreateSurface), "_ZN7android21SurfaceComposerClient13createSurfaceERKNS_7String8EjjijPNS_14SurfaceControlENS_13LayerMetadataEPj"},
                            {reinterpret_cast<void **>(&SurfaceControl__GetSurface), "_ZNK7android14SurfaceControl10getSurfaceEv"},
                        },
                    },
                    {
                        10,
                        {
                            {reinterpret_cast<void **>(&SurfaceComposerClient__CreateSurface), "_ZN7android21SurfaceComposerClient13createSurfaceERKNS_7String8EjjijPNS_14SurfaceControlENS_13LayerMetadataE"},
                            {reinterpret_cast<void **>(&SurfaceControl__GetSurface), "_ZNK7android14SurfaceControl10getSurfaceEv"},
                        },
                    },
                    {
                        9,
                        {
                            {reinterpret_cast<void **>(&SurfaceComposerClient__CreateSurface_and9), "_ZN7android21SurfaceComposerClient13createSurfaceERKNS_7String8EjjijPNS_14SurfaceControlEii"},
                            {reinterpret_cast<void **>(&SurfaceComposerClient__GetBuiltInDisplay), "_ZN7android21SurfaceComposerClient17getBuiltInDisplayEi"},
                            {reinterpret_cast<void **>(&SurfaceControl__GetSurface), "_ZNK7android14SurfaceControl10getSurfaceEv"},
                        },
                    },
                };

#ifdef __LP64__
                auto libgui = symbolMethod.Open("/system/lib64/libgui.so", RTLD_LAZY);
                auto libutils = symbolMethod.Open("/system/lib64/libutils.so", RTLD_LAZY);
#else
                auto libgui = symbolMethod.Open("/system/lib/libgui.so", RTLD_LAZY);
                auto libutils = symbolMethod.Open("/system/lib/libutils.so", RTLD_LAZY);
#endif

                if (nullptr == libgui || nullptr == libutils)
                {
                    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "[-] dlopen failed: libgui=%p libutils=%p", libgui, libutils);
                    fprintf(stderr, "[%s] dlopen failed: libgui=%p libutils=%p\n", LOG_TAG, libgui, libutils);
                    return;
                }

                ResolveMethod(RefBase, IncStrong, libutils, "_ZNK7android7RefBase9incStrongEPKv");
                ResolveMethod(RefBase, DecStrong, libutils, "_ZNK7android7RefBase9decStrongEPKv");

                ResolveMethod(String8, Constructor, libutils, "_ZN7android7String8C2EPKc");
                ResolveMethod(String8, Destructor, libutils, "_ZN7android7String8D2Ev");

                ResolveMethod(LayerMetadata, Constructor, libgui, "_ZN7android13LayerMetadataC2Ev");
                ResolveMethod(LayerMetadata, setInt32, libgui, "_ZN7android13LayerMetadata8setInt32Eji");


                ResolveMethod(SurfaceComposerClient, Constructor, libgui, "_ZN7android21SurfaceComposerClientC2Ev");
                // createSurface 的 LayerMetadata 参数在不同 A13 ROM 上有两派 mangled 形态:
                // 原生 AOSP:A12 起迁入 android::gui::;部分 OEM(如 realme A13)仍是 android::。
                // 按候选串顺序试,命中即用——比按版本号猜可靠(OEM 分支不跟版本号对齐)。
                ResolveMethodFirstOf(SurfaceComposerClient, CreateSurface, libgui,
                    "_ZN7android21SurfaceComposerClient13createSurfaceERKNS_7String8EjjijRKNS_2spINS_7IBinderEEENS_3gui13LayerMetadataEPj",
                    "_ZN7android21SurfaceComposerClient13createSurfaceERKNS_7String8EjjijRKNS_2spINS_7IBinderEEENS_13LayerMetadataEPj");
                // getSurface 同理:const/非 const 两派(_ZNK7android... vs _ZN7android...)。
                ResolveMethodFirstOf(SurfaceControl, GetSurface, libgui,
                    "_ZNK7android14SurfaceControl10getSurfaceEv",
                    "_ZN7android14SurfaceControl10getSurfaceEv");
                ResolveMethod(SurfaceComposerClient, GetInternalDisplayToken, libgui, "_ZN7android21SurfaceComposerClient23getInternalDisplayTokenEv");  //小于或者等于安卓13
                ResolveMethod(SurfaceComposerClient, GetDisplayState, libgui, "_ZN7android21SurfaceComposerClient15getDisplayStateERKNS_2spINS_7IBinderEEEPNS_2ui12DisplayStateE");
                ResolveMethod(SurfaceComposerClient, GetDisplayInfo, libgui, "_ZN7android21SurfaceComposerClient14getDisplayInfoERKNS_2spINS_7IBinderEEEPNS_11DisplayInfoE"); //安卓10及以下
                ResolveMethod(SurfaceComposerClient, GetPhysicalDisplayIds, libgui, "_ZN7android21SurfaceComposerClient21getPhysicalDisplayIdsEv");
                ResolveMethod(SurfaceComposerClient, GetPhysicalDisplayToken, libgui, "_ZN7android21SurfaceComposerClient23getPhysicalDisplayTokenENS_17PhysicalDisplayIdE");

                ResolveMethod(SurfaceComposerClient__Transaction, Constructor, libgui, "_ZN7android21SurfaceComposerClient11TransactionC2Ev");
                ResolveMethod(SurfaceComposerClient__Transaction, SetLayer, libgui, "_ZN7android21SurfaceComposerClient11Transaction8setLayerERKNS_2spINS_14SurfaceControlEEEi");
                ResolveMethod(SurfaceComposerClient__Transaction, SetPosition, libgui, "_ZN7android21SurfaceComposerClient11Transaction11setPositionERKNS_2spINS_14SurfaceControlEEEff");
                ResolveMethod(SurfaceComposerClient__Transaction, SetTrustedOverlay, libgui, "_ZN7android21SurfaceComposerClient11Transaction17setTrustedOverlayERKNS_2spINS_14SurfaceControlEEEb");
                ResolveMethod(SurfaceComposerClient__Transaction, Apply, libgui, "_ZN7android21SurfaceComposerClient11Transaction5applyEbb");

                ResolveMethod(SurfaceControl, Validate, libgui, "_ZNK7android14SurfaceControl8validateEv");
                ResolveMethod(SurfaceControl, DisConnect, libgui, "_ZN7android14SurfaceControl10disconnectEv");

                auto it = patchesTable.find(systemVersion);
                if (it != patchesTable.end()) {
                    for (const auto &[patchTo, signature] : patchesTable.at(systemVersion))
                    {
                        *patchTo = symbolMethod.Find(libgui, signature);
                        if (nullptr != *patchTo)
                            continue;

                        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "[-] Patch method not found: %s", signature);
                        fprintf(stderr, "[%s] patch method not found: %s\n", LOG_TAG, signature);
                    }
                }

                // NOTE: deliberately no symbolMethod.Close() here — see the header comment.
                // Leaking the two dlopen handles keeps libgui/libutils mapped for the
                // lifetime of the process; every function pointer above points into them.
            }

            // True when every symbol the current version path depends on resolved and
            // the version is supported. If this returns false the anti-capture layer
            // cannot be created on this ROM and the caller must fall back.
            bool Ready() const
            {
                if (systemVersion < 9)
                    return false;
                if (nullptr == RefBase__IncStrong || nullptr == RefBase__DecStrong ||
                    nullptr == String8__Constructor || nullptr == String8__Destructor ||
                    nullptr == SurfaceComposerClient__Constructor ||
                    nullptr == SurfaceComposerClient__Transaction__Constructor ||
                    nullptr == SurfaceComposerClient__Transaction__SetLayer ||
                    nullptr == SurfaceComposerClient__Transaction__SetPosition ||
                    nullptr == SurfaceComposerClient__Transaction__Apply ||
                    nullptr == SurfaceControl__GetSurface || nullptr == SurfaceControl__DisConnect)
                    return false;
                if (9 == systemVersion)
                    return nullptr != SurfaceComposerClient__CreateSurface_and9;
                if (12 <= systemVersion)
                    return nullptr != SurfaceComposerClient__CreateSurface &&
                           nullptr != SurfaceComposerClient__Transaction__SetTrustedOverlay;
                // 10 / 11: LayerMetadata path
                return nullptr != SurfaceComposerClient__CreateSurface &&
                       nullptr != LayerMetadata__Constructor &&
                       nullptr != LayerMetadata__setInt32;
            }

            static const Functionals &GetInstance(const SymbolMethod &symbolMethod = {.Open = dlopen, .Find = dlsym, .Close = dlclose}) {
                static Functionals functionals(symbolMethod);
                return functionals;
            }
        };

        struct String8
        {
            char data[1024];

            String8(const char *const string)
            {
                Functionals::GetInstance().String8__Constructor(data, string);
            }

            ~String8()
            {
                Functionals::GetInstance().String8__Destructor(data);
            }

            operator void *()
            {
                return reinterpret_cast<void *>(data);
            }
        };

        struct LayerMetadata {
            char data[1024];

            LayerMetadata() {
                if (9 < Functionals::GetInstance().systemVersion) {
                    Functionals::GetInstance().LayerMetadata__Constructor(data);
                }
            }

            void setInt32(uint32_t key, int32_t value) {
                Functionals::GetInstance().LayerMetadata__setInt32(data, key, value);
            }

            operator void *() {
                if (9 < Functionals::GetInstance().systemVersion)
                    return reinterpret_cast<void *>(data);
                else
                    return nullptr;
            }
        };

        struct Surface {
        };

        struct SurfaceControl {
            void *data;

            SurfaceControl() : data(nullptr) {}
            SurfaceControl(void *data) : data(data) {}

            int32_t Validate() {
                if (nullptr == data)
                    return 0;

                return Functionals::GetInstance().SurfaceControl__Validate(data);
            }

            Surface *GetSurface() {
                if (nullptr == data)
                    return nullptr;

                auto result = Functionals::GetInstance().SurfaceControl__GetSurface(data);

                return reinterpret_cast<Surface *>(reinterpret_cast<size_t>(result.pointer) + sizeof(std::max_align_t) / 2);
            }

            void DisConnect() {
                if (nullptr == data)
                    return;

                Functionals::GetInstance().SurfaceControl__DisConnect(data);
            }

            void DestroySurface(Surface *surface) {
                if (nullptr == data || nullptr == surface)
                    return;

                Functionals::GetInstance().RefBase__DecStrong(reinterpret_cast<Surface *>(reinterpret_cast<size_t>(surface) - sizeof(std::max_align_t) / 2), this);
                DisConnect();
                Functionals::GetInstance().RefBase__DecStrong(data, this);
            }
        };

        struct SurfaceComposerClientTransaction {
            char data[1024];

            SurfaceComposerClientTransaction() {
                Functionals::GetInstance().SurfaceComposerClient__Transaction__Constructor(data);
            }

            void *SetLayer(StrongPointer<void> &surfaceControl, int32_t z) {
                return Functionals::GetInstance().SurfaceComposerClient__Transaction__SetLayer(data, surfaceControl, z);
            }

            void *SetTrustedOverlay(StrongPointer<void> &surfaceControl, bool isTrustedOverlay) {
                return Functionals::GetInstance().SurfaceComposerClient__Transaction__SetTrustedOverlay(data, surfaceControl, isTrustedOverlay);
            }

            int32_t Apply(bool synchronous, bool oneWay) {
                if (12 >= Functionals::GetInstance().systemVersion)
                    return reinterpret_cast<int32_t (*)(void *, bool)>(Functionals::GetInstance().SurfaceComposerClient__Transaction__Apply)(data, synchronous);
                else
                    return Functionals::GetInstance().SurfaceComposerClient__Transaction__Apply(data, synchronous, oneWay);
            }
        };

        struct SurfaceComposerClient {
            char data[1024];

            SurfaceComposerClient() {
                Functionals::GetInstance().SurfaceComposerClient__Constructor(data);
                Functionals::GetInstance().RefBase__IncStrong(data, this);
            }

            SurfaceControl CreateSurface(const char *name, int32_t width, int32_t height, bool skipScrenshot, int32_t z = 0, float x = 0.f, float y = 0.f) {
                void *parentHandle = nullptr;
                String8 windowName(name);
                LayerMetadata layerMetadata;
                if (skipScrenshot && (Functionals::GetInstance().systemVersion == 10 || Functionals::GetInstance().systemVersion == 11)) {
                    layerMetadata.setInt32(2u, 441731);
                }
                uint32_t flags = 0;
                if (skipScrenshot && Functionals::GetInstance().systemVersion >= 12) {
                    flags |= 0x40;
                }

                if (12 <= Functionals::GetInstance().systemVersion) {
                    static void *fakeParentHandleForBinder = nullptr;
                    parentHandle = &fakeParentHandleForBinder;
                }

                StrongPointer<void> result;
                if (Functionals::GetInstance().systemVersion == 9) {
                    int32_t windowType = -1;
                    int32_t ownerUid = -1;
                    if (skipScrenshot) {
                        windowType = 441731;
                    }
                    result = Functionals::GetInstance().SurfaceComposerClient__CreateSurface_and9(data, windowName, width, height, 1, flags, parentHandle, windowType, ownerUid);
                } else if (Functionals::GetInstance().systemVersion >= 10) {
                    result = Functionals::GetInstance().SurfaceComposerClient__CreateSurface(data, windowName, width, height, 1, flags, parentHandle, layerMetadata, nullptr);
                }

                // One transaction per create (the original reused a static one, which is
                // unsafe when the caller creates/destroys layers repeatedly — the HUD
                // toggle does exactly that). The transaction object is never destructed; that
                // leak is bounded by the number of layer recreations.
                {
                    SurfaceComposerClientTransaction transaction;
                    StrongPointer<void> surfaceControl(result);
                    transaction.SetLayer(surfaceControl, z);
                    transaction.SetPosition(surfaceControl, x, y);
                    if (12 <= Functionals::GetInstance().systemVersion) {
                        transaction.SetTrustedOverlay(surfaceControl, true);
                    }
                    transaction.Apply(false, true);
                }
                return {result.get()};
            }

            bool GetDisplayInfo(ui::DisplayState *displayInfo) {
                StrongPointer<void> defaultDisplay;

                if (9 >= Functionals::GetInstance().systemVersion) { //小于或者等于安卓9
                    defaultDisplay = Functionals::GetInstance().SurfaceComposerClient__GetBuiltInDisplay(ui::DisplayType::DisplayIdMain);
                } else {
                    if (14 > Functionals::GetInstance().systemVersion) {//小于或者等于安卓13
                        defaultDisplay = Functionals::GetInstance().SurfaceComposerClient__GetInternalDisplayToken();
                    } else { //安卓14级以上
                        auto displayIds = Functionals::GetInstance().SurfaceComposerClient__GetPhysicalDisplayIds();
                        if (displayIds.empty())
                            return false;

                        defaultDisplay = Functionals::GetInstance().SurfaceComposerClient__GetPhysicalDisplayToken(displayIds[0]);
                    }
                }

                if (nullptr == defaultDisplay.get())
                    return false;

                if (11 <= Functionals::GetInstance().systemVersion) { //大于或者等于安卓11
                    return 0 == Functionals::GetInstance().SurfaceComposerClient__GetDisplayState(defaultDisplay, displayInfo);
                } else { //安卓10及以下
                    ui::DisplayInfo realDisplayInfo{};
                    if (0 != Functionals::GetInstance().SurfaceComposerClient__GetDisplayInfo(defaultDisplay, &realDisplayInfo))
                        return false;

                    displayInfo->layerStackSpaceRect.width = realDisplayInfo.w;
                    displayInfo->layerStackSpaceRect.height = realDisplayInfo.h;
                    displayInfo->orientation = static_cast<ui::Rotation>(realDisplayInfo.orientation);

                    return true;
                }
            }
        };

    }

    class ANativeWindowCreator {
    public:
        struct DisplayInfo {
            int32_t orientation;
            int32_t width;
            int32_t height;
        };

    public:
        static detail::SurfaceComposerClient &GetComposerInstance() {
            static detail::SurfaceComposerClient surfaceComposerClient;
            return surfaceComposerClient;
        }

        // Whether the runtime symbol resolution succeeded for this Android version.
        // Check this before calling Create() to distinguish "mechanism unavailable"
        // (returns false) from "layer creation failed" (Create returns nullptr).
        static bool SymbolsReady() {
            return detail::Functionals::GetInstance().Ready();
        }

        static DisplayInfo GetDisplayInfo() {
            auto &surfaceComposerClient = GetComposerInstance();
            detail::ui::DisplayState displayInfo{};

            if (!surfaceComposerClient.GetDisplayInfo(&displayInfo))
                return {};

            DisplayInfo local_displayInfo{0};
            int32_t local_orientation = static_cast<int32_t>(displayInfo.orientation);
            int32_t local_abs_x = (displayInfo.layerStackSpaceRect.width > displayInfo.layerStackSpaceRect.height ? displayInfo.layerStackSpaceRect.width : displayInfo.layerStackSpaceRect.height);
            int32_t local_abs_y = (displayInfo.layerStackSpaceRect.width < displayInfo.layerStackSpaceRect.height ? displayInfo.layerStackSpaceRect.width : displayInfo.layerStackSpaceRect.height);
            if (local_orientation == 1 || local_orientation == 3) {
                local_displayInfo.width = local_abs_x;
                local_displayInfo.height = local_abs_y;
            } else {
                local_displayInfo.width = local_abs_y;
                local_displayInfo.height = local_abs_x;
            }
            local_displayInfo.orientation = local_orientation;
            return local_displayInfo;
        }

        // Create a layer at an explicit position (layer-stack space). A content-sized
        // layer costs far less to write/composite than a full-screen one — the HUD's
        // anti-jank design depends on this.
        static ANativeWindow *Create(const char *name, int32_t width, int32_t height, bool skipScrenshot_, int32_t z, float x, float y) {
            auto &surfaceComposerClient = GetComposerInstance();
            auto surfaceControl = surfaceComposerClient.CreateSurface(name, width, height, skipScrenshot_, z, x, y);
            auto nativeWindow = reinterpret_cast<ANativeWindow *>(surfaceControl.GetSurface());

            m_cachedSurfaceControl.emplace(nativeWindow, std::move(surfaceControl));
            return nativeWindow;
        }

        static ANativeWindow *Create(const char *name, int32_t width = -1, int32_t height = -1, bool skipScrenshot_ = false, int32_t z = 0) {
            auto &surfaceComposerClient = GetComposerInstance();
            while (-1 == width || -1 == height) {
                detail::ui::DisplayState displayInfo{};

                if (!surfaceComposerClient.GetDisplayInfo(&displayInfo))
                    break;

                width = displayInfo.layerStackSpaceRect.width;
                height = displayInfo.layerStackSpaceRect.height;

                break;
            }

            auto surfaceControl = surfaceComposerClient.CreateSurface(name, width, height, skipScrenshot_, z, 0.f, 0.f);
            auto nativeWindow = reinterpret_cast<ANativeWindow *>(surfaceControl.GetSurface());

            m_cachedSurfaceControl.emplace(nativeWindow, std::move(surfaceControl));
            return nativeWindow;
        }

        static void Destroy(ANativeWindow *nativeWindow) {
            auto it = m_cachedSurfaceControl.find(nativeWindow);
            if (it == m_cachedSurfaceControl.end())
                return;

            m_cachedSurfaceControl[nativeWindow].DestroySurface(reinterpret_cast<detail::Surface *>(nativeWindow));
            m_cachedSurfaceControl.erase(nativeWindow);
        }

    private:
        inline static std::unordered_map<ANativeWindow *, detail::SurfaceControl> m_cachedSurfaceControl;
    };
}

#endif // !A_NATIVE_WINDOW_CREATOR_H
