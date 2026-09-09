package io.github.love3025.yolovaim.model

import kotlin.math.exp

/**
 * 压枪状态机的纯计算核心 —— 时间基、帧率无关、与「有没有目标」无关。
 *
 * ## 为什么要有这个类
 *
 * 数学原本长在 [io.github.love3025.yolovaim.controller.AimController] 里，
 * 而那个类要 `FloatService`，于是这套状态机**无法单元测试**。压枪恰好是
 * 最容易被帧率/相位问题咬住的代码（历史上三次真机回归都是这一类），
 * 把它抽成纯 Kotlin 之后：
 *   - 恒速斜坡、预算扣减、衰减、上限这些承诺由 `RecoilCoreTest` 直接验证；
 *   - 开环驱动(RecoilDriver,120Hz)与推理环(30~60fps)可以共用同一份状态，
 *     调用频率改变不再改变行为 —— 这正是「帧率无关」要兑现的场景。
 *
 * ## 状态与语义(从 AimController 原样搬来,零行为变更)
 *
 * 参数(全部由调用方持有,这里只算):
 *   speed        0.0~1.0  → 斜坡速率 px/s：30 → 150，线性
 *   rangePx      偏移上限(px)。= 下压范围 × 0.37 × 参考屏高，调用方换算好
 *   resetMs      松开多久后才开始衰减；0 = 预算耗尽后立即清零
 *
 * 输入(每次 tick):
 *   held         本 tick 是否「正在开火」(物理手指在开火区,或扳机锁存)
 *   taps         自上次 tick 以来注入层数到的开火上升沿数
 *   dtSec        真实墙钟帧间隔(调用方负责夹上限,如 0.1s)
 *   nowMs        当前墙钟毫秒
 *
 * 输出:
 *   offsetY      当前压枪偏移(px,恒 ≥ 0,≤ rangePx)
 *
 * ## 三条不变量(测试直接断言)
 *
 * 1. **时间基**：给定墙钟时间内的推进总量与分几帧走无关。
 * 2. **连点每枪恒量**：每发上升沿发一份 FIRE_LATCH_MS 预算,按毫秒精确扣减,
 *    不按帧扣 —— 100ms 窗口在 33ms 帧上不再随相位落 3 或 4 帧。
 * 3. **无上限爬升不可能**：恒速斜坡,coerceIn(0, range) 兜底。
 *
 * 线程模型：单线程独占调用(driver 线程或推理线程,二选一),无锁。
 * offsetY 供跨线程读时,由持有方加 @Volatile 转发。
 */
class RecoilCore {

    companion object {
        /** 斜坡速率锚点(px/s)：速度 0% → 30，100% → 150。50% = 90 = 上游 strength*3f@30fps 的等价值。 */
        const val RATE_SLOW = 30f
        const val RATE_FAST = 150f

        /**
         * 每一发开火发放的推进预算(ms)。连点每枪推进量恒为速率 × 100ms，
         * 与按压时长无关。物理含义 = 枪的循环射速倒数(100ms ↔ 600 RPM)。
         */
        const val FIRE_LATCH_MS = 100f

        /** 未兑现预算上限(发数)：守注入层边沿计数异常暴增,防斜坡在松手后一路推到上限。 */
        const val MAX_PENDING_ROUNDS = 5f

        /** 回落衰减速率常数(1/s) = pow(0.7, dt*30) 的指数等价(30·ln0.7)。 */
        const val DECAY_EXP_PER_SEC = -10.70024f

        /** 偏移低于此值直接清零(衰减尾部的数值噪声)。 */
        const val SNAP_ZERO_PX = 0.5f
    }

    /**
     * 当前压枪偏移(px)。恒 0 ≤ offsetY ≤ rangePx。
     * @Volatile:driver 线程独占写,推理线程经 effectiveAimY 跨线程读 ——
     * 陈旧一个 tick(8ms)无害,但不该读到撕裂的中间态。
     */
    @Volatile
    var offsetY = 0f
        private set

    /** 尚未兑现的开火推进预算(ms)；每发 +FIRE_LATCH_MS，按真实 dt 扣减。 */
    private var fireBudgetMs = 0f

    /** 最近一次「算作正在开火」的时刻(ms)。回落判断的起算点。 */
    private var lastFireMs = 0L

    /**
     * 推进一个 tick。调用方决定频率(推理帧或驱动 tick),语义不变。
     *
     * @param enabled 压枪总开关。false 时状态归零 —— 开关切换是唯一允许
     *   「瞬清」的入口,其余路径全部走衰减。
     */
    fun tick(enabled: Boolean, held: Boolean, taps: Int, dtSec: Float, nowMs: Long,
             speed: Float, rangePx: Float, resetMs: Int) {
        if (!enabled) {
            offsetY = 0f
            fireBudgetMs = 0f
            externalBudgetMs.set(0L)
            return
        }

        // ── 本 tick 该按「开火」推进多久 ──
        // 手指按着时按真实 dt 推进;松开后由预算兜底(每发至少一发的量)。
        // 预算按毫秒精确扣减,不按帧扣:给定墙钟时间内推进总量与分几帧无关。
        // 扳机外部补的预算先并进来(线程安全见 creditExternalShot)。
        drainExternalBudgetLocked()
        val dtMs = dtSec * 1000f
        if (taps > 0) {
            fireBudgetMs = (fireBudgetMs + taps * FIRE_LATCH_MS)
                .coerceAtMost(FIRE_LATCH_MS * MAX_PENDING_ROUNDS)
        }
        val firing = held || fireBudgetMs > 0f
        val advanceSec = (if (held) dtMs else minOf(dtMs, fireBudgetMs)) / 1000f
        fireBudgetMs = (fireBudgetMs - dtMs).coerceAtLeast(0f)

        // 夹取守手改 config.json 的负值(反向爬升 / coerceIn 空区间抛异常)。
        val spd = speed.coerceIn(0f, 1f)
        val range = rangePx.coerceAtLeast(0f)

        if (firing) {
            // lastFireMs 无条件刷:预算只管斜坡要不要走,不改变「正在开火」这个
            // 事实,否则回落会在连点的每次松手之间被误触发。
            lastFireMs = nowMs
            val rate = RATE_SLOW + (RATE_FAST - RATE_SLOW) * spd
            offsetY += rate * advanceSec
        } else if (resetMs <= 0) {
            offsetY = 0f
        } else if (nowMs - lastFireMs > resetMs) {
            // 衰减按时间归一(基准 0.7/帧@30fps),否则这里又引入帧率相关量。
            val k = exp(dtSec * DECAY_EXP_PER_SEC)
            offsetY *= k
            if (offsetY < SNAP_ZERO_PX) offsetY = 0f
        }

        offsetY = offsetY.coerceIn(0f, range)
    }

    /**
     * 外部重置(服务重启/换局)。连预算一起清,否则残留量会在重置后又
     * 白推进最多 FIRE_LATCH_MS。
     */
    fun reset() {
        offsetY = 0f
        fireBudgetMs = 0f
        externalBudgetMs.set(0L)
    }

    /**
     * 线程安全的「补一发开火预算」。给自动扳机用:它注入的点击不经过开火区
     * (TOUCH_TRIGGER_SLOT 被排除),注入层数不到,必须自己报数。
     *
     * 与 [tick] 的 taps 参数是同一语义,但那是 driver 线程独占的普通字段,
     * 而这里从扳机时钟线程并发调用 —— 故单独加原子预算,由 driver 线程在
     * 下一个 tick(≤8ms)合并进主预算。滞后一个 tick 无影响:FIRE_LATCH_MS
     * 的预算本来就是跨多个 tick 扣的。
     */
    fun creditExternalShot() {
        externalBudgetMs.addAndGet(FIRE_LATCH_MS.toLong())
    }

    /** 跨线程预算池(发数),driver 线程每个 tick 开头合并,见 [tick]。 */
    private val externalBudgetMs = java.util.concurrent.atomic.AtomicLong(0L)

    /** driver 线程在 tick 开头调用:把外部补的预算并进来,清零取走。 */
    private fun drainExternalBudgetLocked() {
        val extra = externalBudgetMs.getAndSet(0L)
        if (extra > 0f) {
            fireBudgetMs = (fireBudgetMs + extra)
                .coerceAtMost(FIRE_LATCH_MS * MAX_PENDING_ROUNDS)
        }
    }
}
