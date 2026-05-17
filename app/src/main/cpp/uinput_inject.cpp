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

static char g_touch_device[256] = "/dev/input/event0";

typedef struct {
    int active;
    int tracking_id;
    int x;
    int y;
} slot_state_t;

typedef struct {
    int abs_x_min, abs_x_max;
    int abs_y_min, abs_y_max;
    int abs_mt_x_min, abs_mt_x_max;
    int abs_mt_y_min, abs_mt_y_max;
    int slot_min, slot_max;
    int tracking_min, tracking_max;
    int abs_0021_min, abs_0021_max;
    int abs_0030_min, abs_0030_max;
    int num_keys;
    int keys[16];
} physical_abs_params_t;

static physical_abs_params_t g_physical_params = {0};

static int uinput_fd = -1;
static slot_state_t real_slots[MAX_SLOTS];
static int uinput_slot_active[MAX_SLOTS];
static pthread_mutex_t uinput_mutex = PTHREAD_MUTEX_INITIALIZER;

static volatile int virtual_active = 0;
static int virtual_x = 0;
static int virtual_y = 0;

// Direct fd reader state
static pthread_t reader_thread;
static volatile int reader_running = 0;
static int grab_fd = -1;  // fd from opening physical device + EVIOCGRAB

static int g_dev_abs_max_x = 21199;
static int g_dev_abs_max_y = 29999;
static int g_screen_w = 2120;
static int g_screen_h = 3000;
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

static void parse_physical_abs_params();

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_setLandscapeStart(JNIEnv *env, jclass cls, jint isLandscape) {
    g_landscape_start = isLandscape;
}

static void detect_touch_device() {
    FILE* fp = popen("/system/bin/getevent -p 2>&1", "r");
    if (!fp) { LOGE("detect_touch_device: popen failed"); return; }

    char line[256];
    char current_path[256] = "/dev/input/event0";
    int is_touchpanel = 0;
    int has_mt = 0;

    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, "add device") && strstr(line, "/dev/input/event")) {
            if (is_touchpanel && has_mt) {
                strncpy(g_touch_device, current_path, sizeof(g_touch_device) - 1);
                g_touch_device[sizeof(g_touch_device) - 1] = '\0';
                pclose(fp);
                parse_physical_abs_params();
                LOGD("Detected touch device: %s", g_touch_device);
                return;
            }
            char* p = strstr(line, "/dev/input/event");
            if (p) {
                strncpy(current_path, p, sizeof(current_path) - 1);
                current_path[sizeof(current_path) - 1] = '\0';
                char* end = current_path + strlen(current_path) - 1;
                while (end > current_path && (*end == ' ' || *end == '\n' || *end == '\r')) *end-- = '\0';
            }
            is_touchpanel = 0;
            has_mt = 0;
        }
        if (strstr(line, "touchpanel") && !strstr(line, "Aimbot") && !strstr(line, "pen") && !strstr(line, "Pen")) {
            is_touchpanel = 1;
        }
        if (strstr(line, "002f")) {
            has_mt = 1;
        }
    }
    if (is_touchpanel && has_mt) {
        strncpy(g_touch_device, current_path, sizeof(g_touch_device) - 1);
        g_touch_device[sizeof(g_touch_device) - 1] = '\0';
    }
    pclose(fp);
    LOGD("Touch device: %s", g_touch_device);
}

static void parse_physical_abs_params() {
    memset(&g_physical_params, 0, sizeof(g_physical_params));
    g_physical_params.slot_max = 9;
    g_physical_params.tracking_max = 65535;

    FILE* fp = popen("/system/bin/getevent -p 2>&1", "r");
    if (!fp) { LOGE("parse_physical_abs_params: popen failed"); return; }

    char line[512];
    int in_our_device = 0;
    int num_keys = 0;

    while (fgets(line, sizeof(line), fp)) {
        if (strncmp(line, "add device", 9) == 0) {
            char* p = strstr(line, "/dev/input/event");
            if (p) in_our_device = (strncmp(p, g_touch_device, strlen(g_touch_device)) == 0);
            else in_our_device = 0;
            continue;
        }
        if (!in_our_device) continue;

        unsigned int code_hex;
        int min_val = 0, max_val = 0;
        if (sscanf(line, "                %x  : value %*d, min %d, max %d", &code_hex, &min_val, &max_val) == 3) {
            switch (code_hex) {
                case 0x21: g_physical_params.abs_0021_min = min_val; g_physical_params.abs_0021_max = max_val; break;
                case 0x2f: g_physical_params.slot_min = min_val; g_physical_params.slot_max = max_val ? max_val : 9; break;
                case 0x30: g_physical_params.abs_0030_min = min_val; g_physical_params.abs_0030_max = max_val; break;
                case 0x35: g_physical_params.abs_x_min = min_val; g_physical_params.abs_x_max = max_val; break;
                case 0x36: g_physical_params.abs_y_min = min_val; g_physical_params.abs_y_max = max_val; break;
                case 0x39: g_physical_params.tracking_min = min_val; g_physical_params.tracking_max = max_val ? max_val : 65535; break;
            }
        }
        if (strncmp(line, "    KEY (0001):", 14) == 0) {
            char* p = line + 14;
            while (*p == ' ') p++;
            while (num_keys < 16) {
                while (*p == ' ') p++;
                if (*p == '\0' || *p == '\n') break;
                unsigned int k;
                if (sscanf(p, "%x", &k) == 1) {
                    int found = 0;
                    for (int i = 0; i < num_keys; i++) { if (g_physical_params.keys[i] == (int)k) { found = 1; break; } }
                    if (!found && k != 0) g_physical_params.keys[num_keys++] = (int)k;
                    p += 4;
                    while (*p == ' ') p++;
                } else break;
            }
        }
    }
    pclose(fp);
    g_physical_params.num_keys = num_keys;
    LOGD("parse_physical_abs_params: device=%s keys=%d X=[%d,%d] Y=[%d,%d] SLOT=[%d,%d]",
         g_touch_device, num_keys,
         g_physical_params.abs_x_min, g_physical_params.abs_x_max,
         g_physical_params.abs_y_min, g_physical_params.abs_y_max,
         g_physical_params.slot_min, g_physical_params.slot_max);
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
    e.type = type; e.code = code; e.value = value;
    write(fd, &e, sizeof(e));
}

static inline void sync(int fd) {
    ev(fd, EV_SYN, SYN_REPORT, 0);
}

// =========================================================================
// Send complete frame: physical slots + virtual slot.
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
// Direct event reader — opens physical device, EVIOCGRAB, reads raw events
// =========================================================================
static void* direct_reader(void* arg) {
    // Open physical device
    grab_fd = open(g_touch_device, O_RDONLY);
    if (grab_fd < 0) {
        LOGE("direct_reader: open %s failed errno=%d", g_touch_device, errno);
        reader_running = 0;
        return NULL;
    }

    // EVIOCGRAB — exclusive access, InputReader won't see these events
    if (ioctl(grab_fd, EVIOCGRAB, 1) < 0) {
        LOGE("direct_reader: EVIOCGRAB failed errno=%d", errno);
        close(grab_fd);
        grab_fd = -1;
        reader_running = 0;
        return NULL;
    }
    LOGD("direct_reader: opened fd=%d EVIOCGRAB success on %s", grab_fd, g_touch_device);

    // Per-slot accumulators
    int cur_slot = 0;
    int slot_tid[MAX_SLOTS] = {0};
    int slot_x[MAX_SLOTS] = {0};
    int slot_y[MAX_SLOTS] = {0};
    int slot_has_tid[MAX_SLOTS] = {0};
    int slot_moved[MAX_SLOTS] = {0};

    struct input_event ev;
    while (reader_running) {
        ssize_t n = read(grab_fd, &ev, sizeof(ev));
        if (n < 0) {
            if (errno == EINTR) continue;
            LOGE("direct_reader: read error errno=%d", errno);
            break;
        }
        if (n != sizeof(ev)) continue;

        switch (ev.type) {
        case EV_ABS:
            switch (ev.code) {
            case ABS_MT_SLOT:
                cur_slot = ev.value;
                break;
            case ABS_MT_TRACKING_ID:
                if (cur_slot >= 0 && cur_slot < MAX_SLOTS) {
                    slot_tid[cur_slot] = ev.value;
                    slot_has_tid[cur_slot] = 1;
                }
                break;
            case ABS_MT_POSITION_X:
                if (cur_slot >= 0 && cur_slot < MAX_SLOTS) {
                    slot_x[cur_slot] = ev.value;
                    slot_moved[cur_slot] = 1;
                }
                break;
            case ABS_MT_POSITION_Y:
                if (cur_slot >= 0 && cur_slot < MAX_SLOTS) {
                    if (!slot_moved[cur_slot]) {
                        LOGD("direct_reader: slot=%d Y=%d", cur_slot, ev.value);
                    }
                    slot_y[cur_slot] = ev.value;
                    slot_moved[cur_slot] = 1;
                }
                break;
            case ABS_MT_PRESSURE:
                // just skip
                break;
            case ABS_MT_TOOL_TYPE:
                break;
            }
            break;

        case EV_SYN:
            if (ev.code == SYN_REPORT) {
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

                int need_frame = virtual_active;
                if (!need_frame) {
                    for (int i = 0; i < MAX_SLOTS; i++) {
                        // Forward if any physical slot is active OR uinput needs a lift
                        if (real_slots[i].active || uinput_slot_active[i]) {
                            need_frame = 1;
                            break;
                        }
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

    LOGD("direct_reader: stopped, closing grab_fd=%d", grab_fd);
    if (grab_fd >= 0) {
        ioctl(grab_fd, EVIOCGRAB, 0);
        close(grab_fd);
        grab_fd = -1;
    }
    reader_running = 0;
    return NULL;
}

// =========================================================================
// Start / stop reader
// =========================================================================

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_startGeteventListenerNative(JNIEnv *env, jobject thiz) {
    if (reader_running) { LOGD("reader already running"); return; }

    detect_touch_device();

    reader_running = 1;
    if (pthread_create(&reader_thread, NULL, direct_reader, NULL) != 0) {
        LOGE("pthread_create failed");
        reader_running = 0;
        return;
    }
    LOGD("direct reader started on %s", g_touch_device);
}

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_stopGeteventListenerNative(JNIEnv *env, jobject thiz) {
    if (!reader_running) return;
    reader_running = 0;

    // Close grab_fd to unblock the reader thread
    if (grab_fd >= 0) {
        ioctl(grab_fd, EVIOCGRAB, 0);
        close(grab_fd);
        grab_fd = -1;
    }

    pthread_join(reader_thread, NULL);
    LOGD("direct reader stopped");
}

// =========================================================================
// Virtual touch injection
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
    if (uinput_fd >= 0) {
        LOGD("openUinputNative: closing existing fd=%d", uinput_fd);
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
        uinput_fd = -1;
    }

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
    ioctl(uinput_fd, UI_SET_KEYBIT, 0x3e);
    ioctl(uinput_fd, UI_SET_KEYBIT, 0x145);
    ioctl(uinput_fd, UI_SET_KEYBIT, 0x14a);
    ioctl(uinput_fd, UI_SET_ABSBIT, 0x21);
    ioctl(uinput_fd, UI_SET_ABSBIT, 0x2f);
    ioctl(uinput_fd, UI_SET_ABSBIT, 0x30);
    ioctl(uinput_fd, UI_SET_ABSBIT, 0x32);
    ioctl(uinput_fd, UI_SET_ABSBIT, 0x35);
    ioctl(uinput_fd, UI_SET_ABSBIT, 0x36);
    ioctl(uinput_fd, UI_SET_ABSBIT, 0x37);
    ioctl(uinput_fd, UI_SET_ABSBIT, 0x39);
    ioctl(uinput_fd, UI_SET_ABSBIT, 0x3a);
    ioctl(uinput_fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

    set_abs_range(uinput_fd, 0x21, 0, 1000000);
    set_abs_range(uinput_fd, 0x2f, 0, 9);
    set_abs_range(uinput_fd, 0x30, 0, 255);
    set_abs_range(uinput_fd, 0x32, 0, 0);
    set_abs_range(uinput_fd, 0x35, 0, 21199);
    set_abs_range(uinput_fd, 0x36, 0, 29999);
    set_abs_range(uinput_fd, 0x37, 0, 0);
    set_abs_range(uinput_fd, 0x39, 0, 65535);
    set_abs_range(uinput_fd, 0x3a, 0, 0);

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
    LOGD("closeUinputNative: start, uinput_fd=%d, reader_running=%d", uinput_fd, reader_running);
    if (reader_running) {
        reader_running = 0;
        if (grab_fd >= 0) {
            ioctl(grab_fd, EVIOCGRAB, 0);
            close(grab_fd);
            grab_fd = -1;
        }
        pthread_join(reader_thread, NULL);
    }

    if (uinput_fd >= 0) {
        LOGD("closeUinputNative: destroying uinput device");
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
        uinput_fd = -1;
    }

    virtual_active = 0;
    memset(uinput_slot_active, 0, sizeof(uinput_slot_active));
    LOGD("closeUinputNative: done");
}

// =========================================================================
// JNI send functions
// =========================================================================

JNIEXPORT jboolean JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_uinputSendDown(JNIEnv *env, jobject thiz, jint fd, jint x, jint y, jint pointerId) {
    if (uinput_fd < 0) return JNI_FALSE;
    int dev_x, dev_y;
    if (g_landscape_start) {
        dev_x = (g_screen_h - y) * g_dev_abs_max_x / g_screen_h;
        dev_y = (x * g_dev_abs_max_y) / g_screen_w;
    } else {
        dev_x = (y * g_dev_abs_max_x) / g_screen_h;
        dev_y = (x * g_dev_abs_max_y) / g_screen_w;
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
        dev_x = (y * g_dev_abs_max_x) / g_screen_h;
        dev_y = (x * g_dev_abs_max_y) / g_screen_w;
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
