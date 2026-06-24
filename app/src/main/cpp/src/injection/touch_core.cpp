// touch_core.cpp — Core touch injection logic
// Based on native_touch.cpp + reader threads from TouchHelperA
// Shared by JNI (Shizuku) and root_daemon (su)

#include "touch_core.h"
#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <ctime>
#include <mutex>
#include <vector>

#ifdef ANDROID
#include <android/log.h>
#define LOG_TAG "TouchCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) do { fprintf(stderr, "D/" LOG_TAG ": " __VA_ARGS__); fputc('\n', stderr); } while(0)
#define LOGE(...) do { fprintf(stderr, "E/" LOG_TAG ": " __VA_ARGS__); fputc('\n', stderr); } while(0)
#endif

// ─── Constants ───────────────────────────────────────────────────────

static constexpr int maxE = 5;
static constexpr int maxF = 10;
static constexpr int UNGRAB = 0;
static constexpr int GRAB = 1;

// ─── Data structures ────────────────────────────────────────────────

struct Vec2 {
    float x = 0.0f, y = 0.0f;
    Vec2() = default;
    Vec2(float px, float py) : x(px), y(py) {}
    Vec2 operator*(const Vec2& o) const { return {x * o.x, y * o.y}; }
};

struct TouchObj {
    Vec2 pos{};
    int id = 0;
    bool isDown = false;
};

struct Device {
    int fd = 0;
    char path[256] = "";
    float s2tx = 1.0f;
    float s2ty = 1.0f;
    input_absinfo absX{};
    input_absinfo absY{};
    TouchObj fingers[maxF]{};
};

struct Zone {
    int l = 0, t = 0, r = 0, b = 0;
    volatile int finger_inside = 0;
};

struct InputBuffer {
    input_event event[512]{};
};

// ─── Global state ────────────────────────────────────────────────────

static std::vector<Device> g_devices;
static std::array<std::array<bool, maxF>, maxE> g_uploadedFingerDown{};
static InputBuffer g_inputBuffer{};
static Vec2 g_touchScale{1.0f, 1.0f};
static Vec2 g_screenSize{};
static std::mutex g_mutex;
static int g_outputFd = 0;
static bool g_initialized = false;

// Screen params
static int g_screen_w = 0, g_screen_h = 0;
static bool g_landscape = true;

// Reader threads
static std::vector<pthread_t> g_reader_threads;
static volatile bool g_running = false;

// Detection zones
static Zone g_trigger_zone;
static Zone g_fire_zone;
static Zone g_joystick_zone;

// ─── Helpers ─────────────────────────────────────────────────────────

static void genRandomString(char* str, int len) {
    srand(static_cast<unsigned>(time(nullptr)) + len);
    for (int i = 0; i < len - 1; ++i) {
        int flag = rand() % 3;
        if (flag == 0)      str[i] = static_cast<char>('A' + rand() % 26);
        else if (flag == 1) str[i] = static_cast<char>('a' + rand() % 26);
        else                str[i] = static_cast<char>('0' + rand() % 10);
    }
    str[len - 1] = '\0';
}

static void pushEvent(int& count, unsigned short type, unsigned short code, int value) {
    if (count >= static_cast<int>(std::size(g_inputBuffer.event))) return;
    g_inputBuffer.event[count].type = type;
    g_inputBuffer.event[count].code = code;
    g_inputBuffer.event[count].value = value;
    ++count;
}

static bool pointInZone(const Zone& z, int sx, int sy) {
    return z.l < z.r && z.t < z.b &&
           sx >= z.l && sx <= z.r && sy >= z.t && sy <= z.b;
}

// Screen → portrait touch coords (rotation + scale)
static void screenToTouch(int sx, int sy, float& tx, float& ty) {
    float px = g_landscape ? static_cast<float>(g_screen_h - sy) : static_cast<float>(sx);
    float py = g_landscape ? static_cast<float>(sx) : static_cast<float>(sy);
    tx = px * g_touchScale.x;
    ty = py * g_touchScale.y;
}

// ─── Upload (from native_touch.cpp) ─────────────────────────────────

static void upload() {
    if (g_outputFd <= 0) return;
    int count = 0;
    int activeFingerCount = 0;
    bool hasActiveFinger = false;
    size_t deviceCount = std::min(g_devices.size(), static_cast<size_t>(maxE));

    for (size_t di = 0; di < deviceCount; ++di) {
        for (int fi = 0; fi < maxF; ++fi) {
            const TouchObj& finger = g_devices[di].fingers[fi];
            bool wasUploaded = g_uploadedFingerDown[di][fi];
            int slot = static_cast<int>(di * maxF + fi);

            if (finger.isDown) {
                hasActiveFinger = true;
                ++activeFingerCount;
                pushEvent(count, EV_ABS, ABS_MT_SLOT, slot);
                if (!wasUploaded)
                    pushEvent(count, EV_ABS, ABS_MT_TRACKING_ID, finger.id);
                pushEvent(count, EV_ABS, ABS_MT_POSITION_X, static_cast<int>(finger.pos.x));
                pushEvent(count, EV_ABS, ABS_MT_POSITION_Y, static_cast<int>(finger.pos.y));
                pushEvent(count, EV_ABS, ABS_X, static_cast<int>(finger.pos.x));
                pushEvent(count, EV_ABS, ABS_Y, static_cast<int>(finger.pos.y));
                g_uploadedFingerDown[di][fi] = true;
            } else if (wasUploaded) {
                pushEvent(count, EV_ABS, ABS_MT_SLOT, slot);
                pushEvent(count, EV_ABS, ABS_MT_TRACKING_ID, -1);
                g_uploadedFingerDown[di][fi] = false;
            }
        }
    }

    pushEvent(count, EV_KEY, BTN_TOUCH, hasActiveFinger ? 1 : 0);
    pushEvent(count, EV_KEY, BTN_TOOL_FINGER, activeFingerCount == 1 ? 1 : 0);
    pushEvent(count, EV_KEY, BTN_TOOL_DOUBLETAP, activeFingerCount == 2 ? 1 : 0);
    pushEvent(count, EV_KEY, BTN_TOOL_TRIPLETAP, activeFingerCount == 3 ? 1 : 0);
    pushEvent(count, EV_KEY, BTN_TOOL_QUADTAP, activeFingerCount == 4 ? 1 : 0);
    pushEvent(count, EV_KEY, BTN_TOOL_QUINTTAP, activeFingerCount >= 5 ? 1 : 0);
    pushEvent(count, EV_SYN, SYN_REPORT, 0);
    write(g_outputFd, g_inputBuffer.event, sizeof(input_event) * count);
}

// ─── Zone detection ─────────────────────────────────────────────────

static void updateZones() {
    g_trigger_zone.finger_inside = 0;
    g_fire_zone.finger_inside = 0;
    g_joystick_zone.finger_inside = 0;
    if (g_devices.empty()) return;

    int touchMaxX = g_devices[0].absX.maximum;
    int touchMaxY = g_devices[0].absY.maximum;

    for (size_t d = 0; d < g_devices.size(); d++) {
        for (int f = 0; f < maxF; f++) {
            if (!g_devices[d].fingers[f].isDown) continue;
            if (d == 0 && (f == TOUCH_VIRTUAL_SLOT || f == TOUCH_TRIGGER_SLOT)) continue;

            float devX = g_devices[d].fingers[f].pos.x;
            float devY = g_devices[d].fingers[f].pos.y;
            int sx, sy;
            if (g_landscape) {
                sx = static_cast<int>(devY * g_screen_w / touchMaxY);
                sy = g_screen_h - static_cast<int>(devX * g_screen_h / touchMaxX);
            } else {
                sx = static_cast<int>(devX * g_screen_w / touchMaxX);
                sy = static_cast<int>(devY * g_screen_h / touchMaxY);
            }

            if (pointInZone(g_trigger_zone, sx, sy)) g_trigger_zone.finger_inside = 1;
            if (pointInZone(g_fire_zone, sx, sy))    g_fire_zone.finger_inside = 1;
            if (pointInZone(g_joystick_zone, sx, sy)) g_joystick_zone.finger_inside = 1;
        }
    }
}

// ─── Device scanning ────────────────────────────────────────────────

// Detect physical touch device via `getevent -p` text output.
// Avoids direct /dev/input/eventX open — Shizuku UserService process has no
// SELinux permission for that, but can execute `getevent` which reads kernel
// info on its own. Identical approach to v1.0.8 single-file uinput_inject.cpp.
//
// On success fills outPath/outMaxX/outMaxY and returns true.
// Touch-device criterion matches checkDeviceIsTouch() exactly:
// ABS_MT_SLOT + ABS_MT_POSITION_X + ABS_MT_POSITION_Y all required.
static bool detectTouchDeviceViaGetevent(char* outPath, size_t pathSize,
                                         int& outMaxX, int& outMaxY) {
    FILE* fp = popen("/system/bin/getevent -p 2>&1", "r");
    if (!fp) {
        LOGE("detectTouchDevice: popen getevent failed");
        return false;
    }

    char line[512];
    char currentPath[256] = "";
    bool hasSlot = false, hasX = false, hasY = false;
    int maxX = 0, maxY = 0;
    int deviceCount = 0;
    bool found = false;

    auto tryCommit = [&]() -> bool {
        if (deviceCount > 0 && hasSlot && hasX && hasY) {
            strncpy(outPath, currentPath, pathSize - 1);
            outPath[pathSize - 1] = '\0';
            outMaxX = maxX > 0 ? maxX : 0;
            outMaxY = maxY > 0 ? maxY : 0;
            LOGD("Detected touch device: %s abs=%dx%d", outPath, outMaxX, outMaxY);
            return true;
        }
        return false;
    };

    while (fgets(line, sizeof(line), fp)) {
        // New device section: "add device N: /dev/input/eventX"
        if (strstr(line, "add device") && strstr(line, "/dev/input/event")) {
            if (tryCommit()) { found = true; break; }

            char* p = strstr(line, "/dev/input/event");
            if (p) {
                char* end = p;
                while (*end && *end != '\n' && *end != '\r' && *end != ' ') end++;
                size_t len = static_cast<size_t>(end - p);
                if (len >= sizeof(currentPath)) len = sizeof(currentPath) - 1;
                memcpy(currentPath, p, len);
                currentPath[len] = '\0';
            }
            deviceCount++;
            hasSlot = hasX = hasY = false;
            maxX = maxY = 0;
        }
        // Skip our own previously-created virtual device
        if (strstr(line, "name:") && strstr(line, "Aimbot")) {
            hasSlot = hasX = hasY = false;
            maxX = maxY = 0;
        }
        // ABS codes (hex): 002f=ABS_MT_SLOT(47), 0035=ABS_MT_POSITION_X(53),
        // 0036=ABS_MT_POSITION_Y(54). Some Android versions emit symbolic names
        // instead — accept both.
        if (strstr(line, "002f") || strstr(line, "ABS_MT_SLOT")) hasSlot = true;
        if (strstr(line, "0035") || strstr(line, "ABS_MT_POSITION_X")) {
            hasX = true;
            int val;
            if (sscanf(line, "%*x%*[^m]min %*d, max %d", &val) == 1 && val > 0) maxX = val;
        }
        if (strstr(line, "0036") || strstr(line, "ABS_MT_POSITION_Y")) {
            hasY = true;
            int val;
            if (sscanf(line, "%*x%*[^m]min %*d, max %d", &val) == 1 && val > 0) maxY = val;
        }
    }
    if (!found) found = tryCommit();

    pclose(fp);
    return found;
}

// Open physical touch device and grab it for exclusive access. EVIOCGRAB makes
// InputReader ignore events from this device, so virtual touches injected via
// uinput aren't accompanied by physical-finger noise. Returns fd on success,
// -1 on failure (logged). Identical to v1.0.8 single-file direct_reader.
static int openAndGrabPhysicalDevice(const char* path) {
    if (!path || path[0] == '\0') return -1;
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        LOGE("openAndGrab: open %s failed errno=%d", path, errno);
        return -1;
    }
    if (ioctl(fd, EVIOCGRAB, GRAB) < 0) {
        LOGE("openAndGrab: EVIOCGRAB on %s failed errno=%d", path, errno);
        close(fd);
        return -1;
    }
    LOGD("openAndGrab: fd=%d EVIOCGRAB success on %s", fd, path);
    return fd;
}

static bool checkDeviceIsTouch(int fd) {
    uint8_t* bits = nullptr;
    ssize_t bitsSize = 0;
    int res = 0;
    bool hasSlot = false, hasX = false, hasY = false;
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
                if (code == ABS_MT_SLOT) hasSlot = true;
                if (code == ABS_MT_POSITION_X) hasX = true;
                if (code == ABS_MT_POSITION_Y) hasY = true;
            }
        }
    }
    free(bits);
    return hasSlot && hasX && hasY;
}

// ─── uinput device creation ─────────────────────────────────────────

static bool createUinputDevice(int screenX, int screenY, int sourceFd) {
    uinput_user_dev uiDev{};
    g_outputFd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (g_outputFd <= 0) {
        LOGE("open /dev/uinput failed");
        return false;
    }

    char randomName[16]{};
    genRandomString(randomName, sizeof(randomName));
    strncpy(uiDev.name, randomName, UINPUT_MAX_NAME_SIZE);
    uiDev.id.bustype = 0;
    uiDev.id.vendor = rand() % 10 + 5;
    uiDev.id.product = rand() % 10 + 5;
    uiDev.id.version = rand() % 10 + 5;

    ioctl(g_outputFd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
    ioctl(g_outputFd, UI_SET_EVBIT, EV_ABS);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_X);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_Y);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(g_outputFd, UI_SET_EVBIT, EV_SYN);
    ioctl(g_outputFd, UI_SET_EVBIT, EV_KEY);
    ioctl(g_outputFd, UI_SET_KEYBIT, BTN_TOOL_FINGER);
    ioctl(g_outputFd, UI_SET_KEYBIT, BTN_TOOL_DOUBLETAP);
    ioctl(g_outputFd, UI_SET_KEYBIT, BTN_TOOL_TRIPLETAP);
    ioctl(g_outputFd, UI_SET_KEYBIT, BTN_TOOL_QUADTAP);
    ioctl(g_outputFd, UI_SET_KEYBIT, BTN_TOOL_QUINTTAP);
    ioctl(g_outputFd, UI_SET_KEYBIT, BTN_TOUCH);

    char randomPhys[16]{};
    genRandomString(randomPhys, sizeof(randomPhys));
    ioctl(g_outputFd, UI_SET_PHYS, randomPhys);

    input_id id{};
    if (ioctl(sourceFd, EVIOCGID, &id) == 0) uiDev.id = id;

    uint8_t* bits = nullptr;
    ssize_t bitsSize = 0;
    int res = 0;
    while (true) {
        res = ioctl(sourceFd, EVIOCGBIT(EV_KEY, bitsSize), bits);
        if (res < bitsSize) break;
        bitsSize = res + 16;
        bits = static_cast<uint8_t*>(realloc(bits, bitsSize * 2));
    }
    for (int j = 0; j < res; ++j) {
        for (int k = 0; k < 8; ++k) {
            int code = j * 8 + k;
            if (bits[j] & (1 << k)) {
                if (code == BTN_TOUCH || code == BTN_TOOL_FINGER) continue;
                ioctl(g_outputFd, UI_SET_KEYBIT, code);
            }
        }
    }
    free(bits);

    uiDev.absmin[ABS_MT_SLOT] = 0;
    uiDev.absmax[ABS_MT_SLOT] = maxE * maxF - 1;
    uiDev.absmin[ABS_MT_POSITION_X] = 0;
    uiDev.absmax[ABS_MT_POSITION_X] = screenX;
    uiDev.absmin[ABS_MT_POSITION_Y] = 0;
    uiDev.absmax[ABS_MT_POSITION_Y] = screenY;
    uiDev.absmin[ABS_X] = 0;
    uiDev.absmax[ABS_X] = screenX;
    uiDev.absmin[ABS_Y] = 0;
    uiDev.absmax[ABS_Y] = screenY;
    uiDev.absmin[ABS_MT_TRACKING_ID] = 0;
    uiDev.absmax[ABS_MT_TRACKING_ID] = 65535;
    write(g_outputFd, &uiDev, sizeof(uiDev));

    if (ioctl(g_outputFd, UI_DEV_CREATE)) {
        LOGE("UI_DEV_CREATE failed");
        close(g_outputFd);
        g_outputFd = 0;
        return false;
    }
    return true;
}

// ─── Close ──────────────────────────────────────────────────────────

static void closeTouchLocked() {
    if (!g_initialized) return;
    for (auto& device : g_devices) {
        if (device.fd >= 0) {
            ioctl(device.fd, EVIOCGRAB, UNGRAB);
            close(device.fd);
        }
        device.fd = -1;
    }
    if (g_outputFd > 0) {
        ioctl(g_outputFd, UI_DEV_DESTROY);
        close(g_outputFd);
        g_outputFd = 0;
    }
    memset(g_inputBuffer.event, 0, sizeof(g_inputBuffer.event));
    g_uploadedFingerDown = {};
    g_initialized = false;
    g_devices.clear();
}

// ─── Reader thread ──────────────────────────────────────────────────

static void* deviceReader(void* arg) {
    int devIdx = static_cast<int>(reinterpret_cast<long>(arg));
    if (devIdx < 0 || devIdx >= static_cast<int>(g_devices.size())) return nullptr;
    Device& dev = g_devices[devIdx];
    if (dev.fd < 0) {
        LOGE("Reader[%d]: no fd (grab failed), exiting", devIdx);
        return nullptr;
    }

    int curSlot = 0;
    input_event batch[64];

    while (g_running) {
        ssize_t n = read(dev.fd, batch, sizeof(batch));
        if (n <= 0 || n % sizeof(input_event) != 0) continue;

        size_t count = n / sizeof(input_event);
        std::lock_guard<std::mutex> guard(g_mutex);

        for (size_t j = 0; j < count; j++) {
            auto& ie = batch[j];

            if (ie.type == EV_ABS) {
                switch (ie.code) {
                case ABS_MT_SLOT:
                    curSlot = ie.value;
                    break;
                case ABS_MT_TRACKING_ID:
                    if (curSlot >= 0 && curSlot < maxF) {
                        if (ie.value == -1)
                            dev.fingers[curSlot].isDown = false;
                        else {
                            dev.fingers[curSlot].isDown = true;
                            dev.fingers[curSlot].id =
                                static_cast<int>((devIdx * 2 + 1) * maxF + curSlot);
                        }
                    }
                    break;
                case ABS_MT_POSITION_X:
                    if (curSlot >= 0 && curSlot < maxF) {
                        dev.fingers[curSlot].pos.x = ie.value * dev.s2tx;
                        dev.fingers[curSlot].isDown = true;
                    }
                    break;
                case ABS_MT_POSITION_Y:
                    if (curSlot >= 0 && curSlot < maxF) {
                        dev.fingers[curSlot].pos.y = ie.value * dev.s2ty;
                        dev.fingers[curSlot].isDown = true;
                    }
                    break;
                }
            }

            if (ie.type == EV_SYN && ie.code == SYN_REPORT) {
                upload();
                updateZones();
            }
        }
    }

    LOGD("Reader[%d]: stopped", devIdx);
    return nullptr;
}

// ═════════════════════════════════════════════════════════════════════
//  Public API (touch_core.h)
// ═════════════════════════════════════════════════════════════════════

bool touch_init(int screenW, int screenH) {
    if (screenW <= 0 || screenH <= 0) {
        LOGE("touch_init: invalid screen size %dx%d", screenW, screenH);
        return false;
    }
    std::lock_guard<std::mutex> guard(g_mutex);
    closeTouchLocked();

    Vec2 size(static_cast<float>(screenW), static_cast<float>(screenH));
    g_screenSize = size.x > size.y ? size : Vec2(size.y, size.x);
    g_screen_w = screenW;
    g_screen_h = screenH;

    // Detect physical touch device via getevent (no direct /dev/input access).
    // Falls back to screen size if not detected — uinput injection still works,
    // physical-finger tracking / zone detection just becomes unavailable.
    int touchMaxX = screenW;
    int touchMaxY = screenH;
    char touchPath[256] = "";
    if (detectTouchDeviceViaGetevent(touchPath, sizeof(touchPath), touchMaxX, touchMaxY)) {
        LOGD("Using touch device ABS: %dx%d", touchMaxX, touchMaxY);
    } else {
        LOGD("No touch device detected, using screen size as ABS: %dx%d", touchMaxX, touchMaxY);
    }

    // Always seed a placeholder device entry. fd=-1 means "no real fd to grab
    // or read from" — downstream code can still rely on g_devices[0] for
    // finger/scale bookkeeping without empty checks.
    Device device{};
    device.fd = -1;
    device.absX.maximum = touchMaxX;
    device.absY.maximum = touchMaxY;
    strncpy(device.path, touchPath, sizeof(device.path) - 1);
    device.path[sizeof(device.path) - 1] = '\0';

    // Try to grab physical device so InputReader stops forwarding its events.
    // Failure here is non-fatal — uinput injection still works, just without
    // physical-touch suppression (game may receive both real and virtual taps).
    if (touchPath[0] != '\0') {
        int fd = openAndGrabPhysicalDevice(touchPath);
        if (fd >= 0) device.fd = fd;
        else LOGE("touch_init: grab %s failed, physical touches not suppressed", touchPath);
    }

    g_devices.push_back(device);

    if (!createUinputDevice(touchMaxX, touchMaxY, -1)) {
        closeTouchLocked();
        return false;
    }

    Vec2 logical = size;
    if (logical.x > logical.y) std::swap(logical.x, logical.y);
    g_touchScale.x = static_cast<float>(touchMaxX) / std::max(1.0f, logical.x);
    g_touchScale.y = static_cast<float>(touchMaxY) / std::max(1.0f, logical.y);
    g_initialized = true;
    LOGD("touch ready scale=%.3f,%.3f", g_touchScale.x, g_touchScale.y);
    return true;
}

void touch_close(void) {
    touch_stop_readers();
    std::lock_guard<std::mutex> guard(g_mutex);
    closeTouchLocked();
}

bool touch_is_initialized(void) { return g_initialized; }
int  touch_get_output_fd(void)   { return g_outputFd; }

void touch_start_readers(void) {
    if (g_running) return;
    if (!g_initialized) return;

    g_running = true;
    g_reader_threads.resize(g_devices.size());
    for (size_t i = 0; i < g_devices.size(); i++) {
        if (pthread_create(&g_reader_threads[i], nullptr, deviceReader,
                           reinterpret_cast<void*>(i)) != 0) {
            LOGE("pthread_create failed for device %zu", i);
            g_running = false;
            g_reader_threads.resize(i);
            return;
        }
    }
    LOGD("Started %zu reader threads", g_devices.size());
}

void touch_stop_readers(void) {
    if (!g_running) return;
    g_running = false;
    for (auto& t : g_reader_threads)
        pthread_join(t, nullptr);
    g_reader_threads.clear();
    LOGD("Stopped all readers");
}

void touch_set_screen_params(int w, int h, bool landscape) {
    g_screen_w = w;
    g_screen_h = h;
    g_landscape = landscape;
}

void touch_down(int slot, int id, int screenX, int screenY) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!g_initialized || g_devices.empty()) return;
    float tx, ty;
    screenToTouch(screenX, screenY, tx, ty);
    g_devices[0].fingers[slot].id = id;
    g_devices[0].fingers[slot].pos = Vec2(tx, ty);
    g_devices[0].fingers[slot].isDown = true;
    upload();
}

void touch_move(int slot, int screenX, int screenY) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!g_initialized || g_devices.empty()) return;
    float tx, ty;
    screenToTouch(screenX, screenY, tx, ty);
    g_devices[0].fingers[slot].pos = Vec2(tx, ty);
    upload();
}

void touch_up(int slot) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!g_initialized || g_devices.empty()) return;
    g_devices[0].fingers[slot].isDown = false;
    upload();
}

void touch_set_trigger_zone(int l, int t, int r, int b)  { g_trigger_zone = {l, t, r, b, 0}; }
void touch_set_fire_zone(int l, int t, int r, int b)     { g_fire_zone = {l, t, r, b, 0}; }
void touch_set_joystick_zone(int l, int t, int r, int b) { g_joystick_zone = {l, t, r, b, 0}; }

bool touch_is_finger_in_trigger_zone(void)  { return g_trigger_zone.finger_inside; }
bool touch_is_finger_in_fire_zone(void)     { return g_fire_zone.finger_inside; }
bool touch_is_finger_in_joystick_zone(void) { return g_joystick_zone.finger_inside; }

bool touch_lift_joystick_finger(void) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!g_initialized || g_devices.empty()) return false;

    bool lifted = false;
    int touchMaxX = g_devices[0].absX.maximum;
    int touchMaxY = g_devices[0].absY.maximum;

    for (size_t d = 0; d < g_devices.size(); d++) {
        for (int f = 0; f < maxF; f++) {
            if (!g_devices[d].fingers[f].isDown) continue;
            if (d == 0 && (f == TOUCH_VIRTUAL_SLOT || f == TOUCH_TRIGGER_SLOT)) continue;

            float devX = g_devices[d].fingers[f].pos.x;
            float devY = g_devices[d].fingers[f].pos.y;
            int sx, sy;
            if (g_landscape) {
                sx = static_cast<int>(devY * g_screen_w / touchMaxY);
                sy = g_screen_h - static_cast<int>(devX * g_screen_h / touchMaxX);
            } else {
                sx = static_cast<int>(devX * g_screen_w / touchMaxX);
                sy = static_cast<int>(devY * g_screen_h / touchMaxY);
            }

            if (pointInZone(g_joystick_zone, sx, sy)) {
                g_devices[d].fingers[f].isDown = false;
                lifted = true;
                LOGD("liftJoystickFinger: dev%zu finger%d at (%d,%d)", d, f, sx, sy);
            }
        }
    }

    if (lifted) upload();
    return lifted;
}
