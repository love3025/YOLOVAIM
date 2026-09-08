package io.github.love3025.yolovaim.model

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.exp

/**
 * 瞄准的触点状态与目标运动估计。
 *
 * PID 的内部状态（积分、上一帧误差、指令速率）已经搬到 [AimPidCore] —— 它们是
 * 控制律的私有状态，放在这个跨模块共享的 data class 里，除了让控制律没法单测，
 * 也让「哪里该清、哪里不该清」散落在好几个文件里。
 */
data class AimingState(
    var pointerDown: Boolean = false,
    var centerX: Float = 0f,
    var centerY: Float = 0f,
    var startX: Float = 0f,
    var startY: Float = 0f,
    var lockedTarget: RectF? = null,
    var maxDragDist: Float = 400f,
    var swayTimer: Int = 0,
    var swayPulse: Int = 0,
    var swayDuration: Int = 10,
    var swayDir: Float = 0f,
    var prevTargetX: Float = Float.NaN,
    var prevTargetY: Float = Float.NaN,
    /** 目标速度(px/s)，已滤波。**单位是 px/s，不是 px/帧** —— 见 [updateVelocity]。 */
    var smoothVelX: Float = 0f,
    var smoothVelY: Float = 0f
) {
    companion object {
        /**
         * 静止目标的检测噪声门限(px/s)。
         *
         * 检测框静止时也有 ±1~2px/帧 的抖动。门限必须是 **px/s** 而不是 px/帧：
         * 写成后者的话，同一个物理速度在 30fps 和 50fps 下会落在门限两侧 ——
         * 一边被判成静止、一边被前馈放大，这本身就是一种帧率相关的抖动。
         * 2px @ [AimPidCore.DT_REF] = 100px/s，与旧实现在 50fps 下等价。
         */
        private const val VEL_NOISE_PX_S = 100f

        /** 一阶低通在 [AimPidCore.DT_REF] 处的系数，旧实现的 0.3。 */
        private const val VEL_EMA_AT_REF = 0.3f

        /**
         * `ln(1 - VEL_EMA_AT_REF)` = ln(0.7)。用它把 `0.7^(dt/dtRef)` 写成
         * `exp(LN_KEEP · dt/dtRef)`，避免每帧一次 `Math.pow`。
         *
         * 与 `AimController.DECAY_EXP_PER_SEC` 同一个理由，也同一份实测：
         * pow 一般按 `exp(y·log x)` 实现，aarch64 OpenJDK 上 97.3ns vs 19.3ns。
         * 这里 x 是编译期常量，log 没有必要留在每帧的热路径上。
         */
        private const val LN_KEEP = -0.35667494f

        private const val MIN_DT = 0.001f
    }

    /**
     * 用本帧目标中心更新速度估计。
     *
     * @param dtSec 距上一次调用的实际间隔(秒)。旧实现算的是「每帧位移」并用固定
     *   EMA 系数，于是估出来的速度和滤波时间常数都随帧率变 —— 前馈项 Kf 因此
     *   在帧率波动时忽大忽小。现在速度是 px/s，滤波系数按 `1-(1-a)^(dt/dtRef)`
     *   折算，时间常数固定。
     */
    fun updateVelocity(cx: Float, cy: Float, dtSec: Float) {
        val dt = dtSec.coerceIn(MIN_DT, AimPidCore.MAX_DT)
        if (!prevTargetX.isNaN() && !prevTargetY.isNaN()) {
            val rawVx = (cx - prevTargetX) / dt
            val rawVy = (cy - prevTargetY) / dt
            val cleanVx = if (abs(rawVx) < VEL_NOISE_PX_S) 0f else rawVx
            val cleanVy = if (abs(rawVy) < VEL_NOISE_PX_S) 0f else rawVy
            val a = 1f - exp(LN_KEEP * dt / AimPidCore.DT_REF)
            smoothVelX += (cleanVx - smoothVelX) * a
            smoothVelY += (cleanVy - smoothVelY) * a
        }
        prevTargetX = cx
        prevTargetY = cy
    }

    fun reset() {
        pointerDown = false
        lockedTarget = null
        prevTargetX = Float.NaN; prevTargetY = Float.NaN
        smoothVelX = 0f; smoothVelY = 0f
        swayTimer = (30..90).random(); swayPulse = 0; swayDir = 0f
    }
}
