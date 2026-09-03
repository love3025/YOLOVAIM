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
#include <atomic>
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
#define LOGLAT(...) __android_log_print(ANDROID_LOG_DEBUG, "YolovaimLatency", __VA_ARGS__)
#ifdef YOLOVAIM_TRACE
#define LOGTRACELAT(...) __android_log_print(ANDROID_LOG_DEBUG, "YolovaimLatency", __VA_ARGS__)
#else
#define LOGTRACELAT(...) do {} while (0)
#endif
#else
#define LOGD(...) do { fprintf(stderr, "D/" LOG_TAG ": " __VA_ARGS__); fputc('\n', stderr); } while(0)
#define LOGE(...) do { fprintf(stderr, "E/" LOG_TAG ": " __VA_ARGS__); fputc('\n', stderr); } while(0)
#define LOGLAT(...) do { fprintf(stderr, "D/YolovaimLatency: " __VA_ARGS__); fputc('\n', stderr); } while(0)
#ifdef YOLOVAIM_TRACE
#define LOGTRACELAT(...) do { fprintf(stderr, "D/YolovaimLatency: " __VA_ARGS__); fputc('\n', stderr); } while(0)
#else
#define LOGTRACELAT(...) do {} while (0)
#endif
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

// Physical-panel pressure / contact-size ranges, cloned from the real device.
// 0 means the panel doesn't report that axis → we skip it (reporting a value
// on an unadvertised axis would itself look fake).
static int g_pressure_max = 0;
static int g_touch_major_max = 0;
static int g_width_major_max = 0;

// Reader threads
static std::vector<pthread_t> g_reader_threads;
static volatile bool g_running = false;

// Detection zones
static Zone g_trigger_zone;
static Zone g_fire_zone;
static Zone g_joystick_zone;

// ─── Helpers ─────────────────────────────────────────────────────────

static inline long long touchTimeUs() {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return tv.tv_sec * 1000000LL + tv.tv_usec;
}

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

static int randInRange(int lo, int hi) {
    if (hi <= lo) return lo;
    return lo + rand() % (hi - lo + 1);
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
                // Real fingers report pressure / contact area every frame within a
                // small slice of the panel's reported max (raw ADC, NOT 50-90% of
                // max which would be abnormal). Only emit axes the panel advertises.
                if (g_pressure_max > 0)
                    pushEvent(count, EV_ABS, ABS_MT_PRESSURE,
                              randInRange(g_pressure_max / 333, g_pressure_max / 40));
                if (g_touch_major_max > 0)
                    pushEvent(count, EV_ABS, ABS_MT_TOUCH_MAJOR,
                              randInRange(g_touch_major_max / 12, g_touch_major_max / 4));
                if (g_width_major_max > 0)
                    pushEvent(count, EV_ABS, ABS_MT_WIDTH_MAJOR,
                              randInRange(g_width_major_max / 12, g_width_major_max / 4));
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
    // This log used to fire on every successful uinput write — i.e. once per
    // aim frame — and it runs inside the injector process, in the exact window
    // where the caller was blocked waiting for the round-trip. It put a logd
    // socket write on the injection critical path.
#ifdef YOLOVAIM_TRACE
    long long tWriteStart = touchTimeUs();
#endif
    int wr = write(g_outputFd, g_inputBuffer.event, sizeof(input_event) * count);
    if (wr < 0) {
        LOGE("upload: write failed errno=%d", errno);
    } else {
#ifdef YOLOVAIM_TRACE
        long long tWriteEnd = touchTimeUs();
        LOGTRACELAT("uinput write | events=%d us=%.2fms", count, (tWriteEnd - tWriteStart) / 1e3);
#endif
    }
}

// ─── Zone detection ─────────────────────────────────────────────────

// 开火区上升沿计数。
//
// updateZones() 挂在每个 SYN_REPORT 上，也就是 120-240Hz 的真实触摸事件率；
// 应用侧却只能按推理帧率(30-60Hz)查 g_fire_zone.finger_inside 这个电平。
// 于是半自动连点被按「按压时长」而非「点击次数」计量：30fps 下短于 33ms 的
// 点击会整帧落空，那一枪的压枪根本没算，而漏与不漏取决于点击与推理帧的相位。
// 在这一层数上升沿，240Hz 下 15ms 的点击也不会丢。
//
// g_fire_prev 只在 updateZones() 里读写，而 updateZones() 的调用方
// (readerThread) 始终持有 g_mutex，所以普通 int 即可。
// g_fire_taps 则要被 IPC 线程以读-改-写方式取走(consume)，与 reader 线程的
// 自增并发，必须是原子的 —— 现有的 touch_is_finger_in_fire_zone() 不加锁是
// 因为它只是单次纯读，consume 没这个豁免。
static std::atomic<int> g_fire_taps{0};
static int g_fire_prev = 0;

static inline void countFireEdgeLocked() {
    const int now = g_fire_zone.finger_inside;
    if (now && !g_fire_prev) g_fire_taps.fetch_add(1, std::memory_order_relaxed);
    g_fire_prev = now;
}

static void updateZones() {
    g_trigger_zone.finger_inside = 0;
    g_fire_zone.finger_inside = 0;
    g_joystick_zone.finger_inside = 0;
    if (g_devices.empty()) { countFireEdgeLocked(); return; }

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

    countFireEdgeLocked();
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
        // NOTE: 遗留过滤器。早期版本把虚拟设备命名为品牌名，此处按名字跳过自己。
        // 现在设备名克隆自真实触摸设备（见下方 EVIOCGNAME 分支）或为随机串，
        // 所以这个条件实际已经匹配不到任何东西。改名时一并更新以免留下旧品牌，
        // 行为未变；要真正修复应改为按 uinput fd / devpath 排除自身。
        if (strstr(line, "name:") && strstr(line, "YOLOVAIM")) {
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

    // ── Device identity: clone from the real panel when we have its fd ──
    // A cloned bustype/vendor/product/name makes /proc/bus/input/devices and
    // InputDevice enumeration match a genuine touchscreen. Without a source fd
    // we fall back to plausible values (BUS_I2C is the common touch bus) rather
    // than the illegal bustype=0 a bare uinput device would otherwise expose.
    input_id realId{};
    bool haveRealId = (sourceFd >= 0 && ioctl(sourceFd, EVIOCGID, &realId) == 0);

    char realName[UINPUT_MAX_NAME_SIZE]{};
    bool haveRealName = (sourceFd >= 0 &&
                         ioctl(sourceFd, EVIOCGNAME(sizeof(realName) - 1), realName) > 0 &&
                         realName[0] != '\0');

    if (haveRealName) {
        strncpy(uiDev.name, realName, UINPUT_MAX_NAME_SIZE - 1);
    } else {
        char randomName[16]{};
        genRandomString(randomName, sizeof(randomName));
        strncpy(uiDev.name, randomName, UINPUT_MAX_NAME_SIZE - 1);
    }

    if (haveRealId) {
        uiDev.id = realId;
    } else {
        uiDev.id.bustype = BUS_I2C;
        uiDev.id.vendor = rand() % 10 + 5;
        uiDev.id.product = rand() % 10 + 5;
        uiDev.id.version = rand() % 10 + 5;
    }

    // Clone input device properties (INPUT_PROP_DIRECT, INPUT_PROP_POINTER, ...)
    // from the real panel. Different vendors set different prop combinations —
    // a OnePlus panel is INPUT_PROP_DIRECT alone, some Samsung panels also set
    // INPUT_PROP_POINTER, etc. Falling back to INPUT_PROP_DIRECT if the clone
    // fails keeps the device at least minimally registerable.
    bool clonedProps = false;
    if (sourceFd >= 0) {
        uint8_t propBits[64]{};
        ssize_t propRes = ioctl(sourceFd, EVIOCGPROP(sizeof(propBits)), propBits);
        if (propRes > 0) {
            int propCount = static_cast<int>(propRes) < static_cast<int>(sizeof(propBits))
                                ? static_cast<int>(propRes)
                                : static_cast<int>(sizeof(propBits));
            for (int j = 0; j < propCount; ++j) {
                for (int k = 0; k < 8; ++k) {
                    int code = j * 8 + k;
                    if (propBits[j] & (1 << k)) {
                        ioctl(g_outputFd, UI_SET_PROPBIT, code);
                    }
                }
            }
            clonedProps = true;
        }
    }
    if (!clonedProps) {
        ioctl(g_outputFd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
    }

    ioctl(g_outputFd, UI_SET_EVBIT, EV_ABS);
    ioctl(g_outputFd, UI_SET_EVBIT, EV_SYN);
    ioctl(g_outputFd, UI_SET_EVBIT, EV_KEY);

    // Mandatory axes for our multitouch injection. Note: we intentionally do NOT
    // register ABS_X/ABS_Y — real panels (including this device's "touchpanel")
    // report coordinates via ABS_MT_POSITION_X/Y only. A ghost ABS_X/Y axis is
    // a strong uinput tell. SLOT/POSITION ranges are overridden to the panel's
    // actual coordinate extent below.
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(g_outputFd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);

    char randomPhys[16]{};
    genRandomString(randomPhys, sizeof(randomPhys));
    ioctl(g_outputFd, UI_SET_PHYS, randomPhys);

    // ── Clone every ABS axis (incl. pressure / touch major / width) from the
    // real panel, preserving each axis's min/max/fuzz/flat. This adds the
    // pressure and contact-area capability bits a genuine touchscreen has. ──
    g_pressure_max = g_touch_major_max = g_width_major_max = 0;
    if (sourceFd >= 0) {
        uint8_t* absBits = nullptr;
        ssize_t absSize = 0;
        int absRes = 0;
        while (true) {
            absRes = ioctl(sourceFd, EVIOCGBIT(EV_ABS, absSize), absBits);
            if (absRes < absSize) break;
            absSize = absRes + 16;
            absBits = static_cast<uint8_t*>(realloc(absBits, absSize * 2));
        }
        for (int j = 0; j < absRes; ++j) {
            for (int k = 0; k < 8; ++k) {
                int code = j * 8 + k;
                if (!(absBits[j] & (1 << k))) continue;
                input_absinfo ai{};
                if (ioctl(sourceFd, EVIOCGABS(code), &ai) != 0) continue;
                ioctl(g_outputFd, UI_SET_ABSBIT, code);
                uiDev.absmin[code] = ai.minimum;
                uiDev.absmax[code] = ai.maximum;
                uiDev.absfuzz[code] = ai.fuzz;
                uiDev.absflat[code] = ai.flat;
                if (code == ABS_MT_PRESSURE)    g_pressure_max = ai.maximum;
                if (code == ABS_MT_TOUCH_MAJOR) g_touch_major_max = ai.maximum;
                if (code == ABS_MT_WIDTH_MAJOR) g_width_major_max = ai.maximum;
            }
        }
        free(absBits);
    }

    // Clone the real panel's KEY capabilities (button set) verbatim. We do NOT
    // hardcode any BTN_TOOL_* bits — doing so would diverge from the real panel
    // (which is a strong uinput tell). Fall back to the minimum required
    // multitouch set (BTN_TOUCH + BTN_TOOL_FINGER) if no source fd is available.
    uint8_t* bits = nullptr;
    ssize_t bitsSize = 0;
    int res = 0;
    bool clonedKeys = false;
    if (sourceFd >= 0) {
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
                    ioctl(g_outputFd, UI_SET_KEYBIT, code);
                }
            }
        }
        clonedKeys = res > 0;
    }
    free(bits);
    if (!clonedKeys) {
        ioctl(g_outputFd, UI_SET_KEYBIT, BTN_TOUCH);
        ioctl(g_outputFd, UI_SET_KEYBIT, BTN_TOOL_FINGER);
    }

    // Override ranges for axes we drive. The ABS-bit loop above already copied
    // min/max from the real panel for every axis (including SLOT, POSITION_X/Y,
    // PRESSURE, TOUCH_MAJOR). We only re-pin:
    //   - POSITION_X/Y to the panel's actual coordinate extent (screenX/Y from
    //     getevent — the cloned values already match, this is a safety net for
    //     getevent failures).
    //   - TRACKING_ID to 65535 so we never run out of IDs internally.
    // ABS_MT_SLOT is left as the real panel reported it (Pixel/older panels
    // declare 5 slots, OnePlus/Samsung declare 10). Fall back to 9 only when no
    // real-panel data was available.
    if (uiDev.absmax[ABS_MT_SLOT] == 0) {
        uiDev.absmin[ABS_MT_SLOT] = 0;
        uiDev.absmax[ABS_MT_SLOT] = maxF - 1;
    }
    uiDev.absmin[ABS_MT_POSITION_X] = 0;
    uiDev.absmax[ABS_MT_POSITION_X] = screenX;
    uiDev.absmin[ABS_MT_POSITION_Y] = 0;
    uiDev.absmax[ABS_MT_POSITION_Y] = screenY;
    uiDev.absmin[ABS_MT_TRACKING_ID] = 0;
    uiDev.absmax[ABS_MT_TRACKING_ID] = 65535;
    write(g_outputFd, &uiDev, sizeof(uiDev));

    if (ioctl(g_outputFd, UI_DEV_CREATE)) {
        LOGE("UI_DEV_CREATE failed");
        close(g_outputFd);
        g_outputFd = 0;
        return false;
    }
    LOGD("uinput created: name='%s' bus=0x%x vid=0x%x pid=0x%x pressure=%d major=%d",
         uiDev.name, uiDev.id.bustype, uiDev.id.vendor, uiDev.id.product,
         g_pressure_max, g_touch_major_max);
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

    // Clone identity + ABS/KEY capabilities from the real panel (device.fd) so
    // the virtual device mimics the physical touchscreen. If grab failed we open
    // a temporary read-only fd solely to clone from; -1 keeps the fallback path.
    int cloneFd = device.fd;
    bool cloneFdTemporary = false;
    if (cloneFd < 0 && touchPath[0] != '\0') {
        cloneFd = open(touchPath, O_RDONLY);
        cloneFdTemporary = (cloneFd >= 0);
    }

    bool created = createUinputDevice(touchMaxX, touchMaxY, cloneFd);

    if (cloneFdTemporary) close(cloneFd);

    if (!created) {
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

// Reader-only 初始化(stealth/KPM 注入路径用),与 touch_init 只差两处:
// 1) 面板 fd 只 O_RDONLY 打开,不 EVIOCGRAB —— uinput 路径靠 grab 让
//    InputReader 忽略真手指(游戏只看 uinput 投影);stealth 路径注入与真触摸
//    同源同路径,真手指必须继续送达游戏,grab 了游戏就收不到任何触摸。
//    代价:本读者会同时看到真手指与 KPM 注入报文,靠 slot 8/9 排除表区分
//    (updateZones / touch_lift_joystick_finger 已排除,无需改动)。
// 2) 不创建 uinput 设备,g_outputFd 保持 0 —— upload() 开头的
//    `if (g_outputFd <= 0) return;` 让读者线程里的 upload 全程 no-op,
//    zone 检测 / fire 边沿计数零改动复用。
bool touch_init_reader_only(int screenW, int screenH) {
    if (screenW <= 0 || screenH <= 0) {
        LOGE("touch_init_reader_only: invalid screen size %dx%d", screenW, screenH);
        return false;
    }
    std::lock_guard<std::mutex> guard(g_mutex);
    closeTouchLocked();

    Vec2 size(static_cast<float>(screenW), static_cast<float>(screenH));
    g_screenSize = size.x > size.y ? size : Vec2(size.y, size.x);
    g_screen_w = screenW;
    g_screen_h = screenH;

    int touchMaxX = screenW;
    int touchMaxY = screenH;
    char touchPath[256] = "";
    if (!detectTouchDeviceViaGetevent(touchPath, sizeof(touchPath), touchMaxX, touchMaxY) ||
        touchPath[0] == '\0') {
        // uinput 路径探测失败可以继续(只是没有 zone 检测);stealth 路径的
        // 读者是全部意义所在,没有面板 fd 就没有存在的必要
        LOGE("touch_init_reader_only: no touch device detected");
        return false;
    }

    int fd = open(touchPath, O_RDONLY);
    if (fd < 0) {
        LOGE("touch_init_reader_only: open %s failed errno=%d", touchPath, errno);
        return false;
    }

    Device device{};
    device.fd = fd;
    device.absX.maximum = touchMaxX;
    device.absY.maximum = touchMaxY;
    strncpy(device.path, touchPath, sizeof(device.path) - 1);
    device.path[sizeof(device.path) - 1] = '\0';
    g_devices.push_back(device);

    Vec2 logical = size;
    if (logical.x > logical.y) std::swap(logical.x, logical.y);
    g_touchScale.x = static_cast<float>(touchMaxX) / std::max(1.0f, logical.x);
    g_touchScale.y = static_cast<float>(touchMaxY) / std::max(1.0f, logical.y);
    g_initialized = true;
    LOGD("touch(reader-only) ready scale=%.3f,%.3f", g_touchScale.x, g_touchScale.y);
    return true;
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

// 一次往返同时取走电平和点击数：(taps << 1) | level。
// 拆成两条命令会让推理热路径每帧多一次阻塞式 IPC 往返，正是 4ee9e9e 刚
// 消除掉的那类浪费(execCmd 是 write + readLine，且在 cmdLock 上串行)。
int touch_consume_fire_state(void) {
    const int taps = g_fire_taps.exchange(0, std::memory_order_relaxed);
    return (taps << 1) | (g_fire_zone.finger_inside ? 1 : 0);
}
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

// 当前真实手指(排除虚拟 slot 8/9)中位于摇杆区内的面板 slot 号列表。
// uinput 路径自己用 touch_lift_joystick_finger() 抹掉 uinput 投影即可;
// stealth 路径没有 uinput 投影,真手指直通游戏 —— 调用方拿本函数返回的
// slot 后,经 KPM 内核通道对这些 slot 各发一次抬起来完成同样的"自动停枪"。
// 返回写入 outSlots 的个数(0 = 摇杆区没有真实手指)。
int touch_get_joystick_finger_slots(int* outSlots, int maxSlots) {
    if (outSlots == nullptr || maxSlots <= 0) return 0;
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!g_initialized || g_devices.empty()) return 0;

    int count = 0;
    int touchMaxX = g_devices[0].absX.maximum;
    int touchMaxY = g_devices[0].absY.maximum;

    for (size_t d = 0; d < g_devices.size() && count < maxSlots; d++) {
        for (int f = 0; f < maxF && count < maxSlots; f++) {
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
                outSlots[count++] = f;
                LOGD("joystickFingerSlot: dev%zu finger%d at (%d,%d)", d, f, sx, sy);
            }
        }
    }
    return count;
}
