package io.github.love3025.yolovaim.injector

import android.content.Context
import android.util.Log
import io.github.love3025.yolovaim.model.TouchMethod

/**
 * Stealth(KPM 内核注入)客户端 —— 复用 RootInjectorClient 的 daemon 进程管理与
 * 行协议(su 起 root_daemon,READY 握手,能力探测,cmdLock 串行),只把注入
 * 命令换成 STEALTH_*。zone 查询 / consumeFireState / keepAlive / HudClient
 * 原样继承:zone 检测由 daemon 里的 touch_core 读者承担(reader-only 初始化,
 * 面板不 grab),HUD 与注入后端无关、继续可用。
 *
 * 无痕通道自检失败(内核未装 inputprobe.kpm、两侧配对盐不一致)时
 * [initRemote] 返回 false。本类不做任何有痕降级 —— 用户选 Stealth 要的就是
 * 无痕,静默换 uinput 会在用户以为无痕的状态下打有痕触摸,比报错更糟。
 */
class KpmInjectorClient(context: Context) : RootInjectorClient(context) {
    companion object {
        private const val TAG = "KpmInjector"
        // 与 touch_core.h 的 TOUCH_VIRTUAL_SLOT / TOUCH_TRIGGER_SLOT 对齐;
        // daemon 的 zone 检测已排除这两个 slot,注入回环不会污染手指判定
        private const val SLOT_AIM = 8
        private const val SLOT_TRIGGER = 9
    }

    /** initRemote 失败时的人话原因(供 FloatService 展示),成功时为 null */
    var lastInitError: String? = null
        private set

    override fun initRemote(): Boolean {
        // 成功回 OK:<slot_max>;失败回 ERR:stealth no_channel|no_device|internal
        lastInitError = null
        val resp = sendCmd("STEALTH_INIT")
        if (resp != null && resp.startsWith("OK:")) {
            val slotMax = resp.removePrefix("OK:").trim().toIntOrNull() ?: -1
            if (slotMax in 0 until SLOT_TRIGGER) {
                Log.e(TAG, "面板 slot 上限 $slotMax < ${SLOT_TRIGGER},注入可能被内核静默丢弃")
            }
            Log.i(TAG, "Stealth 通道就绪 (slot_max=$slotMax)")
            return true
        }
        lastInitError = when {
            resp == null -> "daemon 无响应"
            resp.contains("no_channel") ->
                "内核未加载 inputprobe.kpm 或两侧 touch_pairing.h 盐不一致"
            resp.contains("no_device") -> "未找到触摸屏设备"
            else -> "内部错误($resp)"
        }
        Log.e(TAG, "Stealth 初始化失败: $lastInitError — 不降级,请换 Uinput/InputManager 或修复 KPM")
        return false
    }

    override fun tap(x: Int, y: Int) {
        // 与 Root 路径的 tap 同槽位(自瞄指);坐标语义一致,仅命令不同
        sendOk("STEALTH_DOWN $SLOT_AIM $x $y")
        Thread.sleep(8)
        sendOk("STEALTH_UP $SLOT_AIM")
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        sendOk("STEALTH_DOWN $SLOT_AIM $x1 $y1")
        if (durationMs > 0) {
            val steps = maxOf(1, durationMs / 8)
            for (i in 1..steps) {
                val cx = x1 + (x2 - x1) * i / steps
                val cy = y1 + (y2 - y1) * i / steps
                sendOk("STEALTH_MOVE $SLOT_AIM $cx $cy")
                Thread.sleep(8)
            }
            sendOk("STEALTH_UP $SLOT_AIM")
        }
        // durationMs == 0: stay down (caller will moveTo + lift later)
    }

    // 每帧自瞄热路径 —— '!' fire-and-forget,不阻塞推理循环
    override fun moveTo(x: Int, y: Int) {
        sendNoReply("STEALTH_MOVE $SLOT_AIM $x $y")
    }

    override fun lift() {
        sendNoReply("STEALTH_UP $SLOT_AIM")
    }

    override fun triggerDown(x: Int, y: Int) {
        sendOk("STEALTH_DOWN $SLOT_TRIGGER $x $y")
    }

    override fun triggerUp() {
        sendOk("STEALTH_UP $SLOT_TRIGGER")
    }

    override fun triggerTap(x: Int, y: Int, durationMs: Int) {
        sendOk("STEALTH_DOWN $SLOT_TRIGGER $x $y")
        if (durationMs > 0) Thread.sleep(durationMs.toLong())
        sendOk("STEALTH_UP $SLOT_TRIGGER")
    }

    override fun setOrientationConfig(landscapeStart: Boolean) {
        // touchc 的 orientation 1 与 touch_core 的 landscape 分支是同一个旋转
        // (phys.x = 短边 - y, phys.y = x);180°/270°(2/3)暂无信息源
        sendOk("STEALTH_SET_ORIENTATION ${if (landscapeStart) 1 else 0}")
    }

    override fun blockPhysicalTouch() {
        // Root 路径这里是 no-op;Stealth 路径有 EVIOCGRAB 语义。
        // 注意:grab 期间 KPM 注入同样不送达游戏 —— 菜单独占时不要注入
        sendOk("STEALTH_GRAB 1")
    }

    override fun unblockPhysicalTouch() {
        sendOk("STEALTH_GRAB 0")
    }

    override fun setInputMethod(method: TouchMethod) {
        // Stealth 固定走 KPM 内核通道,method 参数忽略(与 Root 路径同构)
    }
}
