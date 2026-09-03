#pragma once
//频道:https://t.me/colanb113
// by cola,基于普通触摸库二改,原创,和kpm橘子的内核触摸效果一样,c++风格

/*
触摸检测原理:ls等命令在/sys/class/input/等设备目录,在目录eventX存在时返回"Permission denied"
eventX目录不存在,则返回"no such file or directory",
所以可以构造"event+数字"遍历设备目录,判断设备数量是否增加或异常
过检测原理:触摸报文由 KPM 在内核里经 input_event 直接送进真实触摸屏设备,
不产生新的输入节点、设备数量不变,与物理触摸同源同路径(帧对齐/压力合成在内核侧)
*/

//兼容性:应该是-能用内核触摸的能用此触摸库,不能用内核触摸的不能用此触摸库

/*
public 函数使用说明(供 app 集成,与任何 UI 框架无关):
1.初始化:TouchManager& tm = TouchManager::GetInstance();
    TouchStatus st = tm.Init(screen_size, false);
    [screen_size:传屏幕/悬浮窗渲染用的实际分辨率]
    [is_physical_pos:坐标是屏幕/逻辑坐标传 false;是充电口方向物理坐标传 true]
    [可选第三参 kpm_key:仅 ctl0 通道用,一般不填 —— 默认走编译期密钥配对
     syscall 通道握手 KPM]
    Init 即首次配对自检:返回 kReady 表示无痕通道就绪,可以 Down/Move/Up;
    返回 kNoTouchDevice / kNoStealthChannel 表示不符合使用条件。
    库不做任何有痕降级 —— 发现用不了时改用 uinput / InputManager 等方案
    由 app 自行决定。
2.注入:tm.Down(pos) / tm.Move(pos) / tm.Up();轨迹与速度曲线由 app 设计。
3.(可选)感知真实手指:Init 前 SetTouchEventCallback(cb),回调收到用户真实
    触摸(TouchPhase::Down/Move/Up + 屏幕坐标);纯注入不注册则不起读线程、零开销。
4.(可选)独占设备:app 有自己的菜单/设置界面、不想真手指穿透到游戏时,
    展开时 SetGrabEnabled(true),收起 SetGrabEnabled(false)。
    注意:grab 期间注入的事件同样不会送达游戏,别在 grab 时注入。
*/
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "vector2.h"

/* ===========================================================================
 * YOLOVAIM vendoring 说明(相对上游 无痕触摸源码/touchc.h 的改动):
 * 1. Down/Move/Up 增加 slot 参数重载 —— root_daemon 用两个注入指
 *    (slot 8=自瞄 / 9=扳机,与 touch_core.h 的 TOUCH_VIRTUAL_SLOT/
 *    TOUCH_TRIGGER_SLOT 对齐,zone 检测已排除这两个 slot)。原三参签名
 *    保留,走 touch_point.slot,上游用法不受影响。
 * 2. SetTouchDevice 记录面板声明的 slot 上限,重载校验越界即丢弃;
 *    GetSlotMax() 供 daemon 在 STEALTH_INIT 回复里带给客户端打日志。
 * 3. ConfirmUpThread 的 confirm_pending(bool) 扩为 per-slot 位图:多 slot
 *    下 slot 8 的 Down 不再误取消 slot 9 的待补发抬起。
 * 升级上游库时按这三处 diff 回来。
 * =========================================================================== */
class TouchManager {
public:
    // 真实手指观察回调的触摸阶段(与任何 UI 框架无关)
    enum class TouchPhase { Down, Move, Up };
    // Init 自检结果:kReady=无痕通道就绪;kNoTouchDevice=没找到触摸屏设备;
    // kNoStealthChannel=内核无 KPM 或握手失败;kInternalError=内部资源失败。
    // 库不做任何有痕降级,失败后的退位(uinput / InputManager 等)由 app 决定
    enum class TouchStatus {
        kReady = 0, kNoTouchDevice, kNoStealthChannel, kInternalError
    };
    // 观察真实用户触摸的回调:pos 为屏幕/逻辑坐标(与 Init 的 screen_size 同空间)
    using TouchEventCallback =
        std::function<void(TouchPhase, const Vector2&)>;

private:
    TouchManager() = default;
    TouchManager(const TouchManager&) = delete;
    TouchManager& operator=(const TouchManager&) = delete;
    TouchManager(TouchManager&&) = delete;
    TouchManager& operator=(TouchManager&&) = delete;
    Vector2 ConvertPhysicalToScreenAndScale(const Vector2& corrd,
                                            const Vector2& scale);
    std::vector<std::string> ScanInputDevicesPath();
    bool CheckKeyBit(unsigned int bit, const unsigned long* arr) const noexcept;
    bool CheckIsTouchDevice(int fd) const noexcept;
    bool SetTouchDevice(const std::vector<std::string>& input_device_paths);
    Vector2 ConvertScreenToPhysicalNoScale(Vector2 screen_pos);
    void ReadTouchEvent();
    void ConfirmUpThread();
    // KPM 后端:内核注入通道(syscall 密钥配对优先,ctl0 supercall 兜底)
    bool KpmInit();
    bool KpmSend(bool down, bool is_down_event, const Vector2& physical_pos,
                 int slot);
    bool SendTouchReport(bool down, bool is_down_event,
                         const Vector2& physical_pos, int slot);
    void RequestConfirmUp(int slot);
    struct TouchPoint {
        int slot = -1;
        int tracking_id = -1;
        Vector2 pos{};
    };
    struct TouchDevice {
        int fd = -1;
        std::string path = "";
        Vector2 scale = {0, 0};
        TouchPoint touch_point[10];  // 真实手指观察用(读线程按槽位记录)
    };

    static constexpr int kByteBitSize = 8;
    static constexpr int kEvByteSize = 64;  // 能力位图长度(unsigned long 个数)
    static constexpr int kMaxTouchPoints = 10;
    // 期望的注入槽位,尽量避开真实手指;实际会按设备声明的槽位上限收敛
    static constexpr int kPreferredSlot = 9;
    static constexpr auto kConfirmUpDelay = std::chrono::milliseconds(30);
    const int kGrab = 1, kUnGrab = 0;

    std::atomic<bool> initialized{false};
    std::atomic<int> screen_orientation{0};
    Vector2 screen_size{0, 0};
    bool is_physical_pos = false;
    bool kpm_active = false;   // KPM 内核注入通道已握手成功
    int kpm_channel_ = 0;      // 1=密钥配对 syscall 2=ctl0 supercall
    std::string kpm_key_;      // APatch 超级密钥(仅 ctl0 回退通道用)
    TouchPoint touch_point;
    TouchDevice touch_device;
    int slot_max = -1;         // 面板声明的 ABS_MT_SLOT 上限(SetTouchDevice 探测)
    int exit_fd = -1;  // eventfd,用于唤醒读线程退出
    bool grabbed = false;
    std::thread read_thread;
    std::thread confirm_thread;
    std::mutex write_mutex;         // 串行化对触摸节点的 write(不跨系统调用自旋)
    std::mutex confirm_mutex;       // 保护 confirm_mask / touch_point
    std::condition_variable confirm_cv;
    // per-slot 待补发抬起位图:Up(slot) 置位,宽限期内该 slot 的 Down/Move
    // 清位,到期对置位 slot 补发一次抬起。多 slot 并存时互不误取消。
    std::uint32_t confirm_mask = 0;
    TouchEventCallback touch_event_callback = nullptr;

    // slot 合法性:面板声明范围内且在观察表界内;非法返回 false(调用方丢弃)
    bool IsValidSlot(int slot) const;
public:
    static TouchManager& GetInstance() {
        static TouchManager instance;
        return instance;
    };
    // Init 即首次配对自检:返回 kReady 才可用;通道不可用时直接返回失败,
    // 绝不静默降级到有痕触摸 —— 退位换 uinput / InputManager 由 app 决定。
    // kpm_key 可选:默认走编译期密钥配对 syscall 通道(见 touch_pairing.h,
    // 跨 root 管理器);kpm_key 仅作为 ctl0 通道(需 APatch 超级密钥)的凭证
    TouchStatus Init(const Vector2& screen_size,bool is_physical_pos,
                     const char* kpm_key = nullptr);
    // 注意:Close 需要与 Down/Move/Up 无并发(通常只在退出时调用)
    void Close();
    // 默认槽位版(touch_point.slot,上游兼容)
    void Down(const Vector2& pos);
    void Move(const Vector2& pos);
    void Up();
    // 指定槽位版:多指注入(root_daemon 用 8=自瞄 / 9=扳机;也可对真实手指
    // 所在 slot 发 Up 实现"抬起玩家的摇杆指")。越界 slot 记日志后丢弃。
    void Down(const Vector2& pos, int slot);
    void Move(const Vector2& pos, int slot);
    void Up(int slot);
    // 面板声明的 slot 上限;未初始化或探测失败返回 -1
    int GetSlotMax() const { return slot_max; }
    void SetScreenOrientation(int orientation);
    void SetTouchEventCallback(TouchEventCallback callback);
    bool SetGrabEnabled(bool enable);
};
