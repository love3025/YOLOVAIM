// uinput_inject.cpp — Thin JNI wrapper over touch_core
// All core logic lives in touch_core.cpp

#include <jni.h>
#include <android/log.h>
#include "touch_core.h"

#define LOG_TAG "UinputInject"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int g_screen_w = 0;
static int g_screen_h = 0;

// ══════════════════════════════════════════════════════════════════
//  JNI Interface
//  Package: io.github.love3025.yolovaim.service.RemoteInjectorService
// ══════════════════════════════════════════════════════════════════

extern "C" {

// ─── Configuration ────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_setDeviceResolution(
    JNIEnv*, jclass, jint, jint) {}

JNIEXPORT void JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_setScreenResolution(
    JNIEnv*, jclass, jint screenW, jint screenH)
{
    if (screenW > 0 && screenH > 0) {
        g_screen_w = screenW;
        g_screen_h = screenH;
        LOGD("setScreenResolution: %dx%d", screenW, screenH);
    }
}

JNIEXPORT void JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_setLandscapeStart(
    JNIEnv*, jclass, jint rotation)
{
    // 名字是历史包袱:参数已是 Display.getRotation() 的 0..3,不再是布尔。
    // 只传横/竖会把 90° 和 270° 算成同一张坐标表(见 touch_core.h)。
    touch_set_screen_params(g_screen_w, g_screen_h, rotation);
    LOGD("setLandscapeStart: rotation=%d (screen=%dx%d)", rotation, g_screen_w, g_screen_h);
}

// ─── Lifecycle ────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_openUinputNative(
    JNIEnv*, jobject)
{
    if (touch_init(g_screen_w, g_screen_h))
        return touch_get_output_fd();
    return -1;
}

JNIEXPORT void JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_closeUinputNative(
    JNIEnv*, jobject)
{
    touch_close();
}

JNIEXPORT void JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_startGeteventListenerNative(
    JNIEnv*, jobject)
{
    touch_start_readers();
}

JNIEXPORT void JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_stopGeteventListenerNative(
    JNIEnv*, jobject)
{
    touch_stop_readers();
}

// ─── Virtual touch (aim) — uses TOUCH_VIRTUAL_SLOT on device 0 ──────

JNIEXPORT jboolean JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_uinputSendDown(
    JNIEnv*, jobject, jint, jint x, jint y, jint)
{
    if (!touch_is_initialized()) return JNI_FALSE;
    touch_down(TOUCH_VIRTUAL_SLOT, TOUCH_VIRTUAL_ID, x, y);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_uinputSendMove(
    JNIEnv*, jobject, jint, jint x, jint y, jint)
{
    if (!touch_is_initialized()) return JNI_FALSE;
    touch_move(TOUCH_VIRTUAL_SLOT, x, y);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_uinputSendUp(
    JNIEnv*, jobject, jint, jint)
{
    if (!touch_is_initialized()) return JNI_FALSE;
    touch_up(TOUCH_VIRTUAL_SLOT);
    return JNI_TRUE;
}

// ─── Trigger touch — uses TOUCH_TRIGGER_SLOT on device 0 ────────────

JNIEXPORT void JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_uinputTriggerDown(
    JNIEnv*, jobject, jint x, jint y)
{
    if (!touch_is_initialized()) return;
    touch_down(TOUCH_TRIGGER_SLOT, TOUCH_TRIGGER_ID, x, y);
}

JNIEXPORT void JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_uinputTriggerUp(
    JNIEnv*, jobject)
{
    if (!touch_is_initialized()) return;
    touch_up(TOUCH_TRIGGER_SLOT);
}

// ─── Zone configuration ────────────────────────────────────────

JNIEXPORT void JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeSetTriggerZone(
    JNIEnv*, jclass, jint l, jint t, jint r, jint b)
{
    touch_set_trigger_zone(l, t, r, b);
    LOGD("TriggerZone: (%d,%d)-(%d,%d)", l, t, r, b);
}

JNIEXPORT jboolean JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeIsFingerInTriggerZone(
    JNIEnv*, jclass)
{
    return touch_is_finger_in_trigger_zone() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeSetFireZone(
    JNIEnv*, jclass, jint l, jint t, jint r, jint b)
{
    touch_set_fire_zone(l, t, r, b);
    LOGD("FireZone: (%d,%d)-(%d,%d)", l, t, r, b);
}

JNIEXPORT jboolean JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeIsFingerInFireZone(
    JNIEnv*, jclass)
{
    return touch_is_finger_in_fire_zone() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeConsumeFireState(
    JNIEnv*, jclass)
{
    return (jint)touch_consume_fire_state();
}

JNIEXPORT void JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeSetJoystickZone(
    JNIEnv*, jclass, jint l, jint t, jint r, jint b)
{
    touch_set_joystick_zone(l, t, r, b);
    LOGD("JoystickZone: (%d,%d)-(%d,%d)", l, t, r, b);
}

JNIEXPORT jboolean JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeIsFingerInJoystickZone(
    JNIEnv*, jclass)
{
    return touch_is_finger_in_joystick_zone() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_love3025_yolovaim_service_RemoteInjectorService_nativeLiftJoystickFinger(
    JNIEnv*, jclass)
{
    return touch_lift_joystick_finger() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
