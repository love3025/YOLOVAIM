// inputmgr_core.cpp — InputManager evdev reader
// Reads physical touch from evdev, tracks pointers in screen coordinates.
// Matches reference implementation: TouchMergerUserService.kt exactly.
// Supports Protocol A (no slot) and Protocol B (with slot).

#include "inputmgr_core.h"
#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <array>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstring>
#include <mutex>
#include <vector>

#ifdef ANDROID
#include <android/log.h>
#define LOG_TAG "InputMgrCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) do {} while(0)
#define LOGE(...) do {} while(0)
#endif

// ─── Constants ───────────────────────────────────────────────────────

static constexpr int MAX_DEVICES = 5;
static constexpr int UNGRAB = 0;
static constexpr int GRAB = 1;
static constexpr int EVDEV_EVENT_SIZE = 24;  // sizeof(struct input_event) on arm64

// ─── Data structures (matches reference) ─────────────────────────────

struct Zone {
    int l = 0, t = 0, r = 0, b = 0;
    volatile int finger_inside = 0;
};

// ─── Global state ────────────────────────────────────────────────────

static std::vector<int> g_fds;  // evdev file descriptors
static std::mutex g_mutex;
static bool g_initialized = false;
static bool g_grabbed = false;

// Screen params
static int g_screen_w = 0, g_screen_h = 0;
static int g_display_rotation = 0;  // 0, 1, 2, 3

// Device params
static int g_evdev_max_x = 0;
static int g_evdev_max_y = 0;
static bool g_has_slot_support = false;

// Device ID for MotionEvent (from real touch device)
static int g_device_id = 0;

// Protocol B state (matches reference: slotTrackId[], slotX[], slotY[])
static int g_slot_track_id[INPUTMGR_MAX_SLOTS];
static int g_slot_x[INPUTMGR_MAX_SLOTS];
static int g_slot_y[INPUTMGR_MAX_SLOTS];
static int g_cur_slot = 0;
static int g_next_synthetic_track_id = INPUTMGR_SYNTHETIC_ID_START;
static int g_synthetic_track_id_log_count = 0;

// Protocol A state (matches reference: protoATid[], protoAX[], protoAY[])
static int g_proto_a_tid[INPUTMGR_MAX_SLOTS];
static int g_proto_a_x[INPUTMGR_MAX_SLOTS];
static int g_proto_a_y[INPUTMGR_MAX_SLOTS];
static int g_proto_a_count = 0;
static int g_proto_a_cur_tid = -1;
static int g_proto_a_cur_x = 0;
static int g_proto_a_cur_y = 0;
static bool g_proto_a_has_pos = false;
static bool g_proto_a_has_mt_report = false;
static int g_proto_a_touch_major = 0;

// Physical pointers (output)
static std::vector<PhysicalPointer> g_physical_pointers;

// Cached finger state (matches reference: cachedFingerResult, cachedFingerTime, cachedFingerGraceUntil)
static int g_cached_finger_result[1] = {-1};
static long long g_cached_finger_time = 0;
static long long g_cached_finger_grace_until = 0;
static constexpr long long PHYSICAL_CLEAR_GRACE_WHILE_SIM_DOWN_MS = 180;
static constexpr long long PHYSICAL_CLEAR_DEBOUNCE_MS = 350;

// Detection zones
static Zone g_trigger_zone;
static Zone g_fire_zone;
static Zone g_joystick_zone;

// Dirty flag
static volatile bool g_dirty = false;

// ─── Helpers ─────────────────────────────────────────────────────────

static long long currentTimeMillis() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static bool pointInZone(const Zone& z, int sx, int sy) {
    return z.l < z.r && z.t < z.b &&
           sx >= z.l && sx <= z.r && sy >= z.t && sy <= z.b;
}

// Rotate coordinates based on display rotation (matches reference: rotateToDisplay)
static void rotateToDisplay(float sx, float sy, float* lx, float* ly) {
    switch (g_display_rotation) {
        case 1:
            *lx = sy;
            *ly = g_screen_w - sx;
            break;
        case 2:
            *lx = g_screen_w - sx;
            *ly = g_screen_h - sy;
            break;
        case 3:
            *lx = g_screen_h - sy;
            *ly = sx;
            break;
        default:  // case 0
            *lx = sx;
            *ly = sy;
            break;
    }
}

// Convert evdev coords to screen coords with rotation
static void devToScreen(int rawX, int rawY, float* lx, float* ly) {
    float sx = g_evdev_max_x > 0 ? (float)(rawX * g_screen_w) / g_evdev_max_x : (float)rawX;
    float sy = g_evdev_max_y > 0 ? (float)(rawY * g_screen_h) / g_evdev_max_y : (float)rawY;
    rotateToDisplay(sx, sy, lx, ly);
}

// ─── Device scanning ────────────────────────────────────────────────

static bool checkDeviceIsTouch(int fd) {
    uint8_t* bits = nullptr;
    ssize_t bitsSize = 0;
    int res = 0;
    bool hasX = false, hasY = false;
    input_absinfo abs{};
    while (true) {
        res = ioctl(fd, EVIOCGBIT(EV_ABS, bitsSize), bits);
        if (res < bitsSize) break;
        bitsSize = res + 16;
        bits = static_cast<uint8_t*>(realloc(bits, bitsSize * 2));
    }
    for (int j = 0; j < res; ++j) {
        for (int k = 0; k < 8; ++k) {
            int code = j * 8 + k;
            if ((bits[j] & (1 << k)) && ioctl(fd, EVIOCGABS(code), &abs) == 0) {
                if (code == ABS_MT_POSITION_X) hasX = true;
                if (code == ABS_MT_POSITION_Y) hasY = true;
            }
        }
    }
    free(bits);
    return hasX && hasY;
}

static bool checkHasSlotSupport(int fd) {
    input_absinfo abs{};
    return ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &abs) == 0 && abs.maximum > 0;
}

// Get device name via ioctl
static void getDeviceName(int fd, char* buf, int bufSize) {
    if (ioctl(fd, EVIOCGNAME(bufSize), buf) < 0)
        buf[0] = '\0';
}

// Enumerate touch device paths via `getevent -p` (Shizuku-safe).
// Parses the same fields touch_core.cpp uses (hasSlot && hasX && hasY)
// and reports each detected path once. Out arrays must hold at least maxResults entries.
static int enumerateTouchDevicesViaGetevent(char paths[][128], int maxResults) {
    FILE* fp = popen("/system/bin/getevent -p 2>&1", "r");
    if (!fp) {
        LOGE("getevent: popen failed errno=%d", errno);
        return 0;
    }

    char currentPath[128] = "";
    bool hasSlot = false, hasX = false, hasY = false;
    int found = 0;
    int lineCount = 0;

    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        lineCount++;
        char pathBuf[128];
        // "add device N: /dev/input/eventX"
        if (sscanf(line, " add device %*d: %127s", pathBuf) == 1) {
            // New device header — commit previous one if it qualified.
            if (currentPath[0] != '\0' && hasSlot && hasX && hasY) {
                if (found < maxResults) {
                    strncpy(paths[found], currentPath, 127);
                    paths[found][127] = '\0';
                    found++;
                }
            }
            strncpy(currentPath, pathBuf, sizeof(currentPath) - 1);
            currentPath[sizeof(currentPath) - 1] = '\0';
            hasSlot = false;
            hasX = false;
            hasY = false;
            continue;
        }

        if (currentPath[0] == '\0') continue;

        // " 0035: value 0, min 0, max 1079"  (ABS_MT_POSITION_X) — note "value" prefix
        int code = -1, minV = 0, maxV = 0;
        if (sscanf(line, " %x : value %*d, min %d, max %d", &code, &minV, &maxV) == 3 ||
            sscanf(line, " %x : min %d, max %d", &code, &minV, &maxV) == 3) {
            if (code == ABS_MT_SLOT)         hasSlot = true;
            else if (code == ABS_MT_POSITION_X) hasX = true;
            else if (code == ABS_MT_POSITION_Y) hasY = true;
        }
    }

    LOGD("getevent: scanned %d lines, %d touch device(s) qualified", lineCount, found);

    // Commit last device
    if (currentPath[0] != '\0' && hasSlot && hasX && hasY) {
        if (found < maxResults) {
            strncpy(paths[found], currentPath, 127);
            paths[found][127] = '\0';
            found++;
        }
    }

    pclose(fp);
    return found;
}

// ─── Close (internal, must hold mutex) ──────────────────────────────

static void closeLocked() {
    if (!g_initialized) return;
    for (auto& fd : g_fds) {
        if (fd >= 0) {
            if (g_grabbed) ioctl(fd, EVIOCGRAB, UNGRAB);
            close(fd);
            fd = -1;
        }
    }
    g_fds.clear();
    g_grabbed = false;
    g_initialized = false;
}

// ─── Pointer state management (matches reference) ────────────────────

static void clearSlotState() {
    for (int i = 0; i < INPUTMGR_MAX_SLOTS; i++) {
        g_slot_track_id[i] = -1;
        g_slot_x[i] = 0;
        g_slot_y[i] = 0;
    }
    g_cur_slot = 0;
    g_next_synthetic_track_id = INPUTMGR_SYNTHETIC_ID_START;
}

static void clearProtoAState() {
    g_proto_a_count = 0;
    g_proto_a_cur_tid = -1;
    g_proto_a_has_pos = false;
    g_proto_a_has_mt_report = false;
    g_proto_a_touch_major = 0;
}

// 开火区上升沿计数 —— 与 touch_core 同样的理由：evdev 事件是 120-240Hz，
// 应用侧只能按推理帧率查电平，短于帧间隔的点击会被整帧漏掉。在这里数，
// 半自动连点才能按「打了几枪」而不是「按了多久」计量。
// 调用方均已持有 g_mutex；g_fire_taps 要被 IPC 线程 consume，故用原子。
static std::atomic<int> g_fire_taps{0};
static int g_fire_prev = 0;

static inline void countFireEdgeLocked() {
    int now = 0;
    for (auto& ptr : g_physical_pointers) {
        if (pointInZone(g_fire_zone, (int)ptr.x, (int)ptr.y)) { now = 1; break; }
    }
    if (now && !g_fire_prev) g_fire_taps.fetch_add(1, std::memory_order_relaxed);
    g_fire_prev = now;
}

// Update physical pointers from Protocol B slots (matches reference: updatePhysicalPointers)
static void updatePhysicalPointers() {
    std::lock_guard<std::mutex> guard(g_mutex);

    bool had_active = !g_physical_pointers.empty();
    g_physical_pointers.clear();

    for (int i = 0; i < INPUTMGR_MAX_SLOTS; i++) {
        if (g_slot_track_id[i] < 0) continue;

        int pointer_id = g_slot_track_id[i];
        int rawX = g_slot_x[i], rawY = g_slot_y[i];
        float lx, ly;
        devToScreen(rawX, rawY, &lx, &ly);

        PhysicalPointer ptr;
        ptr.id = pointer_id;
        ptr.x = lx;
        ptr.y = ly;
        ptr.pressure = 1.0f;
        g_physical_pointers.push_back(ptr);
    }

    if (g_physical_pointers.empty()) {
        // Keep cached finger state if sim is down (matches reference: maybeKeepCachedFingerStateOnPhysicalClearLocked)
        long long now = currentTimeMillis();
        if (had_active) {
            LOGD("PHYS_CLEAR[protoB] grabbed=%d", g_grabbed);
        }
    } else {
        // Update cached finger state
        g_cached_finger_result[0] = (int)g_physical_pointers.size();
        g_cached_finger_time = currentTimeMillis();

        if (g_synthetic_track_id_log_count < 50) {
            // Log full transform chain: raw -> scaled (portrait) -> rotated (display)
            int rx = g_slot_x[0], ry = g_slot_y[0];
            float sx = g_evdev_max_x > 0 ? (float)(rx * g_screen_w) / g_evdev_max_x : (float)rx;
            float sy = g_evdev_max_y > 0 ? (float)(ry * g_screen_h) / g_evdev_max_y : (float)ry;
            LOGD("mapDiag: raw=(%d,%d) max=(%d,%d) -> scaled=(%.1f,%.1f) rot=%d -> (%.0f,%.0f)",
                 rx, ry, g_evdev_max_x, g_evdev_max_y,
                 sx, sy, g_display_rotation,
                 g_physical_pointers[0].x, g_physical_pointers[0].y);
            g_synthetic_track_id_log_count++;
        }
    }

    countFireEdgeLocked();
    g_dirty = true;
}

// Update physical pointers from Protocol A (matches reference: updatePhysicalPointersProtoA)
static void updatePhysicalPointersProtoA() {
    std::lock_guard<std::mutex> guard(g_mutex);

    g_physical_pointers.clear();

    for (int i = 0; i < g_proto_a_count; i++) {
        int tid = g_proto_a_tid[i];
        float lx, ly;
        devToScreen(g_proto_a_x[i], g_proto_a_y[i], &lx, &ly);

        PhysicalPointer ptr;
        ptr.id = tid;
        ptr.x = lx;
        ptr.y = ly;
        ptr.pressure = 1.0f;
        g_physical_pointers.push_back(ptr);
    }

    // Update cached finger state
    g_cached_finger_result[0] = (int)g_physical_pointers.size();
    g_cached_finger_time = currentTimeMillis();

    countFireEdgeLocked();
    g_dirty = true;
}

// Check if any pointers are active (matches reference: hasActivePointers)
static bool hasActivePointers() {
    std::lock_guard<std::mutex> guard(g_mutex);
    return !g_physical_pointers.empty();
}

// ─── Process evdev events (matches reference: evdevReadLoop) ─────────

static int g_evdev_diag_count = 0;

static int processEvdevEvents(int fd) {
    struct input_event batch[64];
    ssize_t n = read(fd, batch, sizeof(batch));
    if (n <= 0) {
        if (g_evdev_diag_count < 20) {
            LOGD("evdevRead: read() returned %zd errno=%d fd=%d grabbed=%d",
                 n, errno, fd, g_grabbed);
            g_evdev_diag_count++;
        }
        return 0;
    }
    if (n % sizeof(struct input_event) != 0) {
        LOGE("evdevRead: partial read %zd bytes (not multiple of %zu)", n, sizeof(struct input_event));
        return 0;
    }

    size_t count = n / sizeof(struct input_event);
    int result = 0;

    for (size_t j = 0; j < count; j++) {
        auto& ie = batch[j];
        int type = ie.type;
        int code = ie.code;
        int value = ie.value;

        if (g_has_slot_support) {
            // Protocol B
            if (type == EV_ABS) {
                switch (code) {
                    case ABS_MT_SLOT:
                        g_cur_slot = value < 0 ? 0 : (value >= INPUTMGR_MAX_SLOTS ? INPUTMGR_MAX_SLOTS - 1 : value);
                        break;
                    case ABS_MT_POSITION_X:
                        g_slot_x[g_cur_slot] = value;
                        // slotHeal: assign synthetic ID if slot has no valid ID yet
                        if (g_slot_track_id[g_cur_slot] < 0 && g_grabbed) {
                            g_slot_track_id[g_cur_slot] = g_next_synthetic_track_id++;
                            if (g_synthetic_track_id_log_count < 30) {
                                LOGD("slotHeal[X]: slot=%d resurrected via POSITION_X with synthTid=%d",
                                     g_cur_slot, g_slot_track_id[g_cur_slot]);
                                g_synthetic_track_id_log_count++;
                            }
                        }
                        break;
                    case ABS_MT_POSITION_Y:
                        g_slot_y[g_cur_slot] = value;
                        // slotHeal: assign synthetic ID if slot has no valid ID yet
                        if (g_slot_track_id[g_cur_slot] < 0 && g_grabbed) {
                            g_slot_track_id[g_cur_slot] = g_next_synthetic_track_id++;
                            if (g_synthetic_track_id_log_count < 30) {
                                LOGD("slotHeal[Y]: slot=%d resurrected via POSITION_Y with synthTid=%d",
                                     g_cur_slot, g_slot_track_id[g_cur_slot]);
                                g_synthetic_track_id_log_count++;
                            }
                        }
                        break;
                    case ABS_MT_TRACKING_ID:
                        g_slot_track_id[g_cur_slot] = value;
                        if (value < 0) {
                            LOGD("TRACKING_ID=-1 slot=%d prevTid=%d", g_cur_slot, g_slot_track_id[g_cur_slot]);
                        }
                        break;
                }
            } else if (type == EV_SYN && code == SYN_REPORT) {
                // Update pointers on SYN_REPORT
                updatePhysicalPointers();
                result = 1;
            }
        } else {
            // Protocol A
            if (type == EV_ABS) {
                switch (code) {
                    case ABS_MT_POSITION_X:
                        g_proto_a_cur_x = value;
                        g_proto_a_has_pos = true;
                        break;
                    case ABS_MT_POSITION_Y:
                        g_proto_a_cur_y = value;
                        g_proto_a_has_pos = true;
                        break;
                    case ABS_MT_TRACKING_ID:
                        g_proto_a_cur_tid = value;
                        break;
                    case ABS_MT_TOUCH_MAJOR:
                        g_proto_a_touch_major = value;
                        break;
                }
            } else if (type == EV_SYN && code == SYN_MT_REPORT) {
                // SYN_MT_REPORT: end of one finger's data
                if (g_proto_a_has_pos && g_proto_a_count < INPUTMGR_MAX_SLOTS) {
                    int tid = g_proto_a_cur_tid >= 0 ? g_proto_a_cur_tid : (8 + g_proto_a_count);
                    g_proto_a_tid[g_proto_a_count] = tid;
                    g_proto_a_x[g_proto_a_count] = g_proto_a_cur_x;
                    g_proto_a_y[g_proto_a_count] = g_proto_a_cur_y;
                    g_proto_a_count++;
                }
                g_proto_a_has_mt_report = true;
                g_proto_a_cur_tid = -1;
                g_proto_a_has_pos = false;
                g_proto_a_touch_major = 0;
            } else if (type == EV_SYN && code == SYN_REPORT) {
                // SYN_REPORT: end of all fingers
                if (!g_proto_a_has_mt_report && g_proto_a_has_pos) {
                    // No SYN_MT_REPORT received, treat as single finger
                    int tid = g_proto_a_cur_tid >= 0 ? g_proto_a_cur_tid : 8;
                    g_proto_a_tid[0] = tid;
                    g_proto_a_x[0] = g_proto_a_cur_x;
                    g_proto_a_y[0] = g_proto_a_cur_y;
                    g_proto_a_count = 1;
                }

                if (g_proto_a_count > 0) {
                    updatePhysicalPointersProtoA();
                } else if (g_proto_a_has_mt_report) {
                    // Empty report: all fingers lifted
                    std::lock_guard<std::mutex> guard(g_mutex);
                    bool had_active = !g_physical_pointers.empty();
                    g_physical_pointers.clear();
                    if (had_active) {
                        LOGD("PHYS_CLEAR[protoA] grabbed=%d", g_grabbed);
                    }
                    countFireEdgeLocked();
                    g_dirty = true;
                }

                result = 1;
                g_proto_a_count = 0;
                g_proto_a_has_mt_report = false;
                g_proto_a_has_pos = false;
            } else if (type == EV_KEY && code == BTN_TOUCH && value == 0) {
                // Button up: clear all pointers
                std::lock_guard<std::mutex> guard(g_mutex);
                bool had_active = !g_physical_pointers.empty();
                g_physical_pointers.clear();
                if (had_active) {
                    LOGD("PHYS_CLEAR[btn-touch-up] grabbed=%d", g_grabbed);
                }
                countFireEdgeLocked();
                g_dirty = true;
                g_proto_a_count = 0;
                g_proto_a_has_pos = false;
            }
        }
    }

    return result;
}

// ═════════════════════════════════════════════════════════════════════
//  Public API
// ═════════════════════════════════════════════════════════════════════

bool inputmgr_init(int screenW, int screenH) {
    if (screenW <= 0 || screenH <= 0) {
        LOGE("inputmgr_init: invalid screen size %dx%d", screenW, screenH);
        return false;
    }

    std::lock_guard<std::mutex> guard(g_mutex);
    closeLocked();

    g_screen_w = screenW;
    g_screen_h = screenH;

    // Clear state
    clearSlotState();
    clearProtoAState();
    g_physical_pointers.clear();
    g_cached_finger_result[0] = -1;
    g_cached_finger_time = 0;
    g_cached_finger_grace_until = 0;

    DIR* dir = opendir("/dev/input/");
    if (!dir) {
        LOGE("open /dev/input failed, falling back to getevent");
    }

    // Use getevent to enumerate touch device paths (Shizuku-safe).
    char touchPaths[MAX_DEVICES][128];
    int touchPathCount = enumerateTouchDevicesViaGetevent(touchPaths, MAX_DEVICES);
    if (touchPathCount == 0) {
        if (dir) closedir(dir);
        LOGE("no touch device found via getevent");
        return false;
    }
    LOGD("getevent found %d touch device(s)", touchPathCount);
    if (dir) closedir(dir);

    int best_fd = -1;
    int best_max_x = 0, best_max_y = 0;
    bool best_has_slot = false;
    char best_name[128] = {};

    // Open each detected touch device with O_RDONLY (works in both Root and Shizuku).
    for (int i = 0; i < touchPathCount; i++) {
        const char* path = touchPaths[i];
        int fd = open(path, O_RDONLY);
        if (fd < 0) {
            LOGE("open %s failed errno=%d", path, errno);
            continue;
        }

        struct input_absinfo absInfoX{}, absInfoY{};
        if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &absInfoX) != 0 ||
            ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &absInfoY) != 0) {
            LOGE("EVIOCGABS on %s failed errno=%d", path, errno);
            close(fd);
            continue;
        }

        int max_x = absInfoX.maximum;
        int max_y = absInfoY.maximum;
        bool has_slot = checkHasSlotSupport(fd);

        g_fds.push_back(fd);

        // Use first device as reference
        if (g_fds.size() == 1) {
            g_evdev_max_x = max_x;
            g_evdev_max_y = max_y;
            g_has_slot_support = has_slot;
            best_fd = fd;
            best_max_x = max_x;
            best_max_y = max_y;
            best_has_slot = has_slot;

            // Get device ID from input_id
            input_id iid{};
            if (ioctl(fd, EVIOCGID, &iid) == 0) {
                g_device_id = (iid.bustype << 16) | (iid.vendor & 0xFFFF);
            }
            char name[128];
            getDeviceName(fd, name, sizeof(name));
            strncpy(best_name, name, sizeof(best_name));
            LOGD("ref device: %s max=%d,%d id=0x%x hasSlot=%d",
                 path, max_x, max_y, g_device_id, has_slot);
        }

        LOGD("touch device: %s max=%d,%d hasSlot=%d",
             path, max_x, max_y, has_slot);
    }

    if (g_fds.empty()) {
        LOGE("no valid touch device");
        return false;
    }

    // Try to release any stale grab from previous process (e.g. old Shizuku service)
    for (size_t i = 0; i < g_fds.size(); i++) {
        int ret = ioctl(g_fds[i], EVIOCGRAB, UNGRAB);
        LOGD("init ungrab fd[%zu]=%d ret=%d errno=%d", i, g_fds[i], ret, errno);
    }

    g_initialized = true;
    LOGD("inputmgr_init ok: %zu devices, max=%d,%d, hasSlot=%d, rotation=%d",
         g_fds.size(), g_evdev_max_x, g_evdev_max_y, g_has_slot_support, g_display_rotation);
    return true;
}

void inputmgr_close(void) {
    std::lock_guard<std::mutex> guard(g_mutex);
    closeLocked();
}

bool inputmgr_is_initialized(void) { return g_initialized; }

void inputmgr_grab(void) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!g_initialized || g_grabbed) return;
    int grabbed_count = 0;
    for (size_t i = 0; i < g_fds.size(); i++) {
        int fd = g_fds[i];
        if (fd >= 0) {
            // First try to release any stale grab (from previous process crash)
            int ungrab_ret = ioctl(fd, EVIOCGRAB, UNGRAB);
            int ret = ioctl(fd, EVIOCGRAB, GRAB);
            if (ret == 0) {
                grabbed_count++;
                LOGD("GRAB ok on fd[%zu]=%d (ungrab=%d)", i, fd, ungrab_ret);
            } else {
                LOGE("GRAB FAIL on fd[%zu]=%d ret=%d errno=%d (ungrab=%d)", i, fd, ret, errno, ungrab_ret);
            }
        }
    }
    g_grabbed = grabbed_count > 0;
    LOGD("GRAB result: %d/%zu devices grabbed", grabbed_count, g_fds.size());
}

void inputmgr_ungrab(void) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!g_initialized || !g_grabbed) return;
    for (auto& fd : g_fds) {
        if (fd >= 0)
            ioctl(fd, EVIOCGRAB, UNGRAB);
    }
    g_grabbed = false;
    LOGD("GRAB released");
}

bool inputmgr_is_grabbed(void) { return g_grabbed; }

static int g_poll_diag_count = 0;

int inputmgr_poll_and_update(int timeoutMs) {
    if (!g_initialized || g_fds.empty()) return -1;

    // Build pollfds
    std::vector<struct pollfd> pfds(g_fds.size());
    for (size_t i = 0; i < g_fds.size(); i++) {
        pfds[i].fd = g_fds[i];
        pfds[i].events = POLLIN;
        pfds[i].revents = 0;
    }

    int ret = poll(pfds.data(), pfds.size(), timeoutMs);
    if (ret < 0) {
        if (g_poll_diag_count < 20) {
            LOGD("poll: error errno=%d", errno);
            g_poll_diag_count++;
        }
        return -1;   // error
    }
    if (ret == 0) return 0;   // timeout

    int result = 0;

    // Process events from all ready fds
    for (size_t d = 0; d < g_fds.size(); d++) {
        if (!(pfds[d].revents & POLLIN)) continue;
        if (g_poll_diag_count < 20) {
            LOGD("poll: fd[%zu]=%d revents=0x%x grabbed=%d",
                 d, pfds[d].fd, pfds[d].revents, g_grabbed);
            g_poll_diag_count++;
        }
        int r = processEvdevEvents(g_fds[d]);
        if (r > 0) result = 1;
    }

    return result;
}

int inputmgr_read_pointers(PhysicalPointer* buf, int maxCount) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!g_initialized || !buf || maxCount <= 0) return 0;

    int count = 0;
    for (auto& ptr : g_physical_pointers) {
        if (count >= maxCount) break;
        buf[count++] = ptr;
    }
    return count;
}

int inputmgr_get_device_id(void) { return g_device_id; }
int inputmgr_get_max_x(void) { return g_evdev_max_x; }
int inputmgr_get_max_y(void) { return g_evdev_max_y; }
bool inputmgr_has_slot_support(void) { return g_has_slot_support; }

void inputmgr_set_screen_params(int w, int h, int rotation) {
    std::lock_guard<std::mutex> guard(g_mutex);
    g_screen_w = w;
    g_screen_h = h;
    g_display_rotation = rotation;
}

void inputmgr_set_trigger_zone(int l, int t, int r, int b)  { g_trigger_zone = {l, t, r, b, 0}; }
void inputmgr_set_fire_zone(int l, int t, int r, int b)     { g_fire_zone = {l, t, r, b, 0}; }
void inputmgr_set_joystick_zone(int l, int t, int r, int b) { g_joystick_zone = {l, t, r, b, 0}; }

bool inputmgr_is_finger_in_trigger_zone(void) {
    std::lock_guard<std::mutex> guard(g_mutex);
    for (auto& ptr : g_physical_pointers) {
        if (pointInZone(g_trigger_zone, (int)ptr.x, (int)ptr.y)) return true;
    }
    return false;
}

bool inputmgr_is_finger_in_fire_zone(void) {
    std::lock_guard<std::mutex> guard(g_mutex);
    for (auto& ptr : g_physical_pointers) {
        if (pointInZone(g_fire_zone, (int)ptr.x, (int)ptr.y)) return true;
    }
    return false;
}

// 取走自上次调用以来的点击次数并清零。
int inputmgr_consume_fire_taps(void) { return g_fire_taps.exchange(0, std::memory_order_relaxed); }

bool inputmgr_is_finger_in_joystick_zone(void) {
    std::lock_guard<std::mutex> guard(g_mutex);
    for (auto& ptr : g_physical_pointers) {
        if (pointInZone(g_joystick_zone, (int)ptr.x, (int)ptr.y)) return true;
    }
    return false;
}

bool inputmgr_lift_joystick_finger(void) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!g_initialized || g_fds.empty()) return false;

    bool lifted = false;
    for (int i = 0; i < INPUTMGR_MAX_SLOTS; i++) {
        if (g_slot_track_id[i] < 0) continue;

        float lx, ly;
        devToScreen(g_slot_x[i], g_slot_y[i], &lx, &ly);

        if (pointInZone(g_joystick_zone, (int)lx, (int)ly)) {
            g_slot_track_id[i] = -1;  // Mark as lifted
            lifted = true;
            LOGD("liftJoystickFinger: slot%d at (%.0f,%.0f)", i, lx, ly);
        }
    }

    // Rebuild physical pointers
    if (lifted) {
        g_physical_pointers.clear();
        for (int i = 0; i < INPUTMGR_MAX_SLOTS; i++) {
            if (g_slot_track_id[i] < 0) continue;

            float lx, ly;
            devToScreen(g_slot_x[i], g_slot_y[i], &lx, &ly);

            PhysicalPointer ptr;
            ptr.id = g_slot_track_id[i];
            ptr.x = lx;
            ptr.y = ly;
            ptr.pressure = 1.0f;
            g_physical_pointers.push_back(ptr);
        }
        g_dirty = true;
    }

    return lifted;
}
