package io.github.love3025.yolovaim.controller
import io.github.love3025.yolovaim.model.DetectionInfo

import android.graphics.RectF
import android.util.Log
import io.github.love3025.yolovaim.service.FloatService
import io.github.love3025.yolovaim.injector.TouchInjectorInterface
import io.github.love3025.yolovaim.model.AreaConfig
import io.github.love3025.yolovaim.model.AimingState
import io.github.love3025.yolovaim.model.BezierMover
import kotlin.math.max
import kotlin.math.min

class AimController(
    private val service: FloatService,
    private val touchClient: () -> TouchInjectorInterface?,
    private val savedAreas: () -> List<AreaConfig>
) {
    companion object {
        private const val TAG = "AimController"
        private const val LAT_TAG = "YolovaimLatency"
        private const val AREA_INDEX_AIM = 2

        /**
         * 压枪的内部标度。都不是可调项 —— 面板上暴露的是 0~1 的强度/速度，
         * 这里负责把它们换算成屏幕像素。
         *
         * **标度必须是屏幕空间，不能按目标框高取比例。** 枪口爬升是镜头的旋转，
         * 画面上的位移像素数由 FOV 与分辨率决定，与目标远近无关；而框高与距离
         * 强相关。`7a1e202` 把 range/rate 改成 `strength * boxH` 后，远距离
         * (实测框高 15~20px) 的上限塌到 17px、速率塌到 2.4px/s，比上游慢 37 倍，
         * 等于让远处目标自动关掉压枪 —— 而远距离恰恰最需要它。实测日志见
         * recoil-improvement-plan.md §11。
         *
         * HOLD_RATE_* 的锚点：上游 `recoilStrength * 3f` 每帧、以 30fps 换算
         * 就是 90px/s，那一版实机确认「有明显效果」。故速度 50% 取 90px/s。
         */
        /** 长按下压速率(屏幕 px/s)：速度 0% -> 30，50% -> 90，100% -> 150。 */
        private const val HOLD_RATE_SLOW = 30f
        private const val HOLD_RATE_FAST = 150f
        /**
         * 连点下压速度：每枪 kick 的落位时间常数 τ(ms)。
         * 速度 0% -> 73ms，**60% -> 36.4ms**，100% -> 12ms。
         *
         * 60% 那一档恰好等于历史上写死的 `pow(0.4, dt*30)` 的等效 τ
         * (-1/(30·ln0.4) = 36.4ms)，而 recoilTapSpeed 的默认值就是 0.6 ——
         * 所以老配置升级后连点手感不变。
         *
         * 快端的实测代价(30fps，一帧 33ms)：τ=36ms 首帧交付 60%、τ=20ms 81%、
         * τ=12ms 94%。也就是说 100% 档基本是把一枪的量单帧打满 = 阶跃输入，而
         * Y 轴的 kp/kd 被 kpYRatio/kdYRatio 特意压低过(抑制纵向震荡)，可能会抖。
         * 这一档仍然留着：该由使用者在真机上判断，不该由代码替他封掉。面板提示
         * 里标了这件事。
         */
        private const val TAP_TAU_SLOW_MS = 73f
        private const val TAP_TAU_FAST_MS = 12f
        /**
         * 回落衰减速率常数(1/s) = 原来 `pow(0.7, dt*30)` 的等价指数形式
         * (30·ln0.7 = -10.70024)。换 exp 是纯性能：pow 一般按 exp(y·log x) 实现，
         * 实测(aarch64 OpenJDK) 97.3ns vs 19.3ns，float32 下偏差 3e-7。
         */
        private const val DECAY_EXP_PER_SEC = -10.70024f
        /**
         * 长按斜坡的起步门限(ms)。按下不足此时长的算"点一下"，只走 tap kick，
         * 不上斜坡。
         *
         * 这道门限把**按压时长**挡在连点通路之外，是「连点下压力度」那句"与按住
         * 多久无关"能成立的前提。上游的 held 是"手指在开火区"的电平，连点时那
         * 几十毫秒它同样是 true，斜坡便跟着跑，每枪实得 `kick + rate*按压时长`。
         * 后果是点得快(60ms)每枪压得少、点得慢(150ms)每枪压得多 —— 方向正好是
         * 反的，决定总爬升的是开枪次数而不是按压时长；而且这是条不可调的噪声，
         * 仿真实测它能让每枪的量随按压时长漂移 38%。
         *
         * 150ms 的取法：连点的按压时长典型 40~120ms，点射/长按 200ms 以上。
         * 两个方向的代价不对称 —— 门限偏低会让粗糙的点击重新漏进斜坡(就是这里
         * 要修的 bug)；偏高只是让全自动晚 150ms 起步，恒速斜坡因此永久滞后
         * `rate*0.15` = 4.5~22px，相对 range 满量程 400px 是 1~5%，斜坡见顶后
         * 差值归零，且第一枪的 kick 本来就盖在那段上。所以宁可取大。
         */
        private const val HOLD_ONSET_MS = 150L
        /**
         * 下压范围满量程 = 屏幕高度 × 该比例。1080p 上 = 400px，与上游
         * MAX_OFFSET 一致；用比例而非绝对值，换分辨率不必重调。
         */
        private const val RANGE_MAX_RATIO = 0.37f
        /** 连点每一下的量(屏幕 px)：连点强度 50% -> 每枪 20px。 */
        private const val TAP_KICK_MAX_PX = 40f
        /** 绝对保险，防止异常参数把偏移推到离谱的值。 */
        private const val MAX_OFFSET = 600f

        /**
         * Per-frame latency tracing, compiled out by default.
         *
         * The 0.05ms threshold on these sites never gated anything in practice:
         * executeAiming() contains the injector call, which is a cross-process
         * round-trip and so always exceeds it. They therefore ran a nanoTime
         * pair, a String.format and a logd write on every aiming frame.
         */
        private const val TRACE = false
    }

    // PID parameters
    var kp = 0.07f
    var ki = 0.001f
    var kd = 0.05f

    // Y-axis gain scaling (prevents vertical oscillation when Kp is high)
    var kpYRatio = 0.6f
    var kdYRatio = 0.85f

    // Derivative EMA filter alpha (1.0 = no filter, 0.0 = freeze D term)
    var derivFilterAlpha = 0.4f

    // Integral separation threshold (in px) — disable Ki above this error magnitude
    var integralSeparationThresh = 200f

    // Integral clamp (anti-windup). Ki=0.001 is small enough that 100 is fine.
    private val integralLimit = 100f

    // Velocity damping: subtracts a fraction of the previous frame's move
    // from the current output. This is the standard "rate feedback" used in
    // motion control to brake approach velocity and prevent overshoot bounce
    // on large aim movements. 0.0 = no damping, 0.5 = aggressive damping.
    var velocityDamping = 0.35f

    // Per-frame output clamp (px). Lower = smoother but slower. 1200 was
    // 72000 px/s at 60fps — too aggressive, caused large-sweep overshoot.
    var maxPerFrame = 600f

    // Feedforward gain (F term). Compensates target velocity before the
    // position error builds up, reducing lag on moving targets. 0.0 = pure PID.
    // Y axis uses a reduced ratio to avoid amplifying vertical jitter.
    var kf = 0.05f
    private val kfYRatio = 0.7f
    private val kfGain = 2.5f  // internal amplifier: maps slider kf=0.2 to effective 0.5 lead

    // Aim settings
    var aimMode = 0 // 0=PID, 1=Bezier
    var bezierDuration = 30
    var bezierControlOffset = 0.3f
    var bezierRandomSpread = 0.1f
    var convergeThresh = 10f
    var aimFov = 50  // px — circle radius around crosshair; targets outside this are ignored
    var dynamicFov = false  // shrink FOV onto target during aim to avoid retargeting
    var fovZoomDelay = 0  // ms — hold time at shrunken FOV after target lost before expanding back
    var aimOffsetYRatio = 0f
    var aimSwayAmplitude = 0
    var aimPrediction = 0
    var aimHoldEnabled = false

    // Recoil compensation
    var recoilEnabled = false
    /**
     * 长按下压力度(0.0 ~ 1.0)：按住开火键最终压到多深(斜坡的终点)。
     *
     * **只夹长按那一份。** 曾经它是长按与连点共用的总上限，后果是只玩连点的人
     * 把它留在 5% 最小值时，连点累计被夹在 20px(一枪的量)上，而面板上没有任何
     * 提示是这个滑块在卡他。现在 hold/tap 两份偏移分开累计、各有上限，共用的只
     * 剩一个内部安全值(见 updateRecoil 里的 totalMax)。
     */
    var recoilStrength = 0.5f
    /** 连点下压力度 0.0 ~ 1.0：每点一次开火键压多少(× TAP_KICK_MAX_PX)，0 = 关闭。 */
    var recoilTapStrength = 0.5f
    /** 长按下压速度 0.0 ~ 1.0 → HOLD_RATE_* 的 px/s：按住时每秒压多少。 */
    var recoilSpeed = 0.5f
    /** 连点下压速度 0.0 ~ 1.0 → TAP_TAU_* 的 τ：每枪那份量多快落到位。默认 0.6 = 历史值。 */
    var recoilTapSpeed = 0.6f
    var recoilResetIntervalMs = 300   // 松开开火区超过此时长才开始回落；0 = 立即重置
    /**
     * 压枪标度的参考高度 = 采集高度(px)。由 FloatService 在建立采集后写入。
     * 兜底 1080 只在还没建立采集时用得到，那时也不会有推理帧。
     */
    var recoilRefHeight = 1080f
    /** 对外的总偏移 = holdOffsetY + tapOffsetY，由 updateRecoil() 末尾算出。 */
    private var recoilOffsetY = 0f
    /** 长按斜坡那一份，上限 = 长按下压力度。 */
    private var holdOffsetY = 0f
    /** 连点累计那一份，上限是内部安全值，不受长按力度影响。 */
    private var tapOffsetY = 0f
    private var lastFireMs = 0L
    private var pendingKick = 0f
    /** 当前这次按压的起点(ms)；0 = 没在按。用来区分"点一下"和"按住"。 */
    private var pressStartMs = 0L

    // Class filtering
    var aimClasses: MutableSet<Int> = mutableSetOf()
    var priorityClass: Int = -1
    var classAimOffsets: Map<Int, Float> = emptyMap()
    var boxAimRatio = 0.5f
    var classBoxAimRatios: Map<Int, Float> = emptyMap()

    // State
    val aimingState = AimingState()
    private val bezierMover = BezierMover()

    // Dynamic FOV state — `dynamicCurrentFov` is the actual radius used for
    // target selection and rendered as the FOV circle; it ranges from
    // `aimFov` (full circle when no target) down to `targetMaxDim + padding`
    // while locked onto a target.
    private var dynamicCurrentFov = 50f
    private var lostTargetMs: Long = -1

    val effectiveFov: Int
        get() = dynamicCurrentFov.toInt().coerceIn(20, maxOf(aimFov, 20))

    fun resetDynamicFov() {
        dynamicCurrentFov = aimFov.toFloat()
        lostTargetMs = -1
    }

    fun selectTarget(dets: List<DetectionInfo>, cx: Float, cy: Float): DetectionInfo? {
        val t0 = System.nanoTime()
        val fovSq = (effectiveFov * effectiveFov).toFloat()

        fun inFov(bcx: Float, bcy: Float): Boolean {
            val dx = bcx - cx; val dy = bcy - cy
            return dx * dx + dy * dy <= fovSq
        }

        val lock = aimingState.lockedTarget
        if (lock != null) {
            // Drop the lock if it has drifted outside the FOV circle — otherwise
            // we keep aiming at a stale target after the user has rotated away.
            if (!inFov(lock.centerX(), lock.centerY())) {
                aimingState.lockedTarget = null
            } else {
                val lockCx = lock.centerX()
                val lockCy = lock.centerY()
                var minDist = Float.MAX_VALUE
                var bestDet: DetectionInfo? = null
                for (det in dets) {
                    val r = det.rect
                    val bcx = (r.left + r.right) * 0.5f
                    val bcy = (r.top + r.bottom) * 0.5f
                    val d = (bcx - lockCx) * (bcx - lockCx) + (bcy - lockCy) * (bcy - lockCy)
                    if (d < minDist) {
                        minDist = d
                        bestDet = det
                    }
                }
                if (minDist < 22500f && bestDet != null) {
                    lock.set(bestDet.rect.centerX(), bestDet.rect.centerY(), bestDet.rect.centerX(), bestDet.rect.centerY())
                    return bestDet
                }
                aimingState.lockedTarget = null
            }
        }

        // Priority: if priorityClass is set and present, only consider that class
        val candidates = if (priorityClass >= 0) {
            val prioritized = dets.filter { it.classId == priorityClass }
            if (prioritized.isNotEmpty()) prioritized else dets
        } else dets

        // Pick closest to crosshair WITHIN FOV
        var bestDistSq = Float.MAX_VALUE
        var bestDet: DetectionInfo? = null
        for (det in candidates) {
            val r = det.rect
            val bcx = (r.left + r.right) * 0.5f
            val bcy = (r.top + r.bottom) * 0.5f
            val dx = bcx - cx; val dy = bcy - cy
            val dSq = dx * dx + dy * dy
            if (dSq > fovSq) continue
            if (dSq < bestDistSq) {
                bestDistSq = dSq
                bestDet = det
            }
        }
        if (bestDet != null) {
            val bcx = bestDet.rect.centerX()
            val bcy = bestDet.rect.centerY()
            aimingState.lockedTarget = RectF(bcx, bcy, bcx, bcy)
        }
        if (TRACE) {
            val dtMs = (System.nanoTime() - t0) / 1e6
            if (dtMs > 0.05) Log.d(LAT_TAG, String.format(java.util.Locale.US, "selectTarget=%.2fms candidates=%d", dtMs, dets.size))
        }
        return bestDet
    }

    // Drives dynamic FOV: shrinks toward (target.maxDim + pad) while locked on
    // a target; stays shrunken for `fovZoomDelay` ms after target lost; expands
    // back to `aimFov` if no target reappears within the grace window.
    fun updateDynamicFov(target: DetectionInfo?, nowMs: Long) {
        if (!dynamicFov) {
            dynamicCurrentFov = aimFov.toFloat()
            lostTargetMs = -1
            return
        }
        val maxFov = aimFov.toFloat()
        if (target != null) {
            val r = target.rect
            // FOV must contain the target. Use the larger of width/height plus
            // a small padding so the box edge clears the circle, but never
            // smaller than the slider minimum and never larger than aimFov.
            val targetMax = max(r.width(), r.height())
            val desiredMin = (targetMax + 8f).coerceIn(20f, maxFov)
            // Smoothly shrink toward desiredMin — 6 px/frame is roughly 360 px/s
            // at 60fps, fast enough to react before the aim crosses the target
            // but slow enough to be visible to the user.
            dynamicCurrentFov = max(desiredMin, dynamicCurrentFov - 6f).coerceAtMost(maxFov)
            lostTargetMs = -1
        } else {
            if (lostTargetMs < 0) lostTargetMs = nowMs
            val dt = nowMs - lostTargetMs
            if (dt >= fovZoomDelay) {
                dynamicCurrentFov = min(maxFov, dynamicCurrentFov + 6f)
            }
        }
    }

    fun executeAiming(targetX: Float, targetY: Float, cx: Float, cy: Float) {
        val t0 = System.nanoTime()
        // 压枪：偏移量由 updateRecoil() 每帧维护，这里只读。
        // 累加/清零绝不能放回这里 —— executeAiming() 只在选到目标时才被调用
        // (FloatService 的 target != null 分支)，把状态机放进来就等于
        // 「无目标 → 既不累加也不清零」，偏移被冻结着带到下一次交火。
        var adjustedTargetY = targetY
        if (recoilEnabled) adjustedTargetY += recoilOffsetY
        if (aimMode == 1) {
            executeAimingBezier(targetX, adjustedTargetY, cx, cy)
        } else {
            executeAimingPid(targetX, adjustedTargetY, cx, cy)
        }
        if (TRACE) {
            val dtMs = (System.nanoTime() - t0) / 1e6
            if (dtMs > 0.05) Log.d(LAT_TAG, String.format(java.util.Locale.US, "executeAiming=%.2fms mode=%d", dtMs, aimMode))
        }
    }

    private fun executeAimingBezier(targetX: Float, targetY: Float, cx: Float, cy: Float) {
        val errorX = targetX - cx
        val errorY = targetY - cy

        if (!aimingState.pointerDown) {
            if (Math.abs(errorX) < convergeThresh && Math.abs(errorY) < convergeThresh) return

            val aimArea = savedAreas().getOrNull(AREA_INDEX_AIM)
            if (aimArea != null) {
                aimingState.centerX = aimArea.x + (Math.random() * aimArea.width).toFloat()
                aimingState.centerY = aimArea.y + (Math.random() * aimArea.height).toFloat()
            } else {
                aimingState.centerX = cx
                aimingState.centerY = cy
            }
            aimingState.startX = aimingState.centerX
            aimingState.startY = aimingState.centerY

            touchClient()?.swipe(aimingState.centerX.toInt(), aimingState.centerY.toInt(), aimingState.centerX.toInt(), aimingState.centerY.toInt(), 0)
            aimingState.pointerDown = true
            val now = System.currentTimeMillis()
            val dist = Math.sqrt((errorX * errorX + errorY * errorY).toDouble()).toFloat()
            val duration = (bezierDuration * 5 + dist * 0.3f).toInt().coerceIn(200, 800)
            bezierMover.start(now, now + duration)
        } else {
            if (Math.abs(errorX) < convergeThresh && Math.abs(errorY) < convergeThresh) {
                // 收敛后保持按住，检测框消失时由 InferenceManager 负责 lift
                bezierMover.cancel()
                return
            }

            // Each frame: compute remaining error, apply smoothstep ratio as this frame's move
            // Restart bezier immediately if it finished but error remains
            if (!bezierMover.isActive()) {
                val now = System.currentTimeMillis()
                val dist = Math.sqrt((errorX * errorX + errorY * errorY).toDouble()).toFloat()
                val duration = (bezierDuration * 5 + dist * 0.3f).toInt().coerceIn(200, 800)
                bezierMover.start(now, now + duration)
            }
            val t = bezierMover.tickRatio(System.currentTimeMillis())
            val moveX = errorX * t
            val moveY = errorY * t
            if (aimSwayAmplitude > 0) aimingState.centerY += computeSway()
            aimingState.centerX += moveX
            aimingState.centerY += moveY
            if (applyDragSafety()) return
            touchClient()?.moveTo(aimingState.centerX.toInt(), aimingState.centerY.toInt())
        }
    }

    private fun executeAimingPid(targetX: Float, targetY: Float, cx: Float, cy: Float) {
        val errorX = targetX - cx
        val errorY = targetY - cy
        if (!aimingState.pointerDown) {
            if (Math.abs(errorX) < convergeThresh && Math.abs(errorY) < convergeThresh) return
            val aimArea = savedAreas().getOrNull(AREA_INDEX_AIM)
            if (aimArea != null) {
                aimingState.centerX = aimArea.x + (Math.random() * aimArea.width).toFloat()
                aimingState.centerY = aimArea.y + (Math.random() * aimArea.height).toFloat()
            } else {
                aimingState.centerX = cx
                aimingState.centerY = cy
            }
            aimingState.startX = aimingState.centerX
            aimingState.startY = aimingState.centerY
            aimingState.prevErrorX = 0f
            aimingState.prevErrorY = 0f
            aimingState.integralX = 0f
            aimingState.integralY = 0f
            aimingState.derivFilteredX = 0f
            aimingState.derivFilteredY = 0f
            aimingState.prevFrameX = aimingState.centerX
            aimingState.prevFrameY = aimingState.centerY
            aimingState.prevTargetX = Float.NaN
            aimingState.prevTargetY = Float.NaN
            aimingState.smoothVelX = 0f
            aimingState.smoothVelY = 0f
            touchClient()?.swipe(aimingState.centerX.toInt(), aimingState.centerY.toInt(), aimingState.centerX.toInt(), aimingState.centerY.toInt(), 0)
            aimingState.pointerDown = true
            Log.d(TAG, "aim DOWN at (${aimingState.centerX}, ${aimingState.centerY}) target=($targetX, $targetY)")
        } else {
            if (Math.abs(errorX) < convergeThresh && Math.abs(errorY) < convergeThresh) {
                // 收敛后保持按住，检测框消失时由 InferenceManager 负责 lift
                return
            }

            // Integral separation: only accumulate Ki when error is in the controlled band.
            // Above the threshold the Kp term already drives the response, accumulating
            // integral would cause overshoot and the vertical-axis oscillation reported by users.
            val sep = integralSeparationThresh
            if (Math.abs(errorX) < sep) {
                if (errorX * aimingState.prevErrorX <= 0) aimingState.integralX = 0f
                aimingState.integralX += errorX
                aimingState.integralX = aimingState.integralX.coerceIn(-integralLimit, integralLimit)
            } else {
                aimingState.integralX *= 0.5f
            }
            if (Math.abs(errorY) < sep) {
                if (errorY * aimingState.prevErrorY <= 0) aimingState.integralY = 0f
                aimingState.integralY += errorY
                aimingState.integralY = aimingState.integralY.coerceIn(-integralLimit, integralLimit)
            } else {
                aimingState.integralY *= 0.5f
            }

            // EMA filter on derivative: detection box jitters frame-to-frame, and raw
            // dError/dt amplifies that jitter into D-term spikes that drive oscillation.
            val alpha = derivFilterAlpha
            val rawDerivX = errorX - aimingState.prevErrorX
            val rawDerivY = errorY - aimingState.prevErrorY
            aimingState.derivFilteredX = alpha * rawDerivX + (1f - alpha) * aimingState.derivFilteredX
            aimingState.derivFilteredY = alpha * rawDerivY + (1f - alpha) * aimingState.derivFilteredY

            // Per-axis gain: Y axis gets reduced Kp/Kd to prevent vertical oscillation
            // when Kp is high. The touch-injection pipeline and target Y motion both
            // contribute more noise on the Y axis than X.
            val kpY = kp * kpYRatio
            val kdY = kd * kdYRatio

            // Raw PID output
            var rawX = errorX * kp + aimingState.integralX * ki + aimingState.derivFilteredX * kd
            var rawY = errorY * kpY + aimingState.integralY * ki + aimingState.derivFilteredY * kdY

            // Feedforward (F term): apply target velocity directly to output so the
            // aim cursor leads a moving target instead of always chasing the lag.
            // smoothVelX/Y is EMA-filtered in FloatService via AimingState.updateVelocity.
            // kfGain amplifies the slider value so kf=0.2 produces ~60% lead (visible
            // at typical 5-20 px/frame target speeds). Without it, kf * velocity is
            // 1-2 px/frame and gets drowned out by Kp*error.
            rawX += aimingState.smoothVelX * kf * kfGain
            rawY += aimingState.smoothVelY * kf * kfGain * kfYRatio

            if (aimSwayAmplitude > 0) rawY += computeSway()
            aimingState.prevErrorX = errorX
            aimingState.prevErrorY = errorY

            // Velocity damping (rate feedback): subtract a fraction of the previous
            // frame's actual displacement. This is standard motion-control damping —
            // it brakes the approach velocity as we get near the target and prevents
            // the overshoot-bounce cycle on large aim sweeps. Works in addition to Kd.
            val prevVelX = aimingState.centerX - aimingState.prevFrameX
            val prevVelY = aimingState.centerY - aimingState.prevFrameY
            rawX -= prevVelX * velocityDamping
            rawY -= prevVelY * velocityDamping

            val moveDist = Math.sqrt((rawX * rawX + rawY * rawY).toDouble()).toFloat()
            var moveX = rawX
            var moveY = rawY
            if (moveDist > maxPerFrame) {
                moveX = rawX / moveDist * maxPerFrame
                moveY = rawY / moveDist * maxPerFrame
            }
            aimingState.prevFrameX = aimingState.centerX
            aimingState.prevFrameY = aimingState.centerY
            aimingState.centerX += moveX
            aimingState.centerY += moveY
            if (applyDragSafety()) return
            touchClient()?.moveTo(aimingState.centerX.toInt(), aimingState.centerY.toInt())
        }
    }

    private fun computeSway(): Float {
        if (aimSwayAmplitude <= 0) return 0f
        if (aimingState.swayPulse > 0) {
            aimingState.swayPulse--
            val half = aimingState.swayDuration / 2
            val t = if (aimingState.swayPulse > half) (aimingState.swayDuration - aimingState.swayPulse) / half.toFloat() else aimingState.swayPulse / half.toFloat()
            val sway = aimingState.swayDir * aimSwayAmplitude * t
            if (aimingState.swayPulse == 0) aimingState.swayTimer = (30..90).random()
            return sway
        } else {
            aimingState.swayTimer--
            if (aimingState.swayTimer <= 0) {
                aimingState.swayDuration = (6..16).random()
                aimingState.swayPulse = aimingState.swayDuration
                aimingState.swayDir = if (Math.random() > 0.5f) 1f else -1f
            }
            return 0f
        }
    }

    private fun applyDragSafety(): Boolean {
        val dx = aimingState.centerX - aimingState.startX
        val dy = aimingState.centerY - aimingState.startY
        val dragDist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (dragDist > aimingState.maxDragDist) {
            touchClient()?.lift()
            aimingState.pointerDown = false
            aimingState.lockedTarget = null
            bezierMover.cancel()
            Log.d(TAG, "aim edge lift at (${aimingState.centerX}, ${aimingState.centerY}) drag=$dragDist")
            return true
        }
        return false
    }

    // 刻意不在这里调 resetRecoil()。lift() 的调用点之一是 FloatService 里
    // 「pointerDown 且 (!aimbotOn || !hasDetects || !holdToAimActive)」那条，
    // 其中 !hasDetects 就是「画面暂时没目标」。在这里清压枪，等于把持续开火
    // 途中目标被烟雾/掩体挡一帧就重置的行为装回去 —— 比原来的 bug 更糟，因为
    // 它发生在手指还按着、枪口还在爬的时候。压枪的生命周期由 updateRecoil()
    // 的开火时间戳独占，触点抬起是瞄准逻辑的事，两者不耦合。
    fun lift() {
        touchClient()?.lift()
        aimingState.pointerDown = false
        aimingState.lockedTarget = null
    }

    /**
     * 压枪状态机 —— 必须每推理帧调用一次，且与「有没有目标」无关。
     *
     * 参数是**对称的 2×2**，长按和连点各有自己的力度与速度，互不共用：
     *
     * |      | 力度(压多少)                    | 速度(多快压到)                  |
     * |------|--------------------------------|--------------------------------|
     * | 长按 | recoilStrength = 斜坡终点深度   | recoilSpeed = px/s (HOLD_RATE_*) |
     * | 连点 | recoilTapStrength = 每枪的量    | recoilTapSpeed = 落位 τ (TAP_TAU_*) |
     *
     * 对称不是为了好看，是因为不对称过：以前 recoilStrength 是两条路共用的总上限，
     * 于是长按"没有力度"(它的力度被那个共用滑块吃掉了)、连点"没有速度"(τ 写死)，
     * 而且只玩连点的人会被长按侧的值卡住。现在 holdOffsetY / tapOffsetY 分开累计、
     * 各有上限，唯一共用的是 totalMax —— 一个内部安全线，不是滑块。
     *
     * 两个"速度"方向一致(越高越快)但量纲不同，这是两边动力学不同决定的：长按对抗
     * 连续爬升，所以是 px/s；连点每枪是一次冲量，量已由力度定死，速度只能是"这份
     * 量多快落到位"。连点的等效补偿速率 = `每枪量 × 点击频率` —— 由玩家的手指决定
     * 并随点击快慢自动缩放(仿真：3/5/8 发每秒 → 60/100/160 px/s)，天然低于全自动，
     * 也本该如此：开枪次数少，枪口爬升就少。
     *
     * recoilTapStrength 与按压时长无关，这条由 HOLD_ONSET_MS 保证；在加门限之前
     * 它只是句谎话(实测漂移 38%)，见那里的说明。
     *
     * 四者都是屏幕空间量。不要改回按目标框高取比例 —— 那与
     * aimOffsetYRatio / boxAimRatio 是不同性质的量：那两个决定"打身体哪个部位"
     * (该随框高缩放)，压枪对抗的是镜头爬升(与距离无关)。混用会让远距离失效。
     *
     * 为什么速度必须独立出来：枪械后坐力大时，开火头 1-2 秒枪口爬升快过压枪，
     * 准星会先跑到头部上方，等后坐力见顶才开始往下走，整个过程比应有的多花
     * 1-3 秒。这时候调强度没用 —— 强度只是把终点放得更低，不改变到达终点的
     * 快慢，甚至因为范围变大而更慢。要压住前期，得让它走得更快。
     *
     * 时间基而不是帧基：原来是每帧固定量，30fps 按住一秒下压 45px、60fps 就是
     * 90px，`4ee9e9e` 提帧之后压枪同比变快。现在按 dt 累加，帧率无关。
     *
     * kick 与斜坡是加性的，但**按压时长只喂给斜坡这一条**：一次按压若跨过门限，
     * 得到的是"第一枪的 kick + 之后的爬升补偿"，这与真实枪械一致；短于门限就
     * 只有 kick。自动扳机走的是 triggerFired 锁存(准心在目标上就一直 true)，
     * 恒过门限，行为与加门限之前相同 —— 它自己注入的点击在 updateZones() 里被
     * TOUCH_VIRTUAL_SLOT/TOUCH_TRIGGER_SLOT 排除、不计入 taps，所以它只吃斜坡。
     *
     * 松手后不立即清零，而是超过 recoilResetIntervalMs 才开始衰减：
     *  - 半自动连点的枪与枪之间（松手 100-200ms）不该被清零，否则每枪都从 0 压起
     *  - 时间判断是绝对的，只要之后任何一次调用算得出 now - lastFireMs，
     *    就能重建「已经松了多久」。旧实现那种「必须在松手且有目标的那一瞬间
     *    恰好被调用到，否则这次重置永久丢失」的路径因此不复存在
     *  - 衰减而非硬清零：offset 可以累到上百 px，一帧归零会让 errorY 跳变，
     *    而 applyDragSafety() 管的是总位移、不是每帧速率，挡不住这个尖峰，
     *    PID 的微分项会直接吃到
     *
     * 注意这里不看 aimbotOn / holdToAimActive：玩家在开火，游戏内枪口就在爬升，
     * 这与自瞄有没有接管无关。等自瞄再接手时，偏移量应当反映真实的累计爬升。
     */
    fun updateRecoil(held: Boolean, taps: Int, dtSec: Float, nowMs: Long) {
        if (!recoilEnabled) {
            holdOffsetY = 0f; tapOffsetY = 0f; recoilOffsetY = 0f
            pendingKick = 0f; pressStartMs = 0L
            return
        }

        // 按压起点。held 由 false→true 的那一帧记下，松开清零。
        // 注意 held = 手指在开火区 || 自动扳机锁存，两者都算"在开火"。
        if (held) { if (pressStartMs == 0L) pressStartMs = nowMs } else pressStartMs = 0L

        // 面板滑块本就是 0~1，这两道夹取守的是手改 config.json 的情形：负值会让
        // 斜坡反向爬升、让 τ 插值到 0 或负数(exp 的结果反号、pendingKick 自增不
        // 收敛)。面板的 0~100% 是这两个量唯一的真值来源。
        val speed = recoilSpeed.coerceIn(0f, 1f)
        val tapSpeed = recoilTapSpeed.coerceIn(0f, 1f)

        // 长按的终点深度 = 长按下压力度。**只夹长按那一份。**
        val holdRange = (recoilStrength * RANGE_MAX_RATIO * recoilRefHeight)
            .coerceAtMost(MAX_OFFSET)
        // 两份偏移之和的物理上限：屏幕高的 RANGE_MAX_RATIO。不设滑块 —— 它不是
        // 手感参数，而是"别把准星压到目标脚下去"的安全线，正常使用碰不到它。
        // 连点那一份单独也用它当上限：连点压多少该由「每枪的量 × 点了几枪」决定，
        // 不该被长按侧的力度throttle 住(那正是这次要修的老毛病)。
        val totalMax = (RANGE_MAX_RATIO * recoilRefHeight).coerceAtMost(MAX_OFFSET)

        // ── 连点：每检测到一次按下，加一份固定量。与按住多久无关。 ──
        if (taps > 0 && recoilTapStrength > 0f) {
            pendingKick += recoilTapStrength * TAP_KICK_MAX_PX * taps
            lastFireMs = nowMs
        }
        // kick 摊到之后几帧释放而不是一帧打满：它进的是 PID 的目标值，而 Y 轴的
        // kp/kd 本就被 kpYRatio/kdYRatio 特意压低来抑制纵向震荡，一次性十几 px
        // 的阶跃正是那个环节最不擅长吃的输入。
        //
        // 释放快慢 = 连点下压速度(TAP_TAU_*)。指数释放且按 dt 归一：与长按那条
        // 一样必须帧率无关，`1-exp(-dt/τ)` 恰好满足 —— 给定墙钟时间内释放掉的
        // 总量与分几帧走无关。
        if (pendingKick > 0f) {
            val tauMs = TAP_TAU_SLOW_MS + (TAP_TAU_FAST_MS - TAP_TAU_SLOW_MS) * tapSpeed
            val take = pendingKick * (1f - Math.exp((-dtSec * 1000f / tauMs).toDouble()).toFloat())
            tapOffsetY += take
            pendingKick -= take
            if (pendingKick < 0.5f) { tapOffsetY += pendingKick; pendingKick = 0f }
        }

        // ── 长按：恒速下压，到 holdRange 为止。 ──
        //
        // 速率直接是 px/s，与力度(=终点深度)彻底无关 —— 两个滑块互不干扰。用绝对
        // 速率而不是 range/sec：后者力度调低时下降也跟着变慢，等于又把两者绑回去。
        //
        // 恒速而不是指数逼近：后者末段无限慢，且不符合真实枪械先爬升后见顶的形状。
        if (held) {
            // lastFireMs 无条件刷：门限只管斜坡要不要走，不改变"正在开火"这个
            // 事实，否则下面的回落会在连点的每次松手之间被触发。
            lastFireMs = nowMs
            if (nowMs - pressStartMs >= HOLD_ONSET_MS) {
                val rate = HOLD_RATE_SLOW + (HOLD_RATE_FAST - HOLD_RATE_SLOW) * speed
                holdOffsetY += rate * dtSec
            }
            // 跨过门限的那一帧会把整个 dtSec 都算进去，多算不超过一帧
            // (30fps × 150px/s ≈ 5px)。为这点误差去拆分帧内时间不值得。
        } else if (recoilResetIntervalMs <= 0) {
            // 间隔 = 0：松开开火区立即重置，不走衰减。
            holdOffsetY = 0f; tapOffsetY = 0f; pendingKick = 0f
        } else if (pendingKick <= 0f && nowMs - lastFireMs > recoilResetIntervalMs) {
            // 衰减系数同样按时间归一，否则这里又会引入一个帧率相关量，把上面刚
            // 换成时间基的意义抵消掉。基准是 0.7/帧 @30fps，见 DECAY_EXP_PER_SEC。
            val k = Math.exp((dtSec * DECAY_EXP_PER_SEC).toDouble()).toFloat()
            holdOffsetY *= k
            tapOffsetY *= k
            if (holdOffsetY < 0.5f) holdOffsetY = 0f
            if (tapOffsetY < 0.5f) tapOffsetY = 0f
        }

        holdOffsetY = holdOffsetY.coerceIn(0f, holdRange)
        tapOffsetY = tapOffsetY.coerceIn(0f, totalMax)
        recoilOffsetY = (holdOffsetY + tapOffsetY).coerceIn(0f, totalMax)
    }

    /** 排障用：外部只读当前偏移量。 */
    val recoilOffsetDebug: Float get() = recoilOffsetY

    fun resetRecoil() {
        recoilOffsetY = 0f
        holdOffsetY = 0f
        tapOffsetY = 0f
        // 一起清掉，否则下一帧 pendingKick 的残量会被加回刚归零的偏移上。
        pendingKick = 0f
        pressStartMs = 0L
    }

    fun reset() {
        aimingState.reset()
        bezierMover.cancel()
        resetRecoil()
    }
}
