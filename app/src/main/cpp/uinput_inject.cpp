#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <linux/uinput.h>
#include <linux/input.h>
#include <string.h>
#include <sys/ioctl.h>
#include <errno.h>
#include <android/log.h>

#define LOG_TAG "UinputInject"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// uinput_abs_setup for setting ABS axis ranges (kernel 5.14+)
#ifndef UI_ABS_SETUP
#define UI_ABS_SETUP _IOW(UINPUT_IOCTL_BASE, 5, struct uinput_abs_setup)
#endif

// Manually define uinput_abs_setup since Android headers may not have correct field names
struct uinput_abs_setup_manual {
    __u32 code;
    struct {
        __s32 value;
        __s32 min;
        __s32 max;
        __s32 fuzz;
        __s32 flat;
        __s32 res;
    } absinfo;
};

static int uinput_fd = -1;

extern "C" {

// Force hardcoded correct values for OPD2404 (OnePlus Pad Pro)
static int g_dev_abs_max_x = 21199;
static int g_dev_abs_max_y = 29999;
static int g_screen_w = 2120;
static int g_screen_h = 3000;

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_setDeviceResolution(JNIEnv *env, jclass cls, jint devW, jint devH) {
    g_dev_abs_max_x = devW;
    g_dev_abs_max_y = devH;
    LOGD("setDeviceResolution: device_abs_max=%dx%d", devW, devH);
}

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_setScreenResolution(JNIEnv *env, jclass cls, jint screenW, jint screenH) {
    g_screen_w = screenW;
    g_screen_h = screenH;
    LOGD("setScreenResolution: screen=%dx%d", screenW, screenH);
}

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_UinputInjector_1setDeviceResolutionNative(JNIEnv *env, jclass cls, jint devW, jint devH) {
    g_dev_abs_max_x = devW;
    g_dev_abs_max_y = devH;
    LOGD("UinputInjector_setDeviceResolutionNative: device_abs_max=%dx%d", devW, devH);
}

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_UinputInjector_1setScreenResolution(JNIEnv *env, jclass cls, jint screenW, jint screenH) {
    g_screen_w = screenW;
    g_screen_h = screenH;
    LOGD("setScreenResolution: screen=%dx%d", screenW, screenH);
}

static void set_abs_range(int fd, int axis, int min, int max, int fuzz, int flat, int res) {
    struct uinput_abs_setup_manual abs_setup;
    memset(&abs_setup, 0, sizeof(abs_setup));
    abs_setup.code = axis;
    abs_setup.absinfo.min = min;
    abs_setup.absinfo.max = max;
    abs_setup.absinfo.fuzz = fuzz;
    abs_setup.absinfo.flat = flat;
    abs_setup.absinfo.res = res;
    int ret = ioctl(fd, UI_ABS_SETUP, &abs_setup);
    if (ret < 0) {
        LOGE("UI_ABS_SETUP for axis %d failed: %s (errno=%d)", axis, strerror(errno), errno);
    } else {
        LOGD("UI_ABS_SETUP axis %d range [%d,%d] OK", axis, min, max);
    }
}

JNIEXPORT jint JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_openUinputNative(JNIEnv *env, jobject thiz) {
    if (uinput_fd >= 0) {
        close(uinput_fd);
        uinput_fd = -1;
    }

    // Try to open /dev/uinput
    uinput_fd = open("/dev/uinput", O_RDWR | O_NONBLOCK);
    if (uinput_fd < 0) {
        LOGE("Failed to open /dev/uinput (O_RDWR): %s", strerror(errno));
        uinput_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    }
    if (uinput_fd < 0) {
        LOGE("Failed to open /dev/uinput (O_WRONLY): %s", strerror(errno));
        uinput_fd = open("/dev/input/uinput", O_WRONLY | O_NONBLOCK);
    }
    if (uinput_fd < 0) {
        LOGE("Failed to open /dev/input/uinput: %s", strerror(errno));
        uinput_fd = open("/dev/misc/uinput", O_WRONLY | O_NONBLOCK);
    }
    if (uinput_fd < 0) {
        LOGE("Failed to open all uinput paths: %s", strerror(errno));
        return -1;
    }

    LOGD("Opened uinput, fd=%d", uinput_fd);

    // Setup the device
    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    usetup.id.bustype = BUS_VIRTUAL;
    usetup.id.vendor = 0x1234;
    usetup.id.product = 0x5678;
    usetup.id.version = 1;
    strcpy(usetup.name, "AimbotTouch");

    if (ioctl(uinput_fd, UI_DEV_SETUP, &usetup) < 0) {
        LOGE("UI_DEV_SETUP failed: %s", strerror(errno));
        close(uinput_fd);
        uinput_fd = -1;
        return -1;
    }

    LOGD("UI_DEV_SETUP OK");

    // Enable event types
    ioctl(uinput_fd, UI_SET_EVBIT, EV_ABS);
    ioctl(uinput_fd, UI_SET_EVBIT, EV_KEY);
    ioctl(uinput_fd, UI_SET_EVBIT, EV_SYN);
    ioctl(uinput_fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(uinput_fd, UI_SET_KEYBIT, BTN_LEFT);
    ioctl(uinput_fd, UI_SET_KEYBIT, BTN_TOOL_FINGER);

    // Multi-touch ABS bits
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_X);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_Y);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_TOOL_TYPE);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_PRESSURE);

    // Set INPUT_PROP_DIRECT so the system treats this as a direct touch device
    ioctl(uinput_fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

    LOGD("ABS bits set, g_dev_abs_max_x=%d g_screen_w=%d", g_dev_abs_max_x, g_screen_w);

    // Set the actual ABS ranges via UI_ABS_SETUP (kernel 5.14+)
    set_abs_range(uinput_fd, ABS_X, 0, g_dev_abs_max_x, 0, 0, 0);
    set_abs_range(uinput_fd, ABS_Y, 0, g_dev_abs_max_y, 0, 0, 0);
    set_abs_range(uinput_fd, ABS_MT_POSITION_X, 0, g_dev_abs_max_x, 0, 0, 0);
    set_abs_range(uinput_fd, ABS_MT_POSITION_Y, 0, g_dev_abs_max_y, 0, 0, 0);
    set_abs_range(uinput_fd, ABS_MT_SLOT, 0, 9, 0, 0, 0);
    set_abs_range(uinput_fd, ABS_MT_TRACKING_ID, 0, 65535, 0, 0, 0);

    LOGD("ABS ranges set, calling UI_DEV_CREATE");

    // Create device
    if (ioctl(uinput_fd, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE failed: %s", strerror(errno));
        close(uinput_fd);
        uinput_fd = -1;
        return -1;
    }

    LOGD("Uinput device created successfully, returning fd=%d", uinput_fd);
    return uinput_fd;
}

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_closeUinputNative(JNIEnv *env, jobject thiz) {
    if (uinput_fd >= 0) {
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
        uinput_fd = -1;
        LOGD("Uinput closed");
    }
}

static void send_mt_event(int fd, int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    ev.time.tv_sec = 0;
    ev.time.tv_usec = 0;
    write(fd, &ev, sizeof(ev));
}

static void send_sync(int fd) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = EV_SYN;
    ev.code = SYN_REPORT;
    ev.value = 0;
    ev.time.tv_sec = 0;
    ev.time.tv_usec = 0;
    write(fd, &ev, sizeof(ev));
}

JNIEXPORT jboolean JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_uinputSendDown(JNIEnv *env, jobject thiz, jint fd, jint x, jint y, jint pointerId) {
    if (fd < 0) {
        LOGE("sendTouchDown: uinput_fd not open");
        return JNI_FALSE;
    }

    // 90° rotation: landscape screen (3000x2120) -> portrait device (21199x29999)
    int dev_x = (y * g_dev_abs_max_x) / g_screen_h;
    int dev_y = (x * g_dev_abs_max_y) / g_screen_w;

    LOGD("TouchDown raw x=%d y=%d screen=%dx%d device=%dx%d dev=(%d,%d)",
         x, y, g_screen_w, g_screen_h, g_dev_abs_max_x, g_dev_abs_max_y, dev_x, dev_y);

    // ABS_MT_SLOT to set slot first
    send_mt_event(fd, EV_ABS, ABS_MT_SLOT, pointerId);
    // ABS_MT_TRACKING_ID to claim slot with this pointer ID
    send_mt_event(fd, EV_ABS, ABS_MT_TRACKING_ID, pointerId);
    // ABS_MT_TOOL_TYPE to indicate finger
    send_mt_event(fd, EV_ABS, ABS_MT_TOOL_TYPE, MT_TOOL_FINGER);
    // ABS_MT_PRESSURE
    send_mt_event(fd, EV_ABS, ABS_MT_PRESSURE, 50);
    // Position (both device coords and screen coords)
    send_mt_event(fd, EV_ABS, ABS_X, dev_x);
    send_mt_event(fd, EV_ABS, ABS_Y, dev_y);
    send_mt_event(fd, EV_ABS, ABS_MT_POSITION_X, dev_x);
    send_mt_event(fd, EV_ABS, ABS_MT_POSITION_Y, dev_y);
    // BTN_TOUCH down
    send_mt_event(fd, EV_KEY, BTN_TOUCH, 1);
    send_mt_event(fd, EV_KEY, BTN_TOOL_FINGER, 1);
    // Sync
    send_sync(fd);

    LOGD("TouchDown x=%d y=%d id=%d (dev=%d,%d)", x, y, pointerId, dev_x, dev_y);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_uinputSendMove(JNIEnv *env, jobject thiz, jint fd, jint x, jint y, jint pointerId) {
    if (fd < 0) {
        LOGE("sendTouchMove: uinput_fd not open");
        return JNI_FALSE;
    }

    int dev_x = (y * g_dev_abs_max_x) / g_screen_h;
    int dev_y = (x * g_dev_abs_max_y) / g_screen_w;

    send_mt_event(fd, EV_ABS, ABS_MT_SLOT, pointerId);
    send_mt_event(fd, EV_ABS, ABS_MT_TRACKING_ID, pointerId);
    send_mt_event(fd, EV_ABS, ABS_MT_TOOL_TYPE, MT_TOOL_FINGER);
    send_mt_event(fd, EV_ABS, ABS_MT_PRESSURE, 50);
    send_mt_event(fd, EV_ABS, ABS_X, dev_x);
    send_mt_event(fd, EV_ABS, ABS_Y, dev_y);
    send_mt_event(fd, EV_ABS, ABS_MT_POSITION_X, dev_x);
    send_mt_event(fd, EV_ABS, ABS_MT_POSITION_Y, dev_y);
    send_sync(fd);

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_uinputSendUp(JNIEnv *env, jobject thiz, jint fd, jint pointerId) {
    if (fd < 0) {
        LOGE("sendTouchUp: uinput_fd not open");
        return JNI_FALSE;
    }

    // Release tracking ID
    send_mt_event(fd, EV_ABS, ABS_MT_SLOT, pointerId);
    send_mt_event(fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
    send_mt_event(fd, EV_ABS, ABS_MT_PRESSURE, 0);
    send_mt_event(fd, EV_KEY, BTN_TOUCH, 0);
    send_mt_event(fd, EV_KEY, BTN_TOOL_FINGER, 0);
    send_sync(fd);

    LOGD("TouchUp id=%d", pointerId);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_team_maodie_aimbot_UinputInjector_sendTap(JNIEnv *env, jobject thiz, jint x, jint y) {
    if (uinput_fd < 0) {
        LOGE("sendTap: uinput_fd not open");
        return JNI_FALSE;
    }

    // Down - direct mapping: screen -> device
    int dev_x_down = (y * g_dev_abs_max_x) / g_screen_h;
    int dev_y_down = (x * g_dev_abs_max_y) / g_screen_w;
    send_mt_event(uinput_fd, EV_ABS, ABS_MT_SLOT, 15);
    send_mt_event(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, 15);
    send_mt_event(uinput_fd, EV_ABS, ABS_MT_TOOL_TYPE, MT_TOOL_FINGER);
    send_mt_event(uinput_fd, EV_ABS, ABS_MT_PRESSURE, 50);
    send_mt_event(uinput_fd, EV_ABS, ABS_X, dev_x_down);
    send_mt_event(uinput_fd, EV_ABS, ABS_Y, dev_y_down);
    send_mt_event(uinput_fd, EV_ABS, ABS_MT_POSITION_X, dev_x_down);
    send_mt_event(uinput_fd, EV_ABS, ABS_MT_POSITION_Y, dev_y_down);
    send_mt_event(uinput_fd, EV_KEY, BTN_TOUCH, 1);
    send_mt_event(uinput_fd, EV_KEY, BTN_TOOL_FINGER, 1);
    send_sync(uinput_fd);

    usleep(10000); // 10ms

    // Up
    send_mt_event(uinput_fd, EV_ABS, ABS_MT_SLOT, 15);
    send_mt_event(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
    send_mt_event(uinput_fd, EV_ABS, ABS_MT_PRESSURE, 0);
    send_mt_event(uinput_fd, EV_KEY, BTN_TOUCH, 0);
    send_mt_event(uinput_fd, EV_KEY, BTN_TOOL_FINGER, 0);
    send_sync(uinput_fd);

    LOGD("Tap x=%d y=%d", x, y);
    return JNI_TRUE;
}

} // extern "C"