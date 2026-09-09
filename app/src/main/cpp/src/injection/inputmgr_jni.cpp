// inputmgr_jni.cpp — JNI bridge over inputmgr_core
// For InputManager injection mode in RemoteInjectorService
// Matches reference implementation: TouchMergerUserService.kt

#include <jni.h>
#include <android/log.h>
#include "inputmgr_core.h"

#define LOG_TAG "InputMgrJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ═════════════════════════════════════════════════════════════════════
//  JNI Interface
//  Package: io.github.love3025.yolovaim.service.RemoteInjectorService
// ═════════════════════════════════════════════════════════════════════

extern "C" {

// ─── Lifecycle ──────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrInit(
    JNIEnv*, jclass, jint screenW, jint screenH)
{
    bool ok = inputmgr_init(screenW, screenH);
    LOGD("nativeInputmgrInit: %dx%d -> %s", screenW, screenH, ok ? "OK" : "FAIL");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrClose(
    JNIEnv*, jclass)
{
    inputmgr_close();
    LOGD("nativeInputmgrClose");
}

// ─── Grab control ───────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrGrab(
    JNIEnv*, jclass)
{
    inputmgr_grab();
}

JNIEXPORT void JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrUngrab(
    JNIEnv*, jclass)
{
    inputmgr_ungrab();
}

JNIEXPORT jboolean JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrIsGrabbed(
    JNIEnv*, jclass)
{
    return inputmgr_is_grabbed() ? JNI_TRUE : JNI_FALSE;
}

// ─── Blocking poll ──────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrPollAndUpdate(
    JNIEnv*, jclass, jint timeoutMs)
{
    return inputmgr_poll_and_update(timeoutMs);
}

// ─── Pointer reading ────────────────────────────────────────────────
// Returns int[N*4]: [id0, x0, y0, isDown0, id1, x1, y1, isDown1, ...]

JNIEXPORT jintArray JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeReadPointers(
    JNIEnv* env, jclass)
{
    static constexpr int MAX_PTR = 16;
    PhysicalPointer ptrs[MAX_PTR];
    int count = inputmgr_read_pointers(ptrs, MAX_PTR);

    jintArray result = env->NewIntArray(count * 4);
    if (!result) return nullptr;

    jint buf[MAX_PTR * 4];
    for (int i = 0; i < count; i++) {
        buf[i * 4 + 0] = ptrs[i].id;
        buf[i * 4 + 1] = static_cast<jint>(ptrs[i].x);
        buf[i * 4 + 2] = static_cast<jint>(ptrs[i].y);
        buf[i * 4 + 3] = 1;  // isDown = true (only active pointers are returned)
    }
    env->SetIntArrayRegion(result, 0, count * 4, buf);
    return result;
}

// ─── Device info ────────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrGetDeviceId(
    JNIEnv*, jclass)
{
    return inputmgr_get_device_id();
}

JNIEXPORT jint JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrGetMaxX(
    JNIEnv*, jclass)
{
    return inputmgr_get_max_x();
}

JNIEXPORT jint JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrGetMaxY(
    JNIEnv*, jclass)
{
    return inputmgr_get_max_y();
}

JNIEXPORT jboolean JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrHasSlotSupport(
    JNIEnv*, jclass)
{
    return inputmgr_has_slot_support() ? JNI_TRUE : JNI_FALSE;
}

// ─── Screen params ──────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrSetScreenParams(
    JNIEnv*, jclass, jint w, jint h, jint rotation)
{
    inputmgr_set_screen_params(w, h, rotation);
    LOGD("nativeInputmgrSetScreenParams: %dx%d rotation=%d", w, h, rotation);
}

// ─── Zone configuration ────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrSetTriggerZone(
    JNIEnv*, jclass, jint l, jint t, jint r, jint b)
{
    inputmgr_set_trigger_zone(l, t, r, b);
    LOGD("InputmgrTriggerZone: (%d,%d)-(%d,%d)", l, t, r, b);
}

JNIEXPORT void JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrSetFireZone(
    JNIEnv*, jclass, jint l, jint t, jint r, jint b)
{
    inputmgr_set_fire_zone(l, t, r, b);
    LOGD("InputmgrFireZone: (%d,%d)-(%d,%d)", l, t, r, b);
}

JNIEXPORT void JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrSetJoystickZone(
    JNIEnv*, jclass, jint l, jint t, jint r, jint b)
{
    inputmgr_set_joystick_zone(l, t, r, b);
    LOGD("InputmgrJoystickZone: (%d,%d)-(%d,%d)", l, t, r, b);
}

JNIEXPORT jboolean JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrIsFingerInTriggerZone(
    JNIEnv*, jclass)
{
    return inputmgr_is_finger_in_trigger_zone() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrIsFingerInFireZone(
    JNIEnv*, jclass)
{
    return inputmgr_is_finger_in_fire_zone() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrConsumeFireState(
    JNIEnv*, jclass)
{
    return (jint)inputmgr_consume_fire_state();
}

JNIEXPORT jboolean JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrIsFingerInJoystickZone(
    JNIEnv*, jclass)
{
    return inputmgr_is_finger_in_joystick_zone() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeInputmgrLiftJoystickFinger(
    JNIEnv*, jclass)
{
    return inputmgr_lift_joystick_finger() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
