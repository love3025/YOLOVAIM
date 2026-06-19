/*
 * root_daemon.cpp — Standalone root daemon for uinput touch injection.
 * Runs under `su`, communicates via stdin/stdout text protocol.
 * Reuses the same uinput logic as uinput_inject.cpp.
 *
 * Protocol:
 *   Commands (stdin, one per line):
 *     SET_RESOLUTION <screenW> <screenH>
 *     SET_DEVICE_RESOLUTION <devW> <devH>
 *     SET_ORIENTATION <1|0>          (1=landscape, 0=portrait)
 *     OPEN_UINPUT
 *     CLOSE_UINPUT
 *     START_GETEVENT
 *     STOP_GETEVENT
 *     DOWN <x> <y>
 *     MOVE <x> <y>
 *     UP
 *     TRIGGER_DOWN <x> <y>
 *     TRIGGER_UP
 *     SET_TRIGGER_ZONE <l> <t> <r> <b>
 *     IS_FINGER_IN_ZONE
 *     KEEP_ALIVE
 *     DESTROY
 *
 *   Responses (stdout, one per line):
 *     OK
 *     OK:<value>
 *     ERR:<message>
 *
 *   All debug/log output goes to stderr only.
 */

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
#include <signal.h>

#define LOG_TAG "RootDaemon"
#define LOGD(...) do { fprintf(stderr, "D/" LOG_TAG ": " __VA_ARGS__); fputc('\n', stderr); } while(0)
#define LOGE(...) do { fprintf(stderr, "E/" LOG_TAG ": " __VA_ARGS__); fputc('\n', stderr); } while(0)

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

#define MAX_SLOTS 15

static int g_virtual_slot = -1;
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

static pthread_t reader_thread;
static volatile int reader_running = 0;
static int grab_fd = -1;

static int g_dev_abs_max_x = 21199;
static int g_dev_abs_max_y = 29999;
static int g_screen_w = 2120;
static int g_screen_h = 3000;
static int g_landscape_start = 1;

static volatile int g_running = 1;

// =========================================================================
// Core uinput helpers (same as uinput_inject.cpp)
// =========================================================================

static void detect_touch_device() {
    FILE* fp = popen("/system/bin/getevent -p 2>&1", "r");
    if (!fp) { LOGE("detect_touch_device: popen failed"); return; }

    char line[256];
    char current_path[256] = "/dev/input/event0";
    int has_pos_x = 0;
    int has_pos_y = 0;
    int is_virtual = 0;
    int found_max_x = 0, found_max_y = 0;

    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, "add device") && strstr(line, "/dev/input/event")) {
            if (has_pos_x && has_pos_y && !is_virtual) {
                strncpy(g_touch_device, current_path, sizeof(g_touch_device) - 1);
                g_touch_device[sizeof(g_touch_device) - 1] = '\0';
                if (found_max_x > 0) g_dev_abs_max_x = found_max_x;
                if (found_max_y > 0) g_dev_abs_max_y = found_max_y;
                g_touch_device_found = 1;
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
        if (strstr(line, "name:") && strstr(line, "Aimbot")) {
            is_virtual = 1;
        }
        if (!is_virtual && strstr(line, "0035")) {
            has_pos_x = 1;
            int val;
            if (sscanf(line, "%*x%*[^m]min %*d, max %d", &val) == 1 && val > 0) found_max_x = val;
        }
        if (!is_virtual && strstr(line, "0036")) {
            has_pos_y = 1;
            int val;
            if (sscanf(line, "%*x%*[^m]min %*d, max %d", &val) == 1 && val > 0) found_max_y = val;
        }
    }
    if (has_pos_x && has_pos_y && !is_virtual) {
        strncpy(g_touch_device, current_path, sizeof(g_touch_device) - 1);
        g_touch_device[sizeof(g_touch_device) - 1] = '\0';
        if (found_max_x > 0) g_dev_abs_max_x = found_max_x;
        if (found_max_y > 0) g_dev_abs_max_y = found_max_y;
        g_touch_device_found = 1;
    }
    pclose(fp);
    LOGD("Touch device: %s abs=%dx%d", g_touch_device, g_dev_abs_max_x, g_dev_abs_max_y);
}

static void set_abs_range(int fd, int axis, int min, int max) {
    struct uinput_abs_setup_manual abs_setup;
    memset(&abs_setup, 0, sizeof(abs_setup));
    abs_setup.code = axis;
    abs_setup.absinfo.min = min;
    abs_setup.absinfo.max = max;
    ioctl(fd, UI_ABS_SETUP, &abs_setup);
}

static inline void ev_send(int fd, int type, int code, int value) {
    struct input_event e;
    memset(&e, 0, sizeof(e));
    e.type = type; e.code = code; e.value = value;
    write(fd, &e, sizeof(e));
}

static inline void sync_send(int fd) {
    ev_send(fd, EV_SYN, SYN_REPORT, 0);
}

static void send_frame_locked() {
    int any_physical = 0;

    for (int i = 0; i < MAX_SLOTS; i++) {
        ev_send(uinput_fd, EV_ABS, ABS_MT_SLOT, i);

        if (real_slots[i].active) {
            if (i == g_virtual_slot) {
                ev_send(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
                uinput_slot_active[g_virtual_slot] = 0;
                g_virtual_slot = -1;
            }
            ev_send(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, real_slots[i].tracking_id);
            ev_send(uinput_fd, EV_ABS, ABS_MT_POSITION_X, real_slots[i].x);
            ev_send(uinput_fd, EV_ABS, ABS_MT_POSITION_Y, real_slots[i].y);
            ev_send(uinput_fd, EV_ABS, ABS_MT_TOOL_TYPE, 1);
            ev_send(uinput_fd, EV_ABS, ABS_MT_PRESSURE, 50);
            uinput_slot_active[i] = 1;
            any_physical = 1;
        } else if (uinput_slot_active[i]) {
            ev_send(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
            uinput_slot_active[i] = 0;
        }
    }

    if (virtual_active) {
        if (g_virtual_slot < 0) {
            for (int i = 0; i < MAX_SLOTS; i++) {
                if (i == g_trigger_slot) continue;
                if (!real_slots[i].active && !uinput_slot_active[i]) {
                    g_virtual_slot = i;
                    break;
                }
            }
        }
        if (g_virtual_slot >= 0) {
            ev_send(uinput_fd, EV_ABS, ABS_MT_SLOT, g_virtual_slot);
            ev_send(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, VIRTUAL_TRACKING);
            ev_send(uinput_fd, EV_ABS, ABS_MT_POSITION_X, virtual_x);
            ev_send(uinput_fd, EV_ABS, ABS_MT_POSITION_Y, virtual_y);
            ev_send(uinput_fd, EV_ABS, ABS_MT_TOOL_TYPE, 1);
            ev_send(uinput_fd, EV_ABS, ABS_MT_PRESSURE, 50);
            uinput_slot_active[g_virtual_slot] = 1;
        }
    } else {
        if (g_virtual_slot >= 0) {
            ev_send(uinput_fd, EV_ABS, ABS_MT_SLOT, g_virtual_slot);
            ev_send(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
            uinput_slot_active[g_virtual_slot] = 0;
            g_virtual_slot = -1;
        }
    }

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
            ev_send(uinput_fd, EV_ABS, ABS_MT_SLOT, g_trigger_slot);
            ev_send(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, TRIGGER_TRACKING);
            ev_send(uinput_fd, EV_ABS, ABS_MT_POSITION_X, trigger_x);
            ev_send(uinput_fd, EV_ABS, ABS_MT_POSITION_Y, trigger_y);
            ev_send(uinput_fd, EV_ABS, ABS_MT_TOOL_TYPE, 1);
            ev_send(uinput_fd, EV_ABS, ABS_MT_PRESSURE, 50);
            uinput_slot_active[g_trigger_slot] = 1;
        }
    } else {
        if (g_trigger_slot >= 0) {
            ev_send(uinput_fd, EV_ABS, ABS_MT_SLOT, g_trigger_slot);
            ev_send(uinput_fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
            uinput_slot_active[g_trigger_slot] = 0;
            g_trigger_slot = -1;
        }
    }

    int touch_down = any_physical || virtual_active || trigger_active;
    ev_send(uinput_fd, EV_KEY, BTN_TOUCH, touch_down ? 1 : 0);
    ev_send(uinput_fd, EV_KEY, BTN_TOOL_FINGER, touch_down ? 1 : 0);

    if (any_physical) {
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (i != g_virtual_slot && i != g_trigger_slot && real_slots[i].active) {
                ev_send(uinput_fd, EV_ABS, ABS_X, real_slots[i].x);
                ev_send(uinput_fd, EV_ABS, ABS_Y, real_slots[i].y);
                break;
            }
        }
    } else if (virtual_active) {
        ev_send(uinput_fd, EV_ABS, ABS_X, virtual_x);
        ev_send(uinput_fd, EV_ABS, ABS_Y, virtual_y);
    } else if (trigger_active) {
        ev_send(uinput_fd, EV_ABS, ABS_X, trigger_x);
        ev_send(uinput_fd, EV_ABS, ABS_Y, trigger_y);
    }

    sync_send(uinput_fd);
}

static void inject_virtual_touch(int dev_x, int dev_y, int is_down) {
    if (uinput_fd < 0) return;
    pthread_mutex_lock(&uinput_mutex);
    virtual_x = dev_x;
    virtual_y = dev_y;
    virtual_active = is_down;
    send_frame_locked();
    pthread_mutex_unlock(&uinput_mutex);
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

static void screen_to_device(int sx, int sy, int* dev_x, int* dev_y) {
    if (g_landscape_start) {
        *dev_x = (g_screen_h - sy) * g_dev_abs_max_x / g_screen_h;
        *dev_y = (sx * g_dev_abs_max_y) / g_screen_w;
    } else {
        *dev_x = (sx * g_dev_abs_max_x) / g_screen_w;
        *dev_y = (sy * g_dev_abs_max_y) / g_screen_h;
    }
}

// =========================================================================
// Direct event reader (EVIOCGRAB)
// =========================================================================

static void* direct_reader(void* arg) {
    grab_fd = open(g_touch_device, O_RDONLY);
    if (grab_fd < 0) {
        LOGE("direct_reader: open %s failed errno=%d", g_touch_device, errno);
        reader_running = 0;
        return NULL;
    }

    if (ioctl(grab_fd, EVIOCGRAB, 1) < 0) {
        LOGE("direct_reader: EVIOCGRAB failed errno=%d", errno);
        close(grab_fd);
        grab_fd = -1;
        reader_running = 0;
        return NULL;
    }
    LOGD("direct_reader: opened fd=%d EVIOCGRAB success on %s", grab_fd, g_touch_device);

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
                    slot_y[cur_slot] = ev.value;
                    slot_moved[cur_slot] = 1;
                }
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
                        if (real_slots[i].active || uinput_slot_active[i]) {
                            need_frame = 1;
                            break;
                        }
                    }
                }

                if (uinput_fd >= 0 && need_frame) {
                    send_frame_locked();
                }

                // Check physical finger in trigger zone
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
                pthread_mutex_unlock(&uinput_mutex);
            }
            break;
        }
    }

    LOGD("direct_reader: stopped");
    if (grab_fd >= 0) {
        ioctl(grab_fd, EVIOCGRAB, 0);
        close(grab_fd);
        grab_fd = -1;
    }
    reader_running = 0;
    return NULL;
}

static void start_reader() {
    if (reader_running) return;
    if (!g_touch_device_found) detect_touch_device();
    reader_running = 1;
    if (pthread_create(&reader_thread, NULL, direct_reader, NULL) != 0) {
        LOGE("pthread_create failed");
        reader_running = 0;
    }
}

static void stop_reader() {
    if (!reader_running) return;
    reader_running = 0;
    if (grab_fd >= 0) {
        ioctl(grab_fd, EVIOCGRAB, 0);
        close(grab_fd);
        grab_fd = -1;
    }
    pthread_join(reader_thread, NULL);
}

// =========================================================================
// Uinput open / close
// =========================================================================

static int open_uinput() {
    if (uinput_fd >= 0) {
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
        uinput_fd = -1;
    }

    if (!g_touch_device_found) detect_touch_device();
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

static void close_uinput() {
    stop_reader();
    if (uinput_fd >= 0) {
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
        uinput_fd = -1;
    }
    virtual_active = 0;
    g_virtual_slot = -1;
    g_trigger_slot = -1;
    memset(uinput_slot_active, 0, sizeof(uinput_slot_active));
}

// =========================================================================
// Command handler
// =========================================================================

static void handle_command(const char* cmd) {
    // Trim trailing newline
    char buf[1024];
    strncpy(buf, cmd, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = '\0';
    char* nl = strchr(buf, '\n');
    if (nl) *nl = '\0';
    char* cr = strchr(buf, '\r');
    if (cr) *cr = '\0';

    if (strncmp(buf, "SET_RESOLUTION ", 15) == 0) {
        int w, h;
        if (sscanf(buf + 15, "%d %d", &w, &h) == 2 && w > 0 && h > 0) {
            g_screen_w = w;
            g_screen_h = h;
            puts("OK");
        } else {
            puts("ERR:invalid args");
        }
    }
    else if (strncmp(buf, "SET_DEVICE_RESOLUTION ", 22) == 0) {
        int w, h;
        if (sscanf(buf + 22, "%d %d", &w, &h) == 2 && w > 0 && h > 0) {
            g_dev_abs_max_x = w;
            g_dev_abs_max_y = h;
            puts("OK");
        } else {
            puts("ERR:invalid args");
        }
    }
    else if (strncmp(buf, "SET_ORIENTATION ", 16) == 0) {
        g_landscape_start = atoi(buf + 16);
        puts("OK");
    }
    else if (strcmp(buf, "OPEN_UINPUT") == 0) {
        int fd = open_uinput();
        if (fd >= 0) {
            printf("OK:%d\n", fd);
        } else {
            puts("ERR:open failed");
        }
    }
    else if (strcmp(buf, "CLOSE_UINPUT") == 0) {
        close_uinput();
        puts("OK");
    }
    else if (strcmp(buf, "START_GETEVENT") == 0) {
        start_reader();
        puts("OK");
    }
    else if (strcmp(buf, "STOP_GETEVENT") == 0) {
        stop_reader();
        puts("OK");
    }
    else if (strncmp(buf, "DOWN ", 5) == 0) {
        int x, y;
        if (sscanf(buf + 5, "%d %d", &x, &y) == 2) {
            int dx, dy;
            screen_to_device(x, y, &dx, &dy);
            inject_virtual_touch(dx, dy, 1);
            puts("OK");
        } else {
            puts("ERR:invalid args");
        }
    }
    else if (strncmp(buf, "MOVE ", 5) == 0) {
        int x, y;
        if (sscanf(buf + 5, "%d %d", &x, &y) == 2) {
            int dx, dy;
            screen_to_device(x, y, &dx, &dy);
            inject_virtual_touch(dx, dy, 1);
            puts("OK");
        } else {
            puts("ERR:invalid args");
        }
    }
    else if (strcmp(buf, "UP") == 0) {
        inject_virtual_touch(0, 0, 0);
        puts("OK");
    }
    else if (strncmp(buf, "TRIGGER_DOWN ", 13) == 0) {
        int x, y;
        if (sscanf(buf + 13, "%d %d", &x, &y) == 2) {
            int dx, dy;
            screen_to_device(x, y, &dx, &dy);
            inject_trigger_touch(dx, dy, 1);
            puts("OK");
        } else {
            puts("ERR:invalid args");
        }
    }
    else if (strcmp(buf, "TRIGGER_UP") == 0) {
        inject_trigger_touch(0, 0, 0);
        puts("OK");
    }
    else if (strncmp(buf, "SET_TRIGGER_ZONE ", 17) == 0) {
        int l, t, r, b;
        if (sscanf(buf + 17, "%d %d %d %d", &l, &t, &r, &b) == 4) {
            g_trigger_zone_l = l;
            g_trigger_zone_t = t;
            g_trigger_zone_r = r;
            g_trigger_zone_b = b;
            puts("OK");
        } else {
            puts("ERR:invalid args");
        }
    }
    else if (strcmp(buf, "IS_FINGER_IN_ZONE") == 0) {
        printf("OK:%d\n", g_finger_in_zone ? 1 : 0);
    }
    else if (strncmp(buf, "SET_FIRE_ZONE ", 14) == 0) {
        int l, t, r, b;
        if (sscanf(buf + 14, "%d %d %d %d", &l, &t, &r, &b) == 4) {
            g_fire_zone_l = l;
            g_fire_zone_t = t;
            g_fire_zone_r = r;
            g_fire_zone_b = b;
            puts("OK");
        } else {
            puts("ERR:invalid args");
        }
    }
    else if (strcmp(buf, "IS_FINGER_IN_FIRE_ZONE") == 0) {
        printf("OK:%d\n", g_finger_in_fire_zone ? 1 : 0);
    }
    else if (strcmp(buf, "KEEP_ALIVE") == 0) {
        puts("OK");
    }
    else if (strcmp(buf, "DESTROY") == 0) {
        close_uinput();
        puts("OK");
        g_running = 0;
    }
    else if (strlen(buf) == 0) {
        // Ignore empty lines
    }
    else {
        fprintf(stderr, "Unknown command: %s\n", buf);
        puts("ERR:unknown command");
    }
    fflush(stdout);
}

// =========================================================================
// Main
// =========================================================================

int main() {
    // Ignore SIGPIPE — Java side may close stdin at any time
    signal(SIGPIPE, SIG_IGN);

    // Line-buffer stdout for immediate response flushing
    setvbuf(stdout, NULL, _IOLBF, 0);

    // Signal ready
    puts("READY");
    fflush(stdout);

    char line[1024];
    while (g_running) {
        if (fgets(line, sizeof(line), stdin) == NULL) {
            // stdin closed (Java process died)
            LOGD("stdin closed, exiting");
            break;
        }
        handle_command(line);
    }

    close_uinput();
    return 0;
}
