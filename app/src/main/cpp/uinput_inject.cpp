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

#define MAX_SLOTS        15

// Virtual fingers use dynamic slot allocation from 0
static int g_virtual_slot = -1;  // -1 = unallocated, starts from 0 when allocated
static int g_trigger_slot = -1;

static const int VIRTUAL_TRACKING = 1000;
static const int TRIGGER_TRACKING = 2000;

static int g_touch_device_found = 0;
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

static volatile int trigger_active = 0;
static int trigger_x = 0;
static int trigger_y = 0;

// Trigger zone for physical finger detection (screen coords)
static int g_trigger_zone_l = 0;
static int g_trigger_zone_t = 0;
static int g_trigger_zone_r = 0;
static int g_trigger_zone_b = 0;
static volatile int g_finger_in_zone = 0;

// Fire zone for recoil control finger detection (screen coords)
static int g_fire_zone_l = 0;
static int g_fire_zone_t = 0;
static int g_fire_zone_r = 0;
static int g_fire_zone_b = 0;
static volatile int g_finger_in_fire_zone = 0;

// Joystick zone for auto-stop detection (screen coords)
static int g_joystick_zone_l = 0;
static int g_joystick_zone_t = 0;
static int g_joystick_zone_r = 0;
static int g_joystick_zone_b = 0;
static volatile int g_finger_in_joystick_zone = 0;

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
    if (devW <= 0 || devH <= 0) {
        LOGE("Invalid device resolution: %dx%d", devW, devH);
        return;
    }
    g_dev_abs_max_x = devW;
    g_dev_abs_max_y = devH;
}

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_setScreenResolution(JNIEnv *env, jclass cls, jint screenW, jint screenH) {
    if (screenW <= 0 || screenH <= 0) {
        LOGE("Invalid screen resolution: %dx%d", screenW, screenH);
        return;
    }
    g_screen_w = screenW;
    g_screen_h = screenH;
}

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_setLandscapeStart(JNIEnv *env, jclass cls, jint isLandscape) {
    g_landscape_start = isLandscape;
}

static void detect_touch_device() {
    FILE* fp = popen("/system/bin/getevent -p 2>&1", "r");
    if (!fp) { LOGE("detect_touch_device: popen failed"); return; }

    char line[256];
    char current_path[256] = "/dev/input/event0";
    int has_pos_x = 0;
    int has_pos_y = 0;
    int is_virtual = 0;  // flag when current device has "Aimbot" in its name
    int found_max_x = 0, found_max_y = 0;

    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, "add device") && strstr(line, "/dev/input/event")) {
            if (has_pos_x && has_pos_y) {
                strncpy(g_touch_device, current_path, sizeof(g_touch_device) - 1);
                g_touch_device[sizeof(g_touch_device) - 1] = '\0';
                if (found_max_x > 0) g_dev_abs_max_x = found_max_x;
                if (found_max_y > 0) g_dev_abs_max_y = found_max_y;
                pclose(fp);
                LOGD("Detected touch device: %s abs=%dx%d", g_touch_device, g_dev_abs_max_x, g_dev_abs_max_y);
                return;
            }
            char* p = strstr(line, "/dev/input/event");
            if (p) {
                strncpy(current_path, p, sizeof(current_path) - 1);
                current_path[sizeof(current_path) - 1] = '\0';
                char* end = current_path + strlen(current_path) - 1;
                while (end > current_path && (*end == ' ' || *end == '\n' || *end == '\r')) *end-- = '\0';
            }
            has_pos_x = 0;
            has_pos_y = 0;
            is_virtual = 0;
            found_max_x = 0;
            found_max_y = 0;
        }
        // Track device name — skip our own virtual device
        if (strstr(line, "name:") && strstr(line, "Aimbot")) {
            is_virtual = 1;
        }
        // Detect by ABS_MT_POSITION_X (0x0035) and ABS_MT_POSITION_Y (0x0036)
        // Format example: "0035  : value 0, min 0, max 143999, fuzz 0, flat 0, resolution 0"
        if (!is_virtual && strstr(line, "0035")) {
            has_pos_x = 1;
            int val;
            // line format: "0035  : value 0, min 0, max 143999, fuzz 0, flat 0, resolution 0"
            if (sscanf(line, "%*x%*[^m]min %*d, max %d", &val) == 1 && val > 0) found_max_x = val;
        }
        if (!is_virtual && strstr(line, "0036")) {
            has_pos_y = 1;
            int val;
            if (sscanf(line, "%*x%*[^m]min %*d, max %d", &val) == 1 && val > 0) found_max_y = val;
        }
    }
    if (has_pos_x && has_pos_y) {
        strncpy(g_touch_device, current_path, sizeof(g_touch_device) - 1);
        g_touch_device[sizeof(g_touch_device) - 1] = '\0';
        if (found_max_x > 0) g_dev_abs_max_x = found_max_x;
        if (found_max_y > 0) g_dev_abs_max_y = found_max_y;
    }
    pclose(fp);
    LOGD("Touch device: %s abs=%dx%d", g_touch_device, g_dev_abs_max_x, g_dev_abs_max_y);
}


/* parse_physical_abs_params removed — EVIOCGRAB reader handles device directly */

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
// Send complete frame: physical slots + virtual fingers.
// All slots require explicit ABS_MT_SLOT before any slot data.
// =========================================================================
static void send_frame_locked() {
    int any_physical = 0;

    // Step 1: Sync physical slots — release lifted fingers, report active ones
    for (int i = 0; i < MAX_SLOTS; i++) {
        ev(uinput_fd, EV_ABS, ABS_MT_SLOT, i);

        if (real_slots[i].active) {
            // Physical finger active at this slot — if virtual also uses this slot, release virtual
            if (i == g_virtual_slot) {
                // Virtual finger gets bumped by physical, release it
                ev(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
                uinput_slot_active[g_virtual_slot] = 0;
                g_virtual_slot = -1;
                LOGD("send_frame: physical slot %d bumped virtual", i);
            }
            ev(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, real_slots[i].tracking_id);
            ev(uinput_fd, EV_ABS, ABS_MT_POSITION_X, real_slots[i].x);
            ev(uinput_fd, EV_ABS, ABS_MT_POSITION_Y, real_slots[i].y);
            ev(uinput_fd, EV_ABS, ABS_MT_TOOL_TYPE, 1);
            ev(uinput_fd, EV_ABS, ABS_MT_PRESSURE, 50);
            uinput_slot_active[i] = 1;
            any_physical = 1;
        } else if (uinput_slot_active[i]) {
            ev(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
            uinput_slot_active[i] = 0;
        }
    }

    // Step 2: Dynamic slot allocation for virtual finger
    if (virtual_active) {
        if (g_virtual_slot < 0) {
            // Find first free slot
            for (int i = 0; i < MAX_SLOTS; i++) {
                if (i == g_trigger_slot) continue;
                if (!real_slots[i].active && !uinput_slot_active[i]) {
                    g_virtual_slot = i;
                    break;
                }
            }
        }
        if (g_virtual_slot >= 0) {
            ev(uinput_fd, EV_ABS, ABS_MT_SLOT, g_virtual_slot);
            ev(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, VIRTUAL_TRACKING);
            ev(uinput_fd, EV_ABS, ABS_MT_POSITION_X, virtual_x);
            ev(uinput_fd, EV_ABS, ABS_MT_POSITION_Y, virtual_y);
            ev(uinput_fd, EV_ABS, ABS_MT_TOOL_TYPE, 1);
            ev(uinput_fd, EV_ABS, ABS_MT_PRESSURE, 50);
            uinput_slot_active[g_virtual_slot] = 1;
        }
    } else {
        // Release virtual slot
        if (g_virtual_slot >= 0) {
            ev(uinput_fd, EV_ABS, ABS_MT_SLOT, g_virtual_slot);
            ev(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
            uinput_slot_active[g_virtual_slot] = 0;
            g_virtual_slot = -1;
        }
    }

    // Step 3: Dynamic slot allocation for trigger finger
    if (trigger_active) {
        if (g_trigger_slot < 0) {
            for (int i = 0; i < MAX_SLOTS; i++) {
                if (i == g_virtual_slot) continue;
                if (!real_slots[i].active && !uinput_slot_active[i]) {
                    g_trigger_slot = i;
                    break;
                }
            }
        }
        if (g_trigger_slot >= 0) {
            ev(uinput_fd, EV_ABS, ABS_MT_SLOT, g_trigger_slot);
            ev(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, TRIGGER_TRACKING);
            ev(uinput_fd, EV_ABS, ABS_MT_POSITION_X, trigger_x);
            ev(uinput_fd, EV_ABS, ABS_MT_POSITION_Y, trigger_y);
            ev(uinput_fd, EV_ABS, ABS_MT_TOOL_TYPE, 1);
            ev(uinput_fd, EV_ABS, ABS_MT_PRESSURE, 50);
            uinput_slot_active[g_trigger_slot] = 1;
        }
    } else {
        if (g_trigger_slot >= 0) {
            ev(uinput_fd, EV_ABS, ABS_MT_SLOT, g_trigger_slot);
            ev(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
            uinput_slot_active[g_trigger_slot] = 0;
            g_trigger_slot = -1;
        }
    }

    int touch_down = any_physical || virtual_active || trigger_active;
    ev(uinput_fd, EV_KEY, BTN_TOUCH, touch_down ? 1 : 0);
    ev(uinput_fd, EV_KEY, BTN_TOOL_FINGER, touch_down ? 1 : 0);

    if (any_physical) {
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (i != g_virtual_slot && i != g_trigger_slot && real_slots[i].active) {
                ev(uinput_fd, EV_ABS, ABS_X, real_slots[i].x);
                ev(uinput_fd, EV_ABS, ABS_Y, real_slots[i].y);
                break;
            }
        }
    } else if (virtual_active) {
        ev(uinput_fd, EV_ABS, ABS_X, virtual_x);
        ev(uinput_fd, EV_ABS, ABS_Y, virtual_y);
    } else if (trigger_active) {
        ev(uinput_fd, EV_ABS, ABS_X, trigger_x);
        ev(uinput_fd, EV_ABS, ABS_Y, trigger_y);
    }

    sync(uinput_fd);
}

static void inject_trigger_touch(int dev_x, int dev_y, int is_down) {
    if (uinput_fd < 0) return;
    pthread_mutex_lock(&uinput_mutex);
    trigger_x = dev_x;
    trigger_y = dev_y;
    trigger_active = is_down;
    send_frame_locked();
    pthread_mutex_unlock(&uinput_mutex);
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
    int event_count = 0;

    struct input_event ev;
    while (reader_running) {
        ssize_t n = read(grab_fd, &ev, sizeof(ev));
        if (n < 0) {
            if (errno == EINTR) continue;
            LOGE("direct_reader: read error errno=%d", errno);
            break;
        }
        if (n != sizeof(ev)) continue;

        event_count++;

        switch (ev.type) {
        case EV_ABS:
            switch (ev.code) {
            case ABS_MT_SLOT:
                cur_slot = ev.value;
                LOGD("phys: slot=%d", cur_slot);
                break;
            case ABS_MT_TRACKING_ID:
                if (cur_slot >= 0 && cur_slot < MAX_SLOTS) {
                    slot_tid[cur_slot] = ev.value;
                    slot_has_tid[cur_slot] = 1;
                    LOGD("phys: slot=%d tid=%s", cur_slot,
                         ev.value == -1 ? "UP" : "DOWN");
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
                    slot_y[cur_slot] = ev.value;
                    slot_moved[cur_slot] = 1;
                }
                break;
            case ABS_MT_PRESSURE:
                LOGD("phys: slot=%d pressure=%d", cur_slot, ev.value);
                break;
            case ABS_MT_TOOL_TYPE:
                LOGD("phys: slot=%d tool_type=%d", cur_slot, ev.value);
                break;
            case ABS_MT_TOUCH_MAJOR:
                LOGD("phys: slot=%d touch_major=%d", cur_slot, ev.value);
                break;
            case ABS_MT_WIDTH_MAJOR:
                LOGD("phys: slot=%d width_major=%d", cur_slot, ev.value);
                break;
            default:
                LOGD("phys: slot=%d code=0x%02x value=%d", cur_slot, ev.code, ev.value);
                break;
            }
            break;

        case EV_KEY:
            if (ev.code == BTN_TOUCH) {
                LOGD("phys: BTN_TOUCH=%d", ev.value);
            } else if (ev.code == BTN_TOOL_FINGER) {
                LOGD("phys: BTN_TOOL_FINGER=%d", ev.value);
            } else {
                LOGD("phys: KEY code=%d value=%d", ev.code, ev.value);
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

                // Log all active physical slots on SYN_REPORT
                char summary[256];
                int pos = 0;
                pos += snprintf(summary + pos, sizeof(summary) - pos, "phys: report #%d slots:", event_count);
                for (int i = 0; i < MAX_SLOTS; i++) {
                    if (i == g_virtual_slot || i == g_trigger_slot) continue;
                    if (real_slots[i].active) {
                        pos += snprintf(summary + pos, sizeof(summary) - pos, " [%d](%d,%d)",
                                        i, real_slots[i].x, real_slots[i].y);
                    } else if (uinput_slot_active[i]) {
                        pos += snprintf(summary + pos, sizeof(summary) - pos, " [%d]LIFT", i);
                    }
                }
                if (pos > 22) LOGD("%s", summary);

                if (uinput_fd >= 0 && need_frame) {
                    send_frame_locked();
                }

                // Check physical finger in trigger zone (after real_slots are updated)
                if (g_trigger_zone_l < g_trigger_zone_r && g_trigger_zone_t < g_trigger_zone_b) {
                    g_finger_in_zone = 0;
                    for (int i = 0; i < MAX_SLOTS; i++) {
                        if (i == g_virtual_slot || i == g_trigger_slot) continue;
                        if (real_slots[i].active) {
                            int dev_x = real_slots[i].x;
                            int dev_y = real_slots[i].y;
                            int sx, sy;
                            if (g_landscape_start) {
                                sx = dev_y * g_screen_w / g_dev_abs_max_y;
                                sy = g_screen_h - (dev_x * g_screen_h / g_dev_abs_max_x);
                            } else {
                                sx = dev_x * g_screen_w / g_dev_abs_max_x;
                                sy = dev_y * g_screen_h / g_dev_abs_max_y;
                            }
                            if (sx >= g_trigger_zone_l && sx <= g_trigger_zone_r &&
                                sy >= g_trigger_zone_t && sy <= g_trigger_zone_b) {
                                g_finger_in_zone = 1;
                                break;
                            }
                        }
                    }
                }

                // Check physical finger in fire zone (for recoil control)
                if (g_fire_zone_l < g_fire_zone_r && g_fire_zone_t < g_fire_zone_b) {
                    g_finger_in_fire_zone = 0;
                    for (int i = 0; i < MAX_SLOTS; i++) {
                        if (i == g_virtual_slot || i == g_trigger_slot) continue;
                        if (real_slots[i].active) {
                            int dev_x = real_slots[i].x;
                            int dev_y = real_slots[i].y;
                            int sx, sy;
                            if (g_landscape_start) {
                                sx = dev_y * g_screen_w / g_dev_abs_max_y;
                                sy = g_screen_h - (dev_x * g_screen_h / g_dev_abs_max_x);
                            } else {
                                sx = dev_x * g_screen_w / g_dev_abs_max_x;
                                sy = dev_y * g_screen_h / g_dev_abs_max_y;
                            }
                            if (sx >= g_fire_zone_l && sx <= g_fire_zone_r &&
                                sy >= g_fire_zone_t && sy <= g_fire_zone_b) {
                                g_finger_in_fire_zone = 1;
                                break;
                            }
                        }
                    }
                }

                // Check physical finger in joystick zone
                if (g_joystick_zone_l < g_joystick_zone_r && g_joystick_zone_t < g_joystick_zone_b) {
                    g_finger_in_joystick_zone = 0;
                    for (int i = 0; i < MAX_SLOTS; i++) {
                        if (i == g_virtual_slot || i == g_trigger_slot) continue;
                        if (real_slots[i].active) {
                            int dev_x = real_slots[i].x;
                            int dev_y = real_slots[i].y;
                            int sx, sy;
                            if (g_landscape_start) {
                                sx = dev_y * g_screen_w / g_dev_abs_max_y;
                                sy = g_screen_h - (dev_x * g_screen_h / g_dev_abs_max_x);
                            } else {
                                sx = dev_x * g_screen_w / g_dev_abs_max_x;
                                sy = dev_y * g_screen_h / g_dev_abs_max_y;
                            }
                            if (sx >= g_joystick_zone_l && sx <= g_joystick_zone_r &&
                                sy >= g_joystick_zone_t && sy <= g_joystick_zone_b) {
                                g_finger_in_joystick_zone = 1;
                                break;
                            }
                        }
                    }
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

    // Detect physical touch device first so uinput gets the correct ABS ranges
    detect_touch_device();
    LOGD("Using touch device ABS: %dx%d", g_dev_abs_max_x, g_dev_abs_max_y);

    uinput_fd = open("/dev/uinput", O_RDWR | O_NONBLOCK);
    if (uinput_fd < 0) { LOGE("Cannot open /dev/uinput"); return -1; }

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
    set_abs_range(uinput_fd, 0x2f, 0, 14);
    set_abs_range(uinput_fd, 0x30, 0, 255);
    set_abs_range(uinput_fd, 0x32, 0, 0);
    set_abs_range(uinput_fd, 0x35, 0, g_dev_abs_max_x);
    set_abs_range(uinput_fd, 0x36, 0, g_dev_abs_max_y);
    set_abs_range(uinput_fd, 0x37, 0, 0);
    set_abs_range(uinput_fd, 0x39, 0, 65535);
    set_abs_range(uinput_fd, 0x3a, 0, 0);

    if (ioctl(uinput_fd, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE failed"); close(uinput_fd); uinput_fd = -1; return -1;
    }

    memset(real_slots, 0, sizeof(real_slots));
    memset(uinput_slot_active, 0, sizeof(uinput_slot_active));
    virtual_active = 0;
    g_virtual_slot = -1;
    g_trigger_slot = -1;
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
    g_virtual_slot = -1;
    g_trigger_slot = -1;
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
        // Landscape: display rotated 90° relative to touch panel
        // input system will rotate device X→screen Y, device Y→screen X
        dev_x = (g_screen_h - y) * g_dev_abs_max_x / g_screen_h;
        dev_y = (x * g_dev_abs_max_y) / g_screen_w;
    } else {
        // Portrait: display and touch panel aligned, direct mapping
        dev_x = (x * g_dev_abs_max_x) / g_screen_w;
        dev_y = (y * g_dev_abs_max_y) / g_screen_h;
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
        dev_x = (x * g_dev_abs_max_x) / g_screen_w;
        dev_y = (y * g_dev_abs_max_y) / g_screen_h;
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

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_uinputTriggerDown(JNIEnv *env, jobject thiz, jint x, jint y) {
    if (uinput_fd < 0) return;
    int dev_x, dev_y;
    if (g_landscape_start) {
        dev_x = (g_screen_h - y) * g_dev_abs_max_x / g_screen_h;
        dev_y = (x * g_dev_abs_max_y) / g_screen_w;
    } else {
        dev_x = (x * g_dev_abs_max_x) / g_screen_w;
        dev_y = (y * g_dev_abs_max_y) / g_screen_h;
    }
    inject_trigger_touch(dev_x, dev_y, 1);
}

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_uinputTriggerUp(JNIEnv *env, jobject thiz) {
    if (uinput_fd < 0) return;
    inject_trigger_touch(0, 0, 0);
}

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_nativeSetTriggerZone(JNIEnv *env, jclass clazz,
    jint left, jint top, jint right, jint bottom) {
    g_trigger_zone_l = left;
    g_trigger_zone_t = top;
    g_trigger_zone_r = right;
    g_trigger_zone_b = bottom;
    LOGD("nativeSetTriggerZone: (%d,%d)-(%d,%d)", left, top, right, bottom);
}

JNIEXPORT jboolean JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_nativeIsFingerInTriggerZone(JNIEnv *env, jclass clazz) {
    return g_finger_in_zone ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_nativeSetFireZone(JNIEnv *env, jclass clazz,
    jint left, jint top, jint right, jint bottom) {
    g_fire_zone_l = left;
    g_fire_zone_t = top;
    g_fire_zone_r = right;
    g_fire_zone_b = bottom;
    LOGD("nativeSetFireZone: (%d,%d)-(%d,%d)", left, top, right, bottom);
}

JNIEXPORT jboolean JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_nativeIsFingerInFireZone(JNIEnv *env, jclass clazz) {
    return g_finger_in_fire_zone ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_nativeSetJoystickZone(JNIEnv *env, jclass clazz,
    jint left, jint top, jint right, jint bottom) {
    g_joystick_zone_l = left;
    g_joystick_zone_t = top;
    g_joystick_zone_r = right;
    g_joystick_zone_b = bottom;
    LOGD("nativeSetJoystickZone: (%d,%d)-(%d,%d)", left, top, right, bottom);
}

JNIEXPORT jboolean JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_nativeIsFingerInJoystickZone(JNIEnv *env, jclass clazz) {
    return g_finger_in_joystick_zone ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_team_maodie_aimbot_RemoteInjectorService_nativeLiftJoystickFinger(JNIEnv *env, jclass clazz) {
    if (uinput_fd < 0) return JNI_FALSE;
    pthread_mutex_lock(&uinput_mutex);

    int lifted = 0;
    for (int i = 0; i < MAX_SLOTS; i++) {
        if (i == g_virtual_slot || i == g_trigger_slot) continue;
        if (real_slots[i].active) {
            int dev_x = real_slots[i].x;
            int dev_y = real_slots[i].y;
            int sx, sy;
            if (g_landscape_start) {
                sx = dev_y * g_screen_w / g_dev_abs_max_y;
                sy = g_screen_h - (dev_x * g_screen_h / g_dev_abs_max_x);
            } else {
                sx = dev_x * g_screen_w / g_dev_abs_max_x;
                sy = dev_y * g_screen_h / g_dev_abs_max_y;
            }
            if (sx >= g_joystick_zone_l && sx <= g_joystick_zone_r &&
                sy >= g_joystick_zone_t && sy <= g_joystick_zone_b) {
                // Lift this finger by setting tracking_id to -1
                ev(uinput_fd, EV_ABS, ABS_MT_SLOT, i);
                ev(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
                real_slots[i].active = 0;
                uinput_slot_active[i] = 0;
                lifted = 1;
                LOGD("liftJoystickFinger: lifted slot %d at (%d,%d)", i, sx, sy);
            }
        }
    }

    if (lifted) {
        // Check if any physical fingers remain
        int any_physical = 0;
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (real_slots[i].active) { any_physical = 1; break; }
        }
        int touch_down = any_physical || virtual_active || trigger_active;
        ev(uinput_fd, EV_KEY, BTN_TOUCH, touch_down ? 1 : 0);
        ev(uinput_fd, EV_KEY, BTN_TOOL_FINGER, touch_down ? 1 : 0);
        sync(uinput_fd);
    }

    pthread_mutex_unlock(&uinput_mutex);
    return lifted ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
