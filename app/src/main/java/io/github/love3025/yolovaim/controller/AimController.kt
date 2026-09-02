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
         * 压枪的内部标度。都不是可调项 —— 面板上暴露的是 0~1 的范围/速度，
         * 这里负责把它们换算成屏幕像素。
         *
         * **标度必须是屏幕空间，不能按目标框高取比例。** 枪口爬升是镜头的旋转，
         * 画面上的位移像素数由 FOV 与分辨率决定，与目标远近无关；而框高与距离
         * 强相关。`7a1e202` 把 range/rate 改成 `strength * boxH` 后，远距离
         * (实测框高 15~20px) 的上限塌到 17px、速率塌到 2.4px/s，比上游慢 37 倍，
         * 等于让远处目标自动关掉压枪 —— 而远距离恰恰最需要它。实测日志见
         * recoil-improvement-plan.md §11。
         *
         * RATE_* 的锚点：上游 `recoilStrength * 3f` 每帧、以 30fps 换算
         * 就是 90px/s，那一版实机确认「有明显效果」。故速度 50% 取 90px/s。
         */
        /**
         * 压枪速率(屏幕 px/s)：速度 0% -> 30，50% -> 90，100% -> 150。
         * **长按和连点共用这一条** —— 面板上只有一个「压枪速度」。
         */
        private const val RATE_SLOW = 30f
        private const val RATE_FAST = 150f
        /**
         * 回落衰减速率常数(1/s) = 原来 `pow(0.7, dt*30)` 的等价指数形式
         * (30·ln0.7 = -10.70024)。换 exp 是纯性能：pow 一般按 exp(y·log x) 实现，
         * 实测(aarch64 OpenJDK) 97.3ns vs 19.3ns，float32 下偏差 3e-7。
         */
        private const val DECAY_EXP_PER_SEC = -10.70024f
        /**
         * 每一发开火发放的推进预算(ms)。连点每枪的推进量因此恒为
         * `速率 × FIRE_LATCH_MS`，与手指实际按了多久无关。
         *
         * 它替掉的是上游那条「斜坡跟着开火电平走」的判据：连点时电平只有几十毫秒
         * 为真，每枪实得 `速率 × 按压时长`，于是点得快每枪压得少、点得慢每枪压得
         * 多 —— 方向正好是反的(决定总爬升的是开枪次数，不是按压时长)，仿真实测
         * 这条噪声能让每枪的量漂移 38%，而且它不可调。
         *
         * 取值的物理含义是**枪的循环射速的倒数**，不是随手取的平滑窗口：枪口爬升
         * 正比于已开火的弹数，长按时循环射速 f 发/秒、每发爬升 = 速率/f，连点每枪
         * `速率 × W` 要与长按一致，条件就是 W = 1/f。100ms ↔ 600 RPM，正是步枪的
         * 典型档(600~750 RPM)；150ms 只有 400 RPM，对步枪偏慢，会让连点相对长按
         * 超压。
         *
         * 没有做成滑块：它描述的是枪，不是手感偏好。若日后要暴露，该按「循环射速
         * (RPM)」来标，而不是再加一个「力度」。
         */
        private const val FIRE_LATCH_MS = 100f
        /**
         * 未兑现的开火预算最多攒几发。正常连点每帧至多 1-2 发，攒不到这里；
         * 这道上限守的是注入层边沿计数异常暴增的情形 —— 没有它，预算会让斜坡
         * 在手指早已松开之后还一路推到上限。
         */
        private const val MAX_PENDING_ROUNDS = 5f
        /**
         * 下压范围满量程 = 屏幕高度 × 该比例。1080p 上 = 400px，与上游
         * MAX_OFFSET 一致；用比例而非绝对值，换分辨率不必重调。
         */
        private const val RANGE_MAX_RATIO = 0.37f
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
     * 下压范围(0.0 ~ 1.0)：偏移量的上限，即最多压到多深。**长按连点共用。**
     *
     * config.json 的 key 仍叫 recoilStrength(从上游延续至今)，不要改名，
     * 否则老配置读不到。语义上它已经只是「范围」，不再含速率成分 ——
     * 上游那个 strength 因为无上限而同时决定了快慢和深浅，这里拆开了。
     */
    var recoilStrength = 0.5f
    /** 压枪速度 0.0 ~ 1.0 → RATE_* 的 px/s：每秒压多少。**长按连点共用。** */
    var recoilSpeed = 0.5f
    var recoilResetIntervalMs = 300   // 松开开火区超过此时长才开始回落；0 = 立即重置
    /**
     * 压枪标度的参考高度 = 采集高度(px)。由 FloatService 在建立采集后写入。
     * 兜底 1080 只在还没建立采集时用得到，那时也不会有推理帧。
     */
    var recoilRefHeight = 1080f
    private var recoilOffsetY = 0f
    private var lastFireMs = 0L
    /** 尚未兑现的开火推进预算(ms)；每发 +FIRE_LATCH_MS，按真实 dt 扣减。 */
    private var fireBudgetMs = 0f

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
     * 参数只有两个，**长按和连点都适用，不分模式**：
     *
     *   下压范围 recoilStrength → 偏移量的上限(最多压到多深)
     *   压枪速度 recoilSpeed    → 斜坡速率 px/s(压下去多快)
     *
     * 上游只有一个 recoilStrength，每帧累加 `strength * 3f` 且**没有上限**，
     * 于是「能压多深」只由按了多久决定 —— 一个旋钮同时管住了快慢和深浅。这里把
     * 它拆成速率与上限两个独立量：调范围不改变下压快慢，调速度不改变终点。
     *
     * 为什么速度必须独立出来：枪械后坐力大时，开火头 1-2 秒枪口爬升快过压枪，
     * 准星会先跑到头部上方，等后坐力见顶才开始往下走，整个过程比应有的多花
     * 1-3 秒。这时候调范围没用 —— 范围只是把终点放得更低，不改变到达终点的
     * 快慢，甚至因为行程变长而更慢。要压住前期，得让它走得更快。
     *
     * **连点靠 FIRE_LATCH_MS 吃到补偿，不靠按压时长。** 每检测到一次开火上升沿
     * 就发一份 FIRE_LATCH_MS 的推进预算，连点每枪的推进量因此恒为
     * `速率 × FIRE_LATCH_MS`，与按住多久无关(见那里对 38% 漂移的说明)；长按时
     * 电平一直为真，预算不起额外作用，行为就是恒速斜坡到上限。两种开火方式走的
     * 是同一条斜坡、吃的是同两个滑块。
     *
     * 预算只影响「斜坡要不要推进」，不吞掉「松手」这件事：预算未清空期间
     * lastFireMs 继续刷新，所以回落判断只是整体推迟最多 FIRE_LATCH_MS，不会失效。
     *
     * 恒速而不是指数逼近：后者末段无限慢，且不符合真实枪械先爬升后见顶的形状。
     *
     * 时间基而不是帧基：原来是每帧固定量，30fps 按住一秒下压 45px、60fps 就是
     * 90px，`4ee9e9e` 提帧之后压枪同比变快。现在按 dt 累加，帧率无关。
     *
     * 两个量都是屏幕空间量。不要改回按目标框高取比例 —— 那与
     * aimOffsetYRatio / boxAimRatio 是不同性质的量：那两个决定「打身体哪个部位」
     * (该随框高缩放)，压枪对抗的是镜头爬升(与距离无关)。混用会让远距离失效。
     *
     * 自动扳机走 triggerFired 锁存(准心在目标上就一直 true)，等价于长按；它自己
     * 注入的点击在 updateZones() 里被 TOUCH_VIRTUAL_SLOT/TOUCH_TRIGGER_SLOT
     * 排除、不计入 taps，所以不会额外发预算。
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
            recoilOffsetY = 0f
            fireBudgetMs = 0f
            return
        }

        // ── 本帧该按"开火"推进多久 ──
        //
        // taps 来自注入层在 SYN_REPORT 上数的上升沿(120-240Hz)，短于一个推理帧
        // 间隔的点击也不会漏 —— 只按推理帧率查电平的话 30fps 下会整帧落空。
        //
        // 每检测到一发就发一份 FIRE_LATCH_MS 的推进预算，并且**按毫秒精确扣减，
        // 不按帧扣**。写成"记一个 latch 截止时刻、每帧看还没到期就推进整帧"是不
        // 行的：100ms 的窗口在 30fps(33ms/帧)下会随相位落进 3 或 4 帧，每枪实得
        // 100ms 或 133ms —— 33% 的逐枪抖动，与 FIRE_LATCH_MS 那里要修的 38% 漂移
        // 同一性质(都是让每枪的量取决于一个玩家控制不了的量)。按预算扣就与帧相位
        // 无关：给定墙钟时间内推进的总量与分几帧走无关，和衰减那条同一个道理。
        //
        // 手指按着时按真实 dt 推进(按得越久开火越多、爬升越多)，松开后由预算兜底
        // (保证每一发至少拿到一发的量)。两者合起来 = 推进 max(按压时长, W)。
        val dtMs = dtSec * 1000f
        if (taps > 0) {
            fireBudgetMs = (fireBudgetMs + taps * FIRE_LATCH_MS)
                .coerceAtMost(FIRE_LATCH_MS * MAX_PENDING_ROUNDS)
        }
        val firing = held || fireBudgetMs > 0f
        val advanceSec = (if (held) dtMs else minOf(dtMs, fireBudgetMs)) / 1000f
        fireBudgetMs = (fireBudgetMs - dtMs).coerceAtLeast(0f)

        // 面板滑块本就是 0~1，这两道夹取守的是手改 config.json 的情形：速度为负
        // 会让斜坡反向爬升；范围为负会让下面的 coerceIn(0f, range) 直接抛
        // IllegalArgumentException(min > max 在 Kotlin 里是空区间)。面板的
        // 0~100% 是这两个量唯一的真值来源。
        val speed = recoilSpeed.coerceIn(0f, 1f)
        val range = (recoilStrength.coerceIn(0f, 1f) * RANGE_MAX_RATIO * recoilRefHeight)
            .coerceIn(0f, MAX_OFFSET)

        if (firing) {
            // lastFireMs 无条件刷：预算只管斜坡要不要走，不改变「正在开火」
            // 这个事实，否则下面的回落会在连点的每次松手之间被触发。
            lastFireMs = nowMs
            val rate = RATE_SLOW + (RATE_FAST - RATE_SLOW) * speed
            recoilOffsetY += rate * advanceSec
        } else if (recoilResetIntervalMs <= 0) {
            // 间隔 = 0：预算耗尽之后立即重置，不走衰减。
            recoilOffsetY = 0f
        } else if (nowMs - lastFireMs > recoilResetIntervalMs) {
            // 衰减系数同样按时间归一，否则这里又会引入一个帧率相关量，把上面刚
            // 换成时间基的意义抵消掉。基准是 0.7/帧 @30fps，见 DECAY_EXP_PER_SEC。
            val k = Math.exp((dtSec * DECAY_EXP_PER_SEC).toDouble()).toFloat()
            recoilOffsetY *= k
            if (recoilOffsetY < 0.5f) recoilOffsetY = 0f
        }

        recoilOffsetY = recoilOffsetY.coerceIn(0f, range)
    }

    /** 排障用：外部只读当前偏移量。 */
    val recoilOffsetDebug: Float get() = recoilOffsetY

    fun resetRecoil() {
        recoilOffsetY = 0f
        // 一起清掉，否则残留的预算会让斜坡在重置后又白推进最多 FIRE_LATCH_MS。
        fireBudgetMs = 0f
    }

    fun reset() {
        aimingState.reset()
        bezierMover.cancel()
        resetRecoil()
    }
}
