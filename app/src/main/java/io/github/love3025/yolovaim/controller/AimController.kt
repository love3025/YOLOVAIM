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
    var recoilStrength = 0.5f   // 0.0 ~ 1.0
    var recoilTapStrength = 0.5f      // 连点压枪强度 0.0 ~ 1.0，0 = 关闭
    var recoilSpeed = 0.5f            // 压枪速度 0.0 ~ 1.0，决定下压速率
    var recoilResetIntervalMs = 300   // 松开开火区超过此时长才开始回落；0 = 立即重置
    /**
     * 压枪标度的参考高度 = 采集高度(px)。由 FloatService 在建立采集后写入。
     * 兜底 1080 只在还没建立采集时用得到，那时也不会有推理帧。
     */
    var recoilRefHeight = 1080f
    private var recoilOffsetY = 0f
    private var lastFireMs = 0L
    private var pendingKick = 0f

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
     * 强度与速度是两个维度，必须分开：
     *  - **压枪强度 = 下压范围**，屏幕高度的比例（100% ≈ 0.37 屏高）。
     *  - **压枪速度 = 下压速率**，直接是 px/s。
     *
     * 两者都是屏幕空间量。不要改回按目标框高取比例 —— 那与
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
        if (!recoilEnabled) { recoilOffsetY = 0f; pendingKick = 0f; return }

        // 下压范围。屏幕空间，不看目标框 —— 见 companion 里的标度说明。
        val range = (recoilStrength * RANGE_MAX_RATIO * recoilRefHeight)
            .coerceAtMost(MAX_OFFSET)

        // 连点：每检测到一次按下，加一份固定量。与按住多久无关。
        if (taps > 0 && recoilTapStrength > 0f) {
            pendingKick += recoilTapStrength * TAP_KICK_MAX_PX * taps
            lastFireMs = nowMs
        }
        // kick 摊到之后几帧释放而不是一帧打满：它进的是 PID 的目标值，而 Y 轴的
        // kp/kd 本就被 kpYRatio/kdYRatio 特意压低来抑制纵向震荡，一次性十几 px
        // 的阶跃正是那个环节最不擅长吃的输入。0.4 → 每帧释放剩余的 60% @30fps。
        if (pendingKick > 0f) {
            val take = pendingKick * (1f - Math.pow(0.4, (dtSec * 30f).toDouble()).toFloat())
            recoilOffsetY += take
            pendingKick -= take
            if (pendingKick < 0.5f) { recoilOffsetY += pendingKick; pendingKick = 0f }
        }

        // 长按：恒速下压，到 range 为止。
        //
        // 速率直接是 px/s，与强度(=范围)彻底无关 —— 两个滑块互不干扰。用绝对
        // 速率而不是 range/sec：后者强度调低时下降也跟着变慢，等于又把两者绑回去。
        //
        // 恒速而不是指数逼近：后者末段无限慢，且不符合真实枪械先爬升后见顶的形状。
        if (held) {
            val rate = HOLD_RATE_SLOW + (HOLD_RATE_FAST - HOLD_RATE_SLOW) * recoilSpeed
            recoilOffsetY += rate * dtSec
            lastFireMs = nowMs
        } else if (recoilResetIntervalMs <= 0) {
            // 间隔 = 0：松开开火区立即重置，不走衰减。
            recoilOffsetY = 0f
            pendingKick = 0f
        } else if (pendingKick <= 0f && nowMs - lastFireMs > recoilResetIntervalMs) {
            // 衰减系数同样按时间归一，否则这里又会引入一个帧率相关量，
            // 把上面刚换成时间基的意义抵消掉。0.7/帧 @30fps 为基准。
            recoilOffsetY *= Math.pow(0.7, (dtSec * 30f).toDouble()).toFloat()
            if (recoilOffsetY < 1f) recoilOffsetY = 0f
        }
        // 终点就是 range 本身，不需要另一个"上限"参数。
        recoilOffsetY = recoilOffsetY.coerceIn(0f, range)
    }

    /** 排障用：外部只读当前偏移量。 */
    val recoilOffsetDebug: Float get() = recoilOffsetY

    fun resetRecoil() {
        recoilOffsetY = 0f
    }

    fun reset() {
        aimingState.reset()
        bezierMover.cancel()
        resetRecoil()
    }
}
