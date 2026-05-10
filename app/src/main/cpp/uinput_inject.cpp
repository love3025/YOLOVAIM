#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <linux/uinput.h>
#include <linux/input.h>
#include <sys/ioctl.h>
#include <errno.h>
#include <android/log.h>
#include <sys/select.h>

#define LOG_TAG "UinputInject"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef UI_ABS_SETUP
#define UI_ABS_SETUP _IOW(UINPUT_IOCTL_BASE, 5, struct uinput_abs_setup)
#endif

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

#define MAX_SLOTS        10
#define VIRTUAL_SLOT     9
#define VIRTUAL_TRACKING 1000

// Touch device path — set dynamically from Java via setTouchDevicePath().
// Defaults to /dev/input/event0.
static char g_touch_device[256] = "/dev/input/event0";

typedef struct {
    int active;
    int tracking_id;
    int x;
    int y;
} slot_state_t;

static int uinput_fd = -1;
static slot_state_t real_slots[MAX_SLOTS];
static int uinput_slot_active[MAX_SLOTS];
static pthread_mutex_t uinput_mutex = PTHREAD_MUTEX_INITIALIZER;

static volatile int virtual_active = 0;
static int virtual_x = 0;
static int virtual_y = 0;

// getevent thread
static pthread_t getevent_thread;
static volatile int getevent_running = 0;
static FILE* getevent_fp = NULL;

static int g_dev_abs_max_x = 21199;
static int g_dev_abs_max_y = 29999;
static int g_screen_w = 2120;
static int g_screen_h = 3000;
// 0=landscape启动(需要旋转), 1=portrait启动(不需要旋转)
static int g_landscape_start = 1;

extern "C" {

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_setDeviceResolution(JNIEnv *env, jclass cls, jint devW, jint devH) {
    g_dev_abs_max_x = devW;
    g_dev_abs_max_y = devH;
}

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_setScreenResolution(JNIEnv *env, jclass cls, jint screenW, jint screenH) {
    g_screen_w = screenW;
    g_screen_h = screenH;
}

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_setLandscapeStart(JNIEnv *env, jclass cls, jint isLandscape) {
    g_landscape_start = isLandscape;
}

// Auto-detect the real multi-touch device by running "getevent -p".
// Looks for a device with "touchpanel" in its name (excluding "Aimbot" and "pen")
// that supports multi-touch axes (ABS_MT_SLOT = 0x2f).
// Falls back to /dev/input/event0 if detection fails.
static void detect_touch_device() {
    FILE* fp = popen("/system/bin/getevent -p 2>&1", "r");
    if (!fp) {
        LOGE("detect_touch_device: popen failed");
        return;
    }

    char line[256];
    char current_path[256] = "/dev/input/event0";
    int is_touchpanel = 0;
    int has_mt = 0;

    while (fgets(line, sizeof(line), fp)) {
        // Track device path from "add device" lines
        if (strstr(line, "add device") && strstr(line, "/dev/input/event")) {
            // Starting a new device — commit previous if it was a good match
            if (is_touchpanel && has_mt) {
                strncpy(g_touch_device, current_path, sizeof(g_touch_device) - 1);
                g_touch_device[sizeof(g_touch_device) - 1] = '\0';
                pclose(fp);
                LOGD("Detected touch device: %s", g_touch_device);
                return;
            }
            // Reset for next device
            char* p = strstr(line, "/dev/input/event");
            if (p) {
                strncpy(current_path, p, sizeof(current_path) - 1);
                current_path[sizeof(current_path) - 1] = '\0';
                char* end = current_path + strlen(current_path) - 1;
                while (end > current_path && (*end == ' ' || *end == '\n' || *end == '\r')) {
                    *end-- = '\0';
                }
            }
            is_touchpanel = 0;
            has_mt = 0;
        }

        // Check device name: must contain "touchpanel", exclude "Aimbot" and "pen"
        if (strstr(line, "touchpanel") && !strstr(line, "Aimbot")
            && !strstr(line, "pen") && !strstr(line, "Pen")) {
            is_touchpanel = 1;
        }

        // Check for multi-touch support: ABS_MT_SLOT = 0x2f
        if (strstr(line, "002f")) {
            has_mt = 1;
        }
    }

    // Check the last device
    if (is_touchpanel && has_mt) {
        strncpy(g_touch_device, current_path, sizeof(g_touch_device) - 1);
        g_touch_device[sizeof(g_touch_device) - 1] = '\0';
    }

    pclose(fp);
    LOGD("Touch device: %s", g_touch_device);
}

static void set_abs_range(int fd, int axis, int min, int max) {
    struct uinput_abs_setup_manual abs_setup;
    memset(&abs_setup, 0, sizeof(abs_setup));
    abs_setup.code = axis;
    abs_setup.absinfo.min = min;
    abs_setup.absinfo.max = max;
    ioctl(fd, UI_ABS_SETUP, &abs_setup);
}

static inline void ev(int fd, int type, int code, int value) {
    struct input_event e;
    memset(&e, 0, sizeof(e));
    e.type = type;
    e.code = code;
    e.value = value;
    write(fd, &e, sizeof(e));
}

static inline void sync(int fd) {
    ev(fd, EV_SYN, SYN_REPORT, 0);
}

// =========================================================================
// Send complete frame: physical slots (changed only) + virtual slot.
// Called with uinput_mutex HELD.
// =========================================================================
static void send_frame_locked() {
    int any_physical = 0;

    for (int i = 0; i < MAX_SLOTS; i++) {
        if (i == VIRTUAL_SLOT) continue;

        if (real_slots[i].active) {
            ev(uinput_fd, EV_ABS, ABS_MT_SLOT, i);
            ev(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, real_slots[i].tracking_id);
            ev(uinput_fd, EV_ABS, ABS_MT_POSITION_X, real_slots[i].x);
            ev(uinput_fd, EV_ABS, ABS_MT_POSITION_Y, real_slots[i].y);
            ev(uinput_fd, EV_ABS, ABS_MT_TOOL_TYPE, 1);
            ev(uinput_fd, EV_ABS, ABS_MT_PRESSURE, 50);
            uinput_slot_active[i] = 1;
            any_physical = 1;
        } else if (uinput_slot_active[i]) {
            ev(uinput_fd, EV_ABS, ABS_MT_SLOT, i);
            ev(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
            uinput_slot_active[i] = 0;
        }
    }

    ev(uinput_fd, EV_ABS, ABS_MT_SLOT, VIRTUAL_SLOT);
    if (virtual_active) {
        ev(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, VIRTUAL_TRACKING);
        ev(uinput_fd, EV_ABS, ABS_MT_POSITION_X, virtual_x);
        ev(uinput_fd, EV_ABS, ABS_MT_POSITION_Y, virtual_y);
        ev(uinput_fd, EV_ABS, ABS_MT_TOOL_TYPE, 1);
        ev(uinput_fd, EV_ABS, ABS_MT_PRESSURE, 50);
    } else {
        ev(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
    }

    int touch_down = any_physical || virtual_active;
    ev(uinput_fd, EV_KEY, BTN_TOUCH, touch_down ? 1 : 0);
    ev(uinput_fd, EV_KEY, BTN_TOOL_FINGER, touch_down ? 1 : 0);

    if (any_physical) {
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (i != VIRTUAL_SLOT && real_slots[i].active) {
                ev(uinput_fd, EV_ABS, ABS_X, real_slots[i].x);
                ev(uinput_fd, EV_ABS, ABS_Y, real_slots[i].y);
                break;
            }
        }
    } else if (virtual_active) {
        ev(uinput_fd, EV_ABS, ABS_X, virtual_x);
        ev(uinput_fd, EV_ABS, ABS_Y, virtual_y);
    }

    sync(uinput_fd);
}

// =========================================================================
// getevent reader — reads physical events via getevent command,
// updates real_slots[], and forwards to uinput on every SYN_REPORT.
// =========================================================================
static void* getevent_reader(void* arg) {
    char cmd[256];
    // Redirect stderr to stdout so we can see getevent errors in our logging
    snprintf(cmd, sizeof(cmd), "/system/bin/getevent %s 2>&1", g_touch_device);

    FILE* fp = popen(cmd, "r");
    if (!fp) {
        LOGE("getevent: popen failed, errno=%d", errno);
        getevent_running = 0;
        return NULL;
    }
    getevent_fp = fp;

    // Read the first line to check if getevent started successfully.
    // If getevent fails (e.g. permission denied), the error message
    // will appear here thanks to 2>&1.
    char first[256];
    if (fgets(first, sizeof(first), fp)) {
        LOGD("getevent first line: %s", first);
    } else {
        LOGE("getevent: no output, process may have failed");
        pclose(fp);
        getevent_fp = NULL;
        getevent_running = 0;
        return NULL;
    }
    LOGD("getevent reader started on %s", g_touch_device);

    // Per-slot accumulators — buffer data until SYN_REPORT, then commit
    // all slots at once to real_slots. This keeps real_slots consistent
    // so inject_virtual_touch can safely call send_frame_locked() anytime.
    int cur_slot = 0;
    int slot_tid[MAX_SLOTS] = {0};
    int slot_x[MAX_SLOTS] = {0};
    int slot_y[MAX_SLOTS] = {0};
    int slot_has_tid[MAX_SLOTS] = {0};
    int slot_moved[MAX_SLOTS] = {0};   // received position update this frame
    char line[256];
    strncpy(line, first, sizeof(line) - 1);
    line[sizeof(line) - 1] = '\0';
    int first_line_processed = 0;
    int select_fd = fileno(fp);

    while (getevent_running) {
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(select_fd, &rfds);
        struct timeval tv = {0, 10000}; // 10ms timeout

        int ret = select(select_fd + 1, &rfds, NULL, NULL, &tv);
        if (ret <= 0) {
            // timeout or error, loop to check getevent_running
            if (ret < 0) break;
            continue;
        }

        if (!first_line_processed) {
            first_line_processed = 1;
        } else {
            if (!fgets(line, sizeof(line), fp)) break;
        }

        char* p = line;

        unsigned int type, code, value;
        if (sscanf(p, "%x %x %x", &type, &code, &value) != 3) continue;

        switch (type) {
        case EV_ABS:
            switch (code) {
            case ABS_MT_SLOT:
                cur_slot = (int)value;
                break;
            case ABS_MT_TRACKING_ID:
                if (cur_slot >= 0 && cur_slot < MAX_SLOTS) {
                    slot_tid[cur_slot] = (int)value;
                    slot_has_tid[cur_slot] = 1;
                }
                break;
            case ABS_MT_POSITION_X:
                if (cur_slot >= 0 && cur_slot < MAX_SLOTS) {
                    slot_x[cur_slot] = (int)value;
                    slot_moved[cur_slot] = 1;
                }
                break;
            case ABS_MT_POSITION_Y:
                if (cur_slot >= 0 && cur_slot < MAX_SLOTS) {
                    slot_y[cur_slot] = (int)value;
                    slot_moved[cur_slot] = 1;
                }
                break;
            }
            break;

        case EV_SYN:
            // SYN_MT_REPORT (code=2): do nothing, just accumulate.
            // SYN_REPORT (code=0): commit ALL slots at once.
            // Only send frame when virtual injection is active; otherwise
            // physical touches go through event6 directly (no duplicates).
            if (code == 0) {
                pthread_mutex_lock(&uinput_mutex);

                for (int i = 0; i < MAX_SLOTS; i++) {
                    if (slot_has_tid[i]) {
                        int tid = slot_tid[i];
                        if (tid == -1) {
                            real_slots[i].active = 0;
                        } else {
                            real_slots[i].active = 1;
                            real_slots[i].tracking_id = tid;
                            real_slots[i].x = slot_x[i];
                            real_slots[i].y = slot_y[i];
                        }
                        slot_has_tid[i] = 0;
                        slot_moved[i] = 0;
                    } else if (slot_moved[i] && real_slots[i].active) {
                        real_slots[i].x = slot_x[i];
                        real_slots[i].y = slot_y[i];
                        slot_moved[i] = 0;
                    }
                }

                // Forward if virtual is active, OR if any physical slot is
                // still active on uinput (needs to be sent its lift event).
                int need_frame = virtual_active;
                if (!need_frame) {
                    for (int i = 0; i < MAX_SLOTS; i++) {
                        if (uinput_slot_active[i]) { need_frame = 1; break; }
                    }
                }
                if (uinput_fd >= 0 && need_frame) {
                    send_frame_locked();
                }
                pthread_mutex_unlock(&uinput_mutex);
            }
            break;
        }
    }

    LOGD("getevent reader stopped");
    pclose(fp);
    getevent_fp = NULL;
    getevent_running = 0;
    return NULL;
}

// =========================================================================
// Start / stop forwarder
// =========================================================================

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_startGeteventListenerNative(JNIEnv *env, jobject thiz) {
    if (getevent_running) { LOGD("getevent already running"); return; }

    // Auto-detect the real touch device before starting
    detect_touch_device();

    getevent_running = 1;
    if (pthread_create(&getevent_thread, NULL, getevent_reader, NULL) != 0) {
        LOGE("pthread_create failed");
        getevent_running = 0;
        return;
    }
    LOGD("getevent reader started");
}

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_stopGeteventListenerNative(JNIEnv *env, jobject thiz) {
    if (!getevent_running) return;
    getevent_running = 0;
    if (getevent_fp) { pclose(getevent_fp); getevent_fp = NULL; }
    pthread_join(getevent_thread, NULL);
    LOGD("getevent reader stopped");
}

// =========================================================================
// Virtual touch injection — sends a complete frame with physical + virtual.
// =========================================================================
static void inject_virtual_touch(int dev_x, int dev_y, int is_down) {
    if (uinput_fd < 0) return;

    pthread_mutex_lock(&uinput_mutex);

    virtual_x = dev_x;
    virtual_y = dev_y;
    virtual_active = is_down;

    send_frame_locked();

    pthread_mutex_unlock(&uinput_mutex);
}

// =========================================================================
// Uinput open / close
// =========================================================================

JNIEXPORT jint JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_openUinputNative(JNIEnv *env, jobject thiz) {
    if (uinput_fd >= 0) { close(uinput_fd); uinput_fd = -1; }

    const char* paths[] = {"/dev/uinput", "/dev/input/uinput", "/dev/misc/uinput"};
    for (int i = 0; i < 3; i++) {
        uinput_fd = open(paths[i], O_RDWR | O_NONBLOCK);
        if (uinput_fd >= 0) break;
    }
    if (uinput_fd < 0) { LOGE("Cannot open uinput"); return -1; }

    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    usetup.id.bustype = BUS_VIRTUAL;
    usetup.id.vendor = 0x1234;
    usetup.id.product = 0x5678;
    usetup.id.version = 1;
    strcpy(usetup.name, "AimbotTouch");

    if (ioctl(uinput_fd, UI_DEV_SETUP, &usetup) < 0) {
        LOGE("UI_DEV_SETUP failed"); close(uinput_fd); uinput_fd = -1; return -1;
    }

    ioctl(uinput_fd, UI_SET_EVBIT, EV_ABS);
    ioctl(uinput_fd, UI_SET_EVBIT, EV_KEY);
    ioctl(uinput_fd, UI_SET_EVBIT, EV_SYN);
    ioctl(uinput_fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(uinput_fd, UI_SET_KEYBIT, BTN_TOOL_FINGER);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_X);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_Y);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_TOOL_TYPE);
    ioctl(uinput_fd, UI_SET_ABSBIT, ABS_MT_PRESSURE);
    ioctl(uinput_fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

    set_abs_range(uinput_fd, ABS_X, 0, g_dev_abs_max_x);
    set_abs_range(uinput_fd, ABS_Y, 0, g_dev_abs_max_y);
    set_abs_range(uinput_fd, ABS_MT_POSITION_X, 0, g_dev_abs_max_x);
    set_abs_range(uinput_fd, ABS_MT_POSITION_Y, 0, g_dev_abs_max_y);
    set_abs_range(uinput_fd, ABS_MT_SLOT, 0, 9);
    set_abs_range(uinput_fd, ABS_MT_TRACKING_ID, 0, 65535);

    if (ioctl(uinput_fd, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE failed"); close(uinput_fd); uinput_fd = -1; return -1;
    }

    memset(real_slots, 0, sizeof(real_slots));
    memset(uinput_slot_active, 0, sizeof(uinput_slot_active));
    virtual_active = 0;
    LOGD("Uinput opened fd=%d", uinput_fd);
    return uinput_fd;
}

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_closeUinputNative(JNIEnv *env, jobject thiz) {
    if (getevent_running) {
        getevent_running = 0;
        if (getevent_fp) { pclose(getevent_fp); getevent_fp = NULL; }
        pthread_join(getevent_thread, NULL);
    }

    if (uinput_fd >= 0) {
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
        uinput_fd = -1;
    }

    virtual_active = 0;
    memset(uinput_slot_active, 0, sizeof(uinput_slot_active));
    LOGD("Uinput closed");
}

// =========================================================================
// JNI send functions
// =========================================================================

JNIEXPORT jboolean JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_uinputSendDown(JNIEnv *env, jobject thiz, jint fd, jint x, jint y, jint pointerId) {
    if (uinput_fd < 0) return JNI_FALSE;
    int dev_x, dev_y;
    if (g_landscape_start) {
        // Landscape screen -> device portrait: 90° rotation
        dev_x = (g_screen_h - y) * g_dev_abs_max_x / g_screen_h;
        dev_y = (x * g_dev_abs_max_y) / g_screen_w;
    } else {
        // Portrait screen -> device portrait: no rotation
        float scale_x = (float)g_dev_abs_max_x / g_screen_h;
        float scale_y = (float)g_dev_abs_max_y / g_screen_w;
        dev_x = (int)(y * scale_x);
        dev_y = (int)(x * scale_y);
    }
    inject_virtual_touch(dev_x, dev_y, 1);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_uinputSendMove(JNIEnv *env, jobject thiz, jint fd, jint x, jint y, jint pointerId) {
    if (uinput_fd < 0) return JNI_FALSE;
    int dev_x, dev_y;
    if (g_landscape_start) {
        dev_x = (g_screen_h - y) * g_dev_abs_max_x / g_screen_h;
        dev_y = (x * g_dev_abs_max_y) / g_screen_w;
    } else {
        float scale_x = (float)g_dev_abs_max_x / g_screen_h;
        float scale_y = (float)g_dev_abs_max_y / g_screen_w;
        dev_x = (int)(y * scale_x);
        dev_y = (int)(x * scale_y);
    }
    inject_virtual_touch(dev_x, dev_y, 1);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_uinputSendUp(JNIEnv *env, jobject thiz, jint fd, jint pointerId) {
    if (uinput_fd < 0) return JNI_FALSE;
    inject_virtual_touch(0, 0, 0);
    return JNI_TRUE;
}

} // extern "C"
