// stealth_backend.h — root_daemon 侧的无痕(KPM)注入后端封装。
//
// touchc.cpp 是通用注入库(单例、C++、为 app 进程集成设计);daemon 的行协议
// 是 C 风格命令分发,这里做一层薄翻译。KPM syscall 通道要求调用进程为 root
// (inputprobe.c 的 on_touch_syscall 对 uid!=0 直接放行穿透),所以这层必须
// 活在 root_daemon 进程里,app 进程直调不可行。
//
// zone 检测(按住触发/开火电平/摇杆)不走这层 —— touch_core 的读者线程在
// reader-only 初始化后照常工作,客户端照旧发 IS_FINGER_IN_* / GET_FIRE_STATE。
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    STEALTH_OK = 0,
    STEALTH_NO_DEVICE,    // 没找到触摸屏设备(touch_init_reader_only 失败)
    STEALTH_NO_CHANNEL,   // 内核无 KPM 或配对握手失败(盐不一致/未装 .kpm)
    STEALTH_INTERNAL,     // 库内部资源失败
    STEALTH_NOT_READY     // init 未成功就调注入(防护,静默丢弃)
} StealthStatus;

// 初始化:先 reader-only 起 touch_core(zone 检测/读者线程全复用),再
// TouchManager::Init 握手 KPM。成功后 *out_slot_max 带回面板 slot 上限
// (可传 NULL)。失败时内部状态已清理,可换 UINPUT 路径重试。
StealthStatus stealth_init(int screenW, int screenH, int* out_slot_max);

bool stealth_is_active(void);

// 注入(屏幕坐标,库内部换算设备单位)。slot 与 touch_core.h 对齐:
// 8=TOUCH_VIRTUAL_SLOT(自瞄指),9=TOUCH_TRIGGER_SLOT(扳机指)。
// 也可传真实手指所在 slot 给 stealth_up,实现"抬起玩家摇杆指"。
void stealth_down(int slot, int x, int y);
void stealth_move(int slot, int x, int y);
void stealth_up(int slot);

// 四向方向(0=竖屏,1=横屏,2/3=180°/270°)。同时喂库的坐标系和
// touch_core 的 zone 坐标系(g_landscape)。
void stealth_set_orientation(int orientation);

// EVIOCGRAB 独占真实面板。注意:grab 期间 KPM 注入同样不送达游戏,
// 菜单独占期间不要注入(touchc.h 的警告)。
bool stealth_grab(int enable);

// 自动停枪:对摇杆区内的真实手指逐个经 KPM 发抬起。uinput 路径的
// touch_lift_joystick_finger() 靠抹掉 uinput 投影生效,stealth 模式没有
// 投影、真手指直通游戏,必须从内核侧把那个 slot 抬掉。
bool stealth_lift_joystick_finger(void);

// 释放。须与注入无并发 —— daemon 是单线程命令循环,天然满足。
void stealth_close(void);

#ifdef __cplusplus
}
#endif
