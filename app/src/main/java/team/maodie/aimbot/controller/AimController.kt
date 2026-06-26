package team.maodie.aimbot.controller
import team.maodie.aimbot.model.DetectionInfo

import android.graphics.RectF
import android.util.Log
import team.maodie.aimbot.service.FloatService
import team.maodie.aimbot.injector.TouchInjectorInterface
import team.maodie.aimbot.model.AreaConfig
import team.maodie.aimbot.model.AimingState
import team.maodie.aimbot.model.BezierMover

class AimController(
    private val service: FloatService,
    private val touchClient: () -> TouchInjectorInterface?,
    private val savedAreas: () -> List<AreaConfig>
) {
    companion object {
        private const val TAG = "AimController"
        private const val AREA_INDEX_AIM = 2
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
    var aimOffsetYRatio = 0f
    var aimSwayAmplitude = 0
    var aimPrediction = 0
    var aimHoldEnabled = false

    // Recoil compensation
    var recoilEnabled = false
    var recoilStrength = 0.5f   // 0.0 ~ 1.0
    var triggerHeld = false     // true while trigger is actively firing
    private var recoilOffsetY = 0f

    // Class filtering
    var aimClasses: MutableSet<Int> = mutableSetOf()
    var priorityClass: Int = -1
    var classAimOffsets: Map<Int, Float> = emptyMap()
    var boxAimRatio = 0.5f
    var classBoxAimRatios: Map<Int, Float> = emptyMap()

    // State
    val aimingState = AimingState()
    private val bezierMover = BezierMover()

    fun selectTarget(dets: List<DetectionInfo>, cx: Float, cy: Float): DetectionInfo? {
        val lock = aimingState.lockedTarget
        if (lock != null) {
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

        // Priority: if priorityClass is set and present, only consider that class
        val candidates = if (priorityClass >= 0) {
            val prioritized = dets.filter { it.classId == priorityClass }
            if (prioritized.isNotEmpty()) prioritized else dets
        } else dets

        // Pick closest to crosshair
        var bestDistSq = Float.MAX_VALUE
        var bestDet: DetectionInfo? = null
        for (det in candidates) {
            val r = det.rect
            val bcx = (r.left + r.right) * 0.5f
            val bcy = (r.top + r.bottom) * 0.5f
            val d = (bcx - cx) * (bcx - cx) + (bcy - cy) * (bcy - cy)
            if (d < bestDistSq) {
                bestDistSq = d
                bestDet = det
            }
        }
        if (bestDet != null) {
            val bcx = bestDet.rect.centerX()
            val bcy = bestDet.rect.centerY()
            aimingState.lockedTarget = RectF(bcx, bcy, bcx, bcy)
        }
        return bestDet
    }

    fun executeAiming(targetX: Float, targetY: Float, cx: Float, cy: Float) {
        // 压枪：按住扳机时持续下压目标位置
        var adjustedTargetY = targetY
        if (recoilEnabled) {
            if (triggerHeld) {
                recoilOffsetY += recoilStrength * 3f
                adjustedTargetY += recoilOffsetY
            } else {
                recoilOffsetY = 0f
            }
        }
        if (aimMode == 1) {
            executeAimingBezier(targetX, adjustedTargetY, cx, cy)
        } else {
            executeAimingPid(targetX, adjustedTargetY, cx, cy)
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
                touchClient()?.lift()
                aimingState.pointerDown = false
                aimingState.lockedTarget = null
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
                touchClient()?.lift()
                aimingState.pointerDown = false
                aimingState.lockedTarget = null
                Log.d(TAG, "aim converged error=($errorX, $errorY)")
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

    fun lift() {
        touchClient()?.lift()
        aimingState.pointerDown = false
        aimingState.lockedTarget = null
    }

    fun resetRecoil() {
        recoilOffsetY = 0f
    }

    fun reset() {
        aimingState.reset()
        bezierMover.cancel()
        resetRecoil()
    }
}
