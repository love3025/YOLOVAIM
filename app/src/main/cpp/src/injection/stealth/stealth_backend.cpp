// stealth_backend.cpp — 见 stealth_backend.h 头注释。
#include "stealth_backend.h"

#include "touch_core.h"
#include "touchc.h"
#include "vector2.h"

// init 时的屏幕尺寸,stealth_set_orientation 里喂 touch_core 坐标系用
// (daemon 的 SET_RESOLUTION 一定先于 STEALTH_INIT 到达,两边一致)。
static int s_screen_w = 0;
static int s_screen_h = 0;
static bool s_active = false;
// 客户端的配置序列是 setOrientationConfig → setResolution → initRemote,
// 所以 STEALTH_SET_ORIENTATION 先于 STEALTH_INIT 到达 —— 记下来,init
// 成功后补投。(-1 = 还没收到过,按库默认 0 竖屏)
static int s_orientation = -1;

extern "C" StealthStatus stealth_init(int screenW, int screenH,
                                      int* out_slot_max) {
    if (out_slot_max != nullptr) { *out_slot_max = -1; }
    if (screenW <= 0 || screenH <= 0) {
        return STEALTH_INTERNAL;
    }

    // 1) touch_core reader-only:面板 fd 不 grab、不建 uinput,
    //    zone 检测 / fire 边沿计数 / 读者线程零改动复用
    if (!touch_init_reader_only(screenW, screenH)) {
        return STEALTH_NO_DEVICE;
    }

    // 2) 库自检:找面板 + KPM 通道握手。失败时库内部已 Close,
    //    touch_core 这边也要收掉,别留一个初始化了一半的读者环境
    auto& tm = TouchManager::GetInstance();
    const auto st = tm.Init(Vector2{static_cast<float>(screenW),
                                    static_cast<float>(screenH)},
                            false, nullptr);
    switch (st) {
        case TouchManager::TouchStatus::kReady:
            s_screen_w = screenW;
            s_screen_h = screenH;
            s_active = true;
            if (out_slot_max != nullptr) { *out_slot_max = tm.GetSlotMax(); }
            // 方向先于 init 到达过(见 s_orientation 注释),这里补投;
            // touchc 的坐标系和 touch_core 的 zone 坐标系一起喂
            if (s_orientation >= 0) {
                tm.SetScreenOrientation(s_orientation);
                touch_set_screen_params(screenW, screenH, s_orientation);
            }
            return STEALTH_OK;
        case TouchManager::TouchStatus::kNoTouchDevice:
            touch_close();
            return STEALTH_NO_DEVICE;
        case TouchManager::TouchStatus::kNoStealthChannel:
            touch_close();
            return STEALTH_NO_CHANNEL;
        default:
            touch_close();
            return STEALTH_INTERNAL;
    }
}

extern "C" bool stealth_is_active(void) { return s_active; }

extern "C" void stealth_down(int slot, int x, int y) {
    if (!s_active) { return; }
    TouchManager::GetInstance().Down(Vector2{static_cast<float>(x),
                                             static_cast<float>(y)},
                                     slot);
}

extern "C" void stealth_move(int slot, int x, int y) {
    if (!s_active) { return; }
    TouchManager::GetInstance().Move(Vector2{static_cast<float>(x),
                                             static_cast<float>(y)},
                                     slot);
}

extern "C" void stealth_up(int slot) {
    if (!s_active) { return; }
    TouchManager::GetInstance().Up(slot);
}

extern "C" void stealth_set_orientation(int orientation) {
    s_orientation = orientation;
    if (!s_active) { return; }  // init 后会补投(见 stealth_init)
    TouchManager::GetInstance().SetScreenOrientation(orientation);
    // touchc 与 touch_core 现在用同一套 0..3 旋转编号,原样透传即可
    touch_set_screen_params(s_screen_w, s_screen_h, orientation);
}

extern "C" bool stealth_grab(int enable) {
    if (!s_active) { return false; }
    return TouchManager::GetInstance().SetGrabEnabled(enable != 0);
}

extern "C" bool stealth_lift_joystick_finger(void) {
    if (!s_active) { return false; }
    int slots[16];
    const int n = touch_get_joystick_finger_slots(slots, 16);
    auto& tm = TouchManager::GetInstance();
    for (int i = 0; i < n; i++) {
        // 对真实手指所在 slot 经 KPM 发抬起;KPM 注入的报文与真驱动
        // 同源,Android 视角就是那根手指抬起了
        tm.Up(slots[i]);
    }
    return n > 0;
}

extern "C" void stealth_close(void) {
    if (!s_active) { return; }
    s_active = false;
    // 库的 Close 会 join 自己的线程(confirm/读)并关自己的面板 fd;
    // touch_core 的读者由随后的 touch_close() 收(daemon 的 DESTROY 序列)
    TouchManager::GetInstance().Close();
}
