package io.github.love3025.yolovaim.controller

import android.util.Log
import io.github.love3025.yolovaim.injector.TouchInjectorInterface
import io.github.love3025.yolovaim.model.RecoilCore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 压枪开环驱动 —— C 方案的核心：**开火即压枪，不经推理**。
 *
 * ## 为什么需要它(相对推理环驱动压枪的三条延迟)
 *
 * 1. **时钟**：推理环 30~60fps tick 一次,开火边沿到状态机推进要等下一个帧边界
 *    (≤33ms)。本线程 125Hz(8ms),边沿到推进只剩一次 IPC 往返(几 ms)。
 * 2. **门**：旧实现里 offset 只有走进 executeAiming()(只在 aimbotOn && 有目标
 *    && holdToAimActive 分支调用)才兑现成手指位移 —— **没目标时压枪完全不表达**,
 *    严格说是无穷大延迟。这正是用户感知「压枪时断时续」的主因。
 * 3. **表达低通**:经 PID 兑现时,纵向增益被 kpYRatio 压低,稳态滞后几十 px。
 *
 * ## 拓扑:同一根虚拟手指,两套驱动分场景接管
 *
 * - **闭环(有目标,自瞄在拖)**:保持现状 —— 偏移加在瞄点上、经 PID 兑现。
 *   这条路唯一的真优势是反馈自适应游戏灵敏度,不能丢。
 * - **开环(本类,兜底态)**:闭环失效超过 [CLOSED_LOST_GRACE_MS] 时,由本线程直接
 *   对虚拟手指(TOUCH_VIRTUAL_SLOT,即 moveTo 通道)发恒速下滑 MOVE。
 *   进入开环零成本(它就是兜底),退出由推理环把手指位置带走 —— driver 写前
 *   重读所有权旗,不覆盖闭环的写入(见 [armed])。
 *
 * ## 断触保证(贴墙交火场景的契约)
 *
 * 目标缩回墙里 → 推理环 target==null → closedActive 不再刷新 → 宽限期后 driver
 * 接管,手指**不抬**,继续恒速下滑;目标再出来 → 推理环重新选靶、从手指当前位置
 * 追新瞄点。全程零 lift。**开火中(含预算期内)绝不抬指**是压枪的生命周期,
 * 由 [RecoilCore] 的开火时间戳独占,与触点抬起解耦。
 *
 * ## 开环不驱动的条件(即使闭环也失效)
 *
 * - 压枪开关关 / 停止(与推理环同一语义)
 * - 不在开火且预算耗尽(offset 在衰减 → 不需要主动驱动;衰减 ≠ 手指上滑,
 *   主动回拉会和玩家抢控制权,正确语义是停火超时后由闭环侧 lift)
 *
 * ## IPC 预算
 *
 * 本线程每 tick 只在需要时发一次 moveTo(整数坐标不变则跳过,与 emitMove 同一
 * 去重策略);consumeFireState 每 tick 一次 —— 这从推理环的热路径上拿走了一半
 * (推理环仍要查扳机的 manualFire,两边各自消费互不干扰:consumeFireState 是
 * 取走即清零的,约定 **driver 是 taps 的唯一消费者**,推理环退回只读电平)。
 *
 * 线程模型:单线程时钟,daemon。核心状态 [core] 由本线程独占写;推理线程只读
 * [RecoilCore.offsetY](经 volatile 转发,见 FloatService 里的 recoilShare)。
 */
class RecoilDriver(
    private val touchClient: () -> TouchInjectorInterface?,
    /** 闭环是否活跃:推理环每帧刷新([noteClosedAlive]),陈旧即视为闭环失效。 */
    private val core: RecoilCore
) {

    companion object {
        private const val TAG = "RecoilDriver"

        /** 驱动 tick 周期(ms)。125Hz:比推理帧率高一个量级,又不至于空转烧 CPU。 */
        const val TICK_MS = 8L

        /**
         * 闭环失效宽限期(ms)。推理帧间隔的 3 倍以上(30fps 帧 33ms,150ms 覆盖
         * 采集卡顿与两帧丢检);宽限期内闭环刚丢目标时不开环驱动,避免检测框
         * 逐帧闪进闪出造成「PID 速度/恒速」逐帧换手的速度颤动。
         */
        const val CLOSED_LOST_GRACE_MS = 150L

        /** 开环 MOVE 与 emitMove 同一去重:坐标不变不发。 */
        private const val UNSENT = Int.MIN_VALUE
    }

    /** 最近一次推理环宣告闭环活跃的时刻(System.nanoTime 时基)。0 = 从未。 */
    private val lastClosedAliveNs = AtomicLong(0L)

    /**
     * 开环驱动是否持有手指所有权。写前重读:闭环(PID)正在拖时本旗为 false,
     * driver 跳过本 tick 的 MOVE,让推理环的写入生效 —— 这是「模式切换不回跳」
     * 的廉价实现(物理上注入命令串行,防的是逻辑覆盖)。
     */
    @Volatile
    var openLoopActive = false
        private set

    /** 推理线程每帧调用:宣告「闭环正在驱动手指」。 */
    fun noteClosedAlive() { lastClosedAliveNs.set(System.nanoTime()) }

    private val running = AtomicBoolean(false)
    private var clock = Executors.newSingleThreadScheduledExecutor { r ->
        Thread({
            // 与推理/扳机线程同优先级:这条线程决定压枪的表达时刻。
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            r.run()
        }, "yolovaim-recoil").apply { isDaemon = true }
    }

    // ── 开环侧的手指状态镜像(与 AimingState 独立,只在 driver 线程读写) ──
    private var fingerX = UNSENT
    private var fingerY = UNSENT
    /** driver 是否已把注入指按下(裸 moveTo 会被注入层丢弃,见 tick 里的说明)。 */
    private var fingerPlanted = false

    // ── 参数快照(@Volatile:推理/UI 线程写,driver 线程读) ──
    @Volatile var enabled = false
    @Volatile var speed = 0.5f
    @Volatile var rangePx = 200f
    @Volatile var resetMs = 300
    /** 落点区域:无目标开环下压时手指所在的安全镜头区(瞄准区)。 */
    @Volatile var homeX = 0
    @Volatile var homeY = 0

    /** 排障计数。 */
    @Volatile var openLoopTicks = 0L
    @Volatile var movesSent = 0L

    fun start() {
        if (running.getAndSet(true)) return
        // FixedDelay 而不是 FixedRate:每一 tick 从墙钟重算,线程被抢占后不补跑
        // 一串(与 TriggerController 时钟同一约定)。
        try {
            clock.scheduleWithFixedDelay({ tick() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // start/shutdown 竞争时 schedule 抛 RejectedExecutionException
            Log.e(TAG, "recoil clock start: ${e.message}")
            running.set(false)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        clock.shutdownNow()
        // 重建:shutdownNow 之后的 executor 不能再 schedule
        clock = Executors.newSingleThreadScheduledExecutor { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                r.run()
            }, "yolovaim-recoil").apply { isDaemon = true }
        }
        openLoopActive = false
        fingerX = UNSENT; fingerY = UNSENT
        fingerPlanted = false
    }

    /**
     * 一 tick:采样开火 → 推进状态机 → 决定是否开环驱动手指。
     * 任何异常都必须吞掉:scheduleWithFixedDelay 的任务抛一次就不再被调度
     * (与 TriggerController.tick 同一条教训 —— 静默失效比崩溃糟)。
     */
    private fun tick() {
        try {
            val client = touchClient() ?: return

            // ── 1. 开火采样(本线程独占消费 taps) ──
            val packed = client.consumeFireState()
            val held: Boolean
            val taps: Int
            if (packed >= 0) {
                held = (packed and 1) != 0
                taps = packed ushr 1
            } else {
                // 注入层不支持打包查询:退回电平(短点击会漏,但通路已验证)。
                held = client.isFingerInFireZone()
                taps = 0
            }

            // ── 2. 状态机推进(与帧率无关,tick 频率只影响表达粒度) ──
            val nowNs = System.nanoTime()
            core.tick(
                enabled = enabled, held = held, taps = taps,
                dtSec = TICK_MS / 1000f, nowMs = nowNs / 1_000_000L,
                speed = speed, rangePx = rangePx, resetMs = resetMs
            )

            // ── 3. 开环驱动决策 ──
            val closedAlive = nowNs - lastClosedAliveNs.get() < CLOSED_LOST_GRACE_MS * 1_000_000L
            val wantDrive = enabled && !closedAlive && core.offsetY > 0.5f && core.offsetY < rangePx
            if (wantDrive) {
                if (!openLoopActive) {
                    // 接管。两种来路,落指方式不同:
                    //  a) 闭环刚丢目标、手指仍按着(AimingState.pointerDown=true,
                    //     FloatService 的 lift 条件因 openLoopActive=true 而没抬它)
                    //     → 什么都不用做,从手指当前位置继续下滑。
                    //  b) 从没按过(没目标时直接开火) → 裸 moveTo 会被注入层静默
                    //     丢弃(touch_move: slot 上无注入指直接 return),必须先
                    //     swipe(x,x,0) 落指 —— 与 AimController 落指同一命令。
                    // 落点:优先瞄准区(home,用户配好的安全镜头区);闭环留下的
                    // 手指位置继续用(连续性)。
                    if (fingerX == UNSENT) { fingerX = homeX; fingerY = homeY }
                    if (!fingerPlanted) {
                        client.swipe(fingerX, fingerY, fingerX, fingerY, 0)
                        fingerPlanted = true
                        Log.d(TAG, "open-loop take over (planted): finger=($fingerX,$fingerY)")
                    } else {
                        Log.d(TAG, "open-loop take over (inherited): finger=($fingerX,$fingerY)")
                    }
                    openLoopActive = true
                }
                val rate = RecoilCore.RATE_SLOW + (RecoilCore.RATE_FAST - RecoilCore.RATE_SLOW) * speed
                fingerY += (rate * (TICK_MS / 1000f)).toInt().coerceAtLeast(1)
                // 去重:整数坐标不变不发(与 emitMove 同一策略)
                if (fingerY != lastSentY || fingerX != lastSentX) {
                    client.moveTo(fingerX, fingerY)
                    lastSentX = fingerX; lastSentY = fingerY
                    movesSent++
                }
                openLoopTicks++
            } else if (openLoopActive) {
                // 释放所有权(**不抬指**):闭环接管,或压枪停/衰减到底/到顶。
                // 闭环接管时 fingerPlanted 保持 true —— 手指仍按着,PID 从当前
                // 位置继续拖;下次开环再接管时不需要重新落指。真正的抬指交给
                // FloatService 的 lift 条件(压枪停/停火超时后自然走到)。
                openLoopActive = false
                Log.d(TAG, "open-loop release: closedAlive=$closedAlive offset=${core.offsetY.toInt()}px")
            }
        } catch (e: Exception) {
            Log.e(TAG, "recoil tick: ${e.message}")
        }
    }

    private var lastSentX = UNSENT
    private var lastSentY = UNSENT
}
