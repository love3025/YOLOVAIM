#include "touchc.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

#include "linux/input.h"
#include "vector2.h"
#include "touch_pairing.h"

// ---------------------------------------------------------------------------
// KPM 通道
// 1) 密钥配对 syscall(首选):与 root 管理器流派无关(APatch/KPatch-Next/KSU/
//    Magisk+KPM 通吃),不需要 APatch 超级密钥,凭编译期配对密钥认证
//    (见 touch_pairing.h)—— 方案 §4.2
// 2) supercall ctl0(回退):官方 KP 通道,需要超级密钥,仅作调试兼容
// 与 kpms/inputprobe 0.4.4 的协议对齐(kTcVers 为最低版本检查,兼容 >= 0.4.0)
// ---------------------------------------------------------------------------
namespace {
constexpr long kSupercallNr = 45;            // __NR_supercall (KP)
constexpr long kScKpmControl = 0x1022;       // SUPERCALL_KPM_CONTROL
constexpr unsigned kKpmAuthMagic = 1526724574u;  // 0x5AFEC0DE,须与 KPM 一致
constexpr const char *kKpmName = "input-dev-probe";

// 编译期密钥配对通道:通道号 TOUCH_SC_NR、密钥 touch_pair_key() 均来自
// touch_pairing.h(须与 KPM 侧同名头文件一字不差)。只有用相同 包名+盐
// 编译出的 app 才算得出这把钥匙;其它调用者一律穿透成原始 syscall,探测不到。
constexpr unsigned kTcPing = 0x7C01u, kTcDown = 0x7C02u, kTcMove = 0x7C03u,
                   kTcUp = 0x7C04u;
constexpr long kTcVers = 0x400;  // KPM 0.4.0

long KpmSupercall(const char *key, const char *args, char *out, int outlen) {
    const long cmd = (0x0D08L << 32) | (0x1158L << 16) | kScKpmControl;
    return syscall(kSupercallNr, key, cmd, kKpmName, args, out, outlen);
}

long TouchSyscall(unsigned long cmd, unsigned long a2 = 0, unsigned long a3 = 0,
                  unsigned long a4 = 0) {
    static const unsigned long long key = touch_pair_key();  // 编译期常量,算一次
    return syscall(TOUCH_SC_NR, key, cmd, a2, a3, a4);
}
}  // namespace

// 将物理坐标转换为屏幕坐标(或逻辑坐标),并进行比例缩放
Vector2 TouchManager::ConvertPhysicalToScreenAndScale(const Vector2 &corrd,
                                                      const Vector2 &scale) {
    Vector2 scale_pos = {corrd.x * scale.x, corrd.y * scale.y};
    Vector2 pos{0, 0};

    switch (screen_orientation.load(std::memory_order_relaxed)) {
        case 0: pos = {scale_pos.x, scale_pos.y}; break;
        case 1: pos = {scale_pos.y, screen_size.y - scale_pos.x}; break;
        case 3: pos = {screen_size.x - scale_pos.y, scale_pos.x}; break;
        default:
            pos = {screen_size.y - scale_pos.x, screen_size.x - scale_pos.y};
            break;
    }
    return pos;
}
// 获取所有输入设备的节点路径
std::vector<std::string> TouchManager::ScanInputDevicesPath() {
    std::vector<std::string> input_device_paths;
    input_device_paths.reserve(18);
    DIR *input_dir = opendir("/dev/input/");
    if (input_dir == nullptr) {
        std::cerr << "打开/dev/input/失败!" << std::endl;
        return input_device_paths;
    }
    struct dirent *file_entry;
    while ((file_entry = readdir(input_dir)) != nullptr) {
        const std::string filename = file_entry->d_name;
        if (filename.find("event") == 0) {
            input_device_paths.emplace_back("/dev/input/" + filename);
        }
    }
    closedir(input_dir);
    return input_device_paths;
}

// 检查指定位是否被设置能力
bool TouchManager::CheckKeyBit(unsigned int bit,
                               const unsigned long *arr) const noexcept {
    constexpr auto long_bits = sizeof(unsigned long) * kByteBitSize;
    if (bit >= static_cast<unsigned int>(kEvByteSize) * long_bits) { return false; }
    const auto mask = 1UL << (bit % long_bits);
    return (arr[bit / long_bits] & mask) != 0;
}

// 检查是否为触摸设备
bool TouchManager::CheckIsTouchDevice(int fd) const noexcept {
    std::array<unsigned long, kEvByteSize> abs_bits{};
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits.data()) < 0) {
        return false;
    }
    bool has_slot = CheckKeyBit(ABS_MT_SLOT, abs_bits.data());
    bool has_tracking_id = CheckKeyBit(ABS_MT_TRACKING_ID, abs_bits.data());
    bool has_pos_x = CheckKeyBit(ABS_MT_POSITION_X, abs_bits.data());
    bool has_pos_y = CheckKeyBit(ABS_MT_POSITION_Y, abs_bits.data());

    return has_slot && has_tracking_id && has_pos_x && has_pos_y;
}

// 获取系统触摸设备的数据
bool TouchManager::SetTouchDevice(
    const std::vector<std::string> &input_device_paths) {
    for (const auto &path : input_device_paths) {
        // O_RDONLY:fd 只用于只读探测(坐标换算/真实手指观察/grab),
        // 库不再向节点写入任何报文,注入全部走 KPM 内核通道。
        // O_NONBLOCK:读线程靠 poll 驱动,Close 时也不会卡在阻塞 read 上
        int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd == -1) { continue; }
        if (!CheckIsTouchDevice(fd)) {
            close(fd);
            continue;
        }

        // 槽位自适应:内核会静默丢弃超出设备声明范围的 ABS_MT_SLOT,
        // 槽数不足 10 的机型上固定用 slot 9 会导致注入完全无效
        int slot = kPreferredSlot;
        input_absinfo slot_info{};
        if (ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &slot_info) == 0 &&
            slot_info.maximum >= 0) {
            slot = std::min(kPreferredSlot, static_cast<int>(slot_info.maximum));
            slot_max = static_cast<int>(slot_info.maximum);
        } else {
            slot_max = kPreferredSlot;  // 探测失败按惯例假设够用
        }
        if (slot < 0) {
            std::cerr << "触摸设备无可用槽位: " << path << std::endl;
            close(fd);
            continue;
        }

        input_absinfo abs_x{}, abs_y{};
        ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &abs_x);
        ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &abs_y);
        if (abs_x.maximum <= 0 || abs_y.maximum <= 0) {
            close(fd);
            continue;
        }

        touch_device.scale = {screen_size.y / abs_x.maximum,
                              screen_size.x / abs_y.maximum};
        touch_device.fd = fd;
        touch_device.path = path;
        touch_point.slot = slot;
        return true;
    }
    return false;
}
// 注册真实手指观察回调(可选,须在 Init 前调用)
void TouchManager::SetTouchEventCallback(TouchEventCallback callback) {
    touch_event_callback = std::move(callback);
}

// 读取触摸事件
void TouchManager::ReadTouchEvent() {
    struct pollfd fds[2] = {};
    fds[0].fd = touch_device.fd;
    fds[0].events = POLLIN;
    fds[1].fd = exit_fd;
    fds[1].events = POLLIN;

    struct input_event input_events[64];
    int current_slot = 0;
    bool observed_down = false;      // 观察到的真实主触点当前是否按下
    Vector2 last_observed_pos{};     // 抬起时回调用的最后已知坐标
    while (initialized.load(std::memory_order_relaxed)) {
        const int poll_ret = poll(fds, 2, 200);
        if (poll_ret < 0) {
            if (errno == EINTR) { continue; }
            break;
        }
        if (fds[1].revents != 0) { return; }  // Close() 通知退出
        if (poll_ret == 0 || (fds[0].revents & POLLIN) == 0) { continue; }

        ssize_t read_size;
        while ((read_size = read(touch_device.fd, input_events,
                                 sizeof(input_events))) > 0) {
            if (read_size % static_cast<ssize_t>(sizeof(input_event)) != 0) {
                continue;
            }
            const int event_count =
                read_size / static_cast<ssize_t>(sizeof(input_event));

            for (int i = 0; i < event_count; i++) {
                struct input_event &event = input_events[i];
                if (event.type == EV_ABS && event.code == ABS_MT_SLOT) {
                    if (event.value < 0 ||
                        event.value >= kMaxTouchPoints) {
                        current_slot = 0;
                        continue;
                    }
                    current_slot = event.value;
                    continue;
                }
                // 过滤自己注入的事件:KPM 经 input_event 注入的报文同样会
                // 分发回本 reader,不过滤的话注入轨迹会被当成真实手指回调出去。
                // 注意只滤默认槽位 —— 多槽注入的观察过滤由调用方负责
                // (root_daemon 不注册回调,此线程根本不会启动)
                if (current_slot == touch_point.slot) {
                    continue;
                }
                if (event.type == EV_ABS) {
                    if (event.code == ABS_MT_TRACKING_ID) {
                        touch_device.touch_point[current_slot].tracking_id =
                            event.value;
                    } else if (event.code == ABS_MT_POSITION_X) {
                        touch_device.touch_point[current_slot].pos.x =
                            event.value;
                    } else if (event.code == ABS_MT_POSITION_Y) {
                        touch_device.touch_point[current_slot].pos.y =
                            event.value;
                    }
                    continue;
                }
                if (event.type == EV_SYN && event.code == SYN_REPORT) {
                    if (touch_event_callback == nullptr) { continue; }
                    if (touch_device.touch_point[current_slot].tracking_id >= 0) {
                        const auto pos = ConvertPhysicalToScreenAndScale(
                            touch_device.touch_point[current_slot].pos,
                            touch_device.scale);
                        // 首次按下→Down,后续→Move(单主触点语义,与旧实现一致)
                        touch_event_callback(observed_down ? TouchPhase::Move
                                                           : TouchPhase::Down,
                                             pos);
                        observed_down = true;
                        last_observed_pos = pos;
                    } else if (observed_down) {
                        // 该触点 tracking_id 归 -1:抬起
                        touch_event_callback(TouchPhase::Up, last_observed_pos);
                        observed_down = false;
                    }
                }
            }
        }
        if (read_size < 0 && errno != EAGAIN && errno != EWOULDBLOCK &&
            errno != EINTR) {
            // 读失败,退避 2ms,避免热自旋
            const struct timespec ts = {0, 2 * 1000 * 1000};
            nanosleep(&ts, nullptr);
        }
    }
}

/*
添加此线程原因:在很小概率下,有抬起事件没有被上传,导致卡屏
作用:Up() 之后延迟补发一次抬起;期间出现新的 Down 则取消。
旧实现常驻 10Hz 往触摸节点打 slot/-1 事件,既是检测特征又空转烧 CPU,已废弃。

per-slot 位图版:Up(slot) 只置自己 slot 的位;宽限期内该 slot 的 Down/Move
清自己的位,别的 slot 不受影响。到期时对所有置位 slot 各补发一次抬起
(对已抬起的 slot 再发一次 up 无副作用,KPM 侧幂等)。
*/
void TouchManager::ConfirmUpThread() {
    std::unique_lock<std::mutex> lock(confirm_mutex);
    while (true) {
        confirm_cv.wait(lock, [this] { return confirm_mask != 0 || !initialized.load(); });
        if (!initialized.load()) { return; }
        // 宽限期内出现新的 Down 会清掉对应 slot 的位,取消该 slot 的补发
        const bool cancelled = confirm_cv.wait_for(lock, kConfirmUpDelay, [this] {
            return confirm_mask == 0 || !initialized.load();
        });
        if (!initialized.load()) { return; }
        if (cancelled) { continue; }
        const std::uint32_t pending = confirm_mask;
        confirm_mask = 0;
        lock.unlock();
        for (int slot = 0; slot < kMaxTouchPoints; slot++) {
            if (pending & (1u << slot)) {
                SendTouchReport(false, false, Vector2{0, 0}, slot);
            }
        }
        lock.lock();
    }
}

// 发送一次触摸报文:只走 KPM 内核通道 —— trkid 由内核分配,帧对齐与压力
// 合成也在内核侧完成。库不做有痕退位,通道不可用在 Init 阶段就自检失败
bool TouchManager::SendTouchReport(bool down, bool is_down_event,
                                   const Vector2 &physical_pos, int slot) {
    std::lock_guard<std::mutex> guard(write_mutex);
    return KpmSend(down, is_down_event, physical_pos, slot);
}

// KPM 通道握手:密钥配对 syscall 优先(编译期配对密钥、跨 root 管理器),
// 失败再试 supercall ctl0(需密钥)。都不通则 Init 判定 kNoStealthChannel,不降级。
bool TouchManager::KpmInit() {
    // 1) 密钥配对 syscall:ping 返回协议版本
    if (TouchSyscall(kTcPing) >= kTcVers) {
        kpm_channel_ = 1;
        return true;
    }
    // 2) ctl0:ping + auth(仅在提供了超级密钥时)
    if (!kpm_key_.empty()) {
        char out[128] = {0};
        if (KpmSupercall(kpm_key_.c_str(), "ping", out, sizeof(out)) == 0 &&
            std::string(out).rfind("pong", 0) == 0) {
            char args[48];
            snprintf(args, sizeof(args), "auth %u", kKpmAuthMagic);
            if (KpmSupercall(kpm_key_.c_str(), args, out, sizeof(out)) == 0 &&
                std::string(out).rfind("authed", 0) == 0) {
                kpm_channel_ = 2;
                return true;
            }
        }
    }
    kpm_channel_ = 0;
    return false;
}

// 走 KPM 通道的一次报文:坐标换算到设备单位,trkid 由内核分配
bool TouchManager::KpmSend(bool down, bool is_down_event,
                           const Vector2 &physical_pos, int slot) {
    if (kpm_channel_ == 1) {
        // 密钥配对 syscall:一次调用一次报文
        if (down) {
            const int x = static_cast<int>(physical_pos.x / touch_device.scale.x);
            const int y = static_cast<int>(physical_pos.y / touch_device.scale.y);
            return TouchSyscall(is_down_event ? kTcDown : kTcMove,
                                static_cast<unsigned long>(slot),
                                static_cast<unsigned long>(x),
                                static_cast<unsigned long>(y)) == 0;
        }
        return TouchSyscall(kTcUp,
                            static_cast<unsigned long>(slot)) == 0;
    }
    // ctl0 回退
    char args[96], out[64];
    if (down) {
        const int x = static_cast<int>(physical_pos.x / touch_device.scale.x);
        const int y = static_cast<int>(physical_pos.y / touch_device.scale.y);
        snprintf(args, sizeof(args), "%s %d %d %d",
                 is_down_event ? "down" : "move", slot, x, y);
    } else {
        snprintf(args, sizeof(args), "up %d", slot);
    }
    const long rc = KpmSupercall(kpm_key_.c_str(), args, out, sizeof(out));
    if (rc != 0) {
        std::cerr << "KPM 注入失败: rc=" << rc << " resp=" << out << std::endl;
        return false;
    }
    return true;
}

void TouchManager::RequestConfirmUp(int slot) {
    std::lock_guard<std::mutex> guard(confirm_mutex);
    confirm_mask |= (1u << slot);
    confirm_cv.notify_all();
}

TouchManager::TouchStatus TouchManager::Init(const Vector2 &screen_size,
                                             bool is_physical_pos,
                                             const char *kpm_key) {
    Close();
    this->is_physical_pos = is_physical_pos;
    kpm_key_ = kpm_key ? kpm_key : "";
    kpm_active = false;
    slot_max = -1;
    if (screen_size.x <= 0 || screen_size.y <= 0) {
        std::cerr << "screen_size 非法: " << screen_size.x << "x"
                  << screen_size.y << std::endl;
        return TouchStatus::kInternalError;
    }
    if (screen_size.x > screen_size.y) {
        this->screen_size = screen_size;
    } else {
        this->screen_size = {screen_size.y, screen_size.x};
    }

    const std::vector<std::string> input_devices_path = ScanInputDevicesPath();
    if (input_devices_path.empty()) {
        std::cerr << "未找到输入设备!" << std::endl;
        return TouchStatus::kNoTouchDevice;
    }

    if (!SetTouchDevice(input_devices_path)) {
        std::cerr << "未找到触摸设备!" << std::endl;
        return TouchStatus::kNoTouchDevice;
    }

    // KPM 通道握手:唯一的注入后端,不通即自检失败,不做任何有痕降级。
    // 退位换 uinput / InputManager 等方案由 app 决定
    kpm_active = KpmInit();
    if (!kpm_active) {
        std::cerr << "无痕通道不可用: 内核未加载 KPM 或配对失败"
                     "(密钥配对 syscall 与 ctl0 均未握手)" << std::endl;
        Close();
        return TouchStatus::kNoStealthChannel;
    }
    std::cerr << (kpm_channel_ == 1 ? "触摸后端: KPM(密钥配对 syscall 通道)"
                                    : "触摸后端: KPM(ctl0 通道)")
              << " slot_max=" << slot_max << std::endl;

    // 读线程只为把真实手指回调给 app;没注册回调就不起(纯注入零开销)
    if (touch_event_callback != nullptr) {
        exit_fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
        if (exit_fd == -1) {
            std::cerr << "创建 eventfd 失败!" << std::endl;
            Close();
            return TouchStatus::kInternalError;
        }
    }

    initialized.store(true);
    if (exit_fd != -1) {
        read_thread = std::thread(&TouchManager::ReadTouchEvent, this);
    }
    confirm_thread = std::thread(&TouchManager::ConfirmUpThread, this);
    return TouchStatus::kReady;
}

void TouchManager::Close() {
    initialized.store(false);

    // 先 join 两个线程,最后才 close fd,避免对已关闭/已复用的 fd 读写
    confirm_cv.notify_all();
    if (confirm_thread.joinable()) { confirm_thread.join(); }

    if (exit_fd != -1) {
        uint64_t wake = 1;
        const ssize_t unused = write(exit_fd, &wake, sizeof(wake));
        (void)unused;
    }
    if (read_thread.joinable()) { read_thread.join(); }

    if (touch_device.fd != -1) {
        if (grabbed) {
            ioctl(touch_device.fd, EVIOCGRAB, kUnGrab);
            grabbed = false;
        }
        close(touch_device.fd);
        touch_device.fd = -1;
    }
    if (exit_fd != -1) {
        close(exit_fd);
        exit_fd = -1;
    }
    touch_device.path.clear();
}

Vector2 TouchManager::ConvertScreenToPhysicalNoScale(Vector2 screen_pos) {
    Vector2 physical_pos = {0, 0};
    switch (screen_orientation.load(std::memory_order_relaxed)) {
        case 0:
            physical_pos.x = screen_pos.x;
            physical_pos.y = screen_pos.y;
            break;
        case 1:
            physical_pos.x = screen_size.y - screen_pos.y;
            physical_pos.y = screen_pos.x;
            break;
        case 3:
            physical_pos.x = screen_pos.y;
            physical_pos.y = screen_size.x - screen_pos.x;
            break;
        default:
            physical_pos.x = screen_size.y - screen_pos.y;
            physical_pos.y = screen_size.x - screen_pos.x;
            break;
    }
    return physical_pos;
}

bool TouchManager::IsValidSlot(int slot) const {
    return slot >= 0 && slot < kMaxTouchPoints &&
           (slot_max < 0 || slot <= slot_max);
}

void TouchManager::Down(const Vector2 &pos) { Down(pos, touch_point.slot); }

// Move 不重置压力渐入、不发 down 命令(trkid 由 KPM 内核侧管理)
void TouchManager::Move(const Vector2 &pos) { Move(pos, touch_point.slot); }

void TouchManager::Up() { Up(touch_point.slot); }

void TouchManager::Down(const Vector2 &pos, int slot) {
    if (!initialized.load()) { return; }
    if (!IsValidSlot(slot)) {
        std::cerr << "Down: 非法 slot " << slot << "(slot_max=" << slot_max
                  << "),丢弃" << std::endl;
        return;
    }
    Vector2 physical_pos;
    if (is_physical_pos) {
        physical_pos = pos;
    } else {
        physical_pos = ConvertScreenToPhysicalNoScale(pos);
    }
    {
        std::lock_guard<std::mutex> guard(confirm_mutex);
        confirm_mask &= ~(1u << slot);  // 新的按下,取消该 slot 待补发的抬起
    }
    SendTouchReport(true, true, physical_pos, slot);
}

// Move 不重置压力渐入、不发 down 命令(trkid 由 KPM 内核侧管理)
void TouchManager::Move(const Vector2 &pos, int slot) {
    if (!initialized.load()) { return; }
    if (!IsValidSlot(slot)) {
        std::cerr << "Move: 非法 slot " << slot << "(slot_max=" << slot_max
                  << "),丢弃" << std::endl;
        return;
    }
    Vector2 physical_pos;
    if (is_physical_pos) {
        physical_pos = pos;
    } else {
        physical_pos = ConvertScreenToPhysicalNoScale(pos);
    }
    {
        std::lock_guard<std::mutex> guard(confirm_mutex);
        confirm_mask &= ~(1u << slot);  // 移动说明触摸活着,取消该 slot 的补发
    }
    SendTouchReport(true, false, physical_pos, slot);
}

void TouchManager::Up(int slot) {
    if (!initialized.load()) { return; }
    if (!IsValidSlot(slot)) {
        std::cerr << "Up: 非法 slot " << slot << "(slot_max=" << slot_max
                  << "),丢弃" << std::endl;
        return;
    }
    SendTouchReport(false, false, Vector2{0, 0}, slot);
    // 无论本次写入是否成功,都安排一次延迟补发,
    // 防止极小概率的抬起丢失导致卡屏;30ms 内有新的 Down 会自动取消
    RequestConfirmUp(slot);
}

void TouchManager::SetScreenOrientation(int screen_orientation) {
    this->screen_orientation.store(screen_orientation, std::memory_order_relaxed);
}

/*
菜单模式:grab 后本 fd 成为该节点唯一的 evdev 接收者,
真实触摸不再穿透到游戏,悬浮窗交互独占;解除后恢复。
注意:grab 期间注入的事件同样只会送达本进程,菜单打开时不要注入。
*/
bool TouchManager::SetGrabEnabled(bool enable) {
    if (touch_device.fd == -1) { return false; }
    if (enable == grabbed) { return true; }
    if (ioctl(touch_device.fd, EVIOCGRAB, enable ? kGrab : kUnGrab) < 0) {
        std::cerr << "EVIOCGRAB 失败: errno=" << errno << std::endl;
        return false;
    }
    grabbed = enable;
    return true;
}
