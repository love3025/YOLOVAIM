package io.github.love3025.yolovaim.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bounded P-term shaping throughout the approach, not only the last 10 pixels.
 *
 * Linear P loses authority long before the stop tolerance is reached. Restore
 * it smoothly inside the approach band, independently on each axis. The stop
 * tolerance still decides accuracy; neither it nor the trigger test is widened.
 * D/F, integral and command damping remain the caller's responsibility, and a
 * braking/reversing PID output is never overridden. There is no minimum step.
 *
 * No touch-to-screen gain is inferred: a frame's displacement also contains
 * target motion, detection noise and delayed effects of earlier commands.
 * Capture timestamps provide a warm-up/arrival observation window and reduce
 * the gain ceiling on older frames. That window is not a bound on device delay.
 *
 * Pure Kotlin, allocation-free per frame, confined to the inference thread.
 */
class AimFinishController {
    companion object {
        // Upper end of the existing Kp slider, not an extra user setting.
        // A stronger user-supplied PID is left alone, never multiplied again.
        const val MAX_ASSIST_KP = 0.2f
        private const val APPROACH_RADIUS_PX = 128f
        private const val STOP_TO_APPROACH_RATIO = 4f
        private const val MAX_P_MULTIPLIER = 4f
        private const val MAX_FRAME_AGE_NS = 150_000_000L
        private const val MAX_SAMPLE_GAP_NS = 150_000_000L
        private const val NS_PER_SECOND = 1_000_000_000f
    }

    private val x = Axis()
    private val y = Axis()
    private var previousCaptureNs = 0L

    /** Observe every selected-target frame, including frames already at rest. */
    fun observe(
        errorX: Float,
        errorY: Float,
        toleranceX: Float,
        toleranceY: Float,
        stopThreshold: Float,
        captureNs: Long,
        nowNs: Long
    ) {
        val ageNs = nowNs - captureNs
        if (captureNs <= 0L || ageNs !in 0L..MAX_FRAME_AGE_NS ||
            !errorX.isFinite() || !errorY.isFinite() ||
            !stopThreshold.isFinite() || stopThreshold <= 0f ||
            !toleranceX.isFinite() || !toleranceY.isFinite() ||
            toleranceX < 0f || toleranceY < 0f
        ) {
            reset()
            return
        }
        val radius = max(APPROACH_RADIUS_PX, STOP_TO_APPROACH_RATIO * stopThreshold)
        if (!radius.isFinite()) {
            reset()
            return
        }

        val gapNs = captureNs - previousCaptureNs
        val contiguous = previousCaptureNs > 0L && gapNs in 1L..MAX_SAMPLE_GAP_NS
        if (!contiguous) {
            x.reset()
            y.reset()
        }
        previousCaptureNs = captureNs

        // The old BOTH-axes / old-stop gate missed the entire visible slow
        // approach. A slow X must not wait for Y to get within ten pixels.
        val dt = if (contiguous) gapNs / NS_PER_SECOND else 0f
        val horizonNs = if (contiguous) max(2L * gapNs, ageNs + gapNs) else 0L
        val gainScale = if (contiguous) 2f * gapNs / horizonNs else 0f
        x.observe(errorX, toleranceX, radius, contiguous, dt, captureNs, horizonNs, gainScale)
        y.observe(errorY, toleranceY, radius, contiguous, dt, captureNs, horizonNs, gainScale)
    }

    fun shapeX(pidOutput: Float, kp: Float): Float = x.shape(pidOutput, kp)
    // gainCeiling 必须显式传入：生产侧 Y 轴上限是 MAX_ASSIST_KP × kpYRatio，
    // kpYRatio 用户可调。这里不留默认值，免得多处各自维护一个 0.6。
    fun shapeY(pidOutput: Float, kp: Float, gainCeiling: Float): Float =
        y.shape(pidOutput, kp, gainCeiling)

    fun reset() {
        previousCaptureNs = 0L
        x.reset()
        y.reset()
    }

    private class Axis {
        private var error = 0f
        private var tolerance = 0f
        private var approachRadius = 0f
        private var previousError = 0f
        private var lastNonzeroSign = 0
        private var wasInApproach = false
        private var suppressed = false
        private var outsideSinceNs = 0L
        private var weight = 0f
        private var gainScale = 0f

        fun observe(
            value: Float,
            tol: Float,
            radius: Float,
            contiguous: Boolean,
            dt: Float,
            captureNs: Long,
            horizonNs: Long,
            delayGainScale: Float
        ) {
            error = value
            tolerance = tol
            approachRadius = radius
            weight = 0f
            gainScale = delayGainScale
            val inApproach = abs(value) < radius
            val sign = when {
                value > 0f -> 1
                value < 0f -> -1
                else -> 0
            }

            if (!inApproach) {
                // A new coarse approach starts a new finishing attempt.
                suppressed = false
                outsideSinceNs = 0L
            } else {
                // A crossing (including + -> 0 -> -) means the correction has
                // overshot or the target changed direction. Do not repeatedly
                // re-enable the extra gain around zero: use the original PID
                // for the rest of this finishing attempt.
                if (contiguous && lastNonzeroSign != 0 && sign != 0 &&
                    sign != lastNonzeroSign && wasInApproach
                ) suppressed = true

                if (abs(value) >= tol && tol > 0f && tol < radius) {
                    if (outsideSinceNs == 0L) outsideSinceNs = captureNs
                } else outsideSinceNs = 0L

                // Do not amplify several commands before even one observation
                // window has elapsed. Old/slow captures also lower the ceiling.
                if (contiguous && !suppressed && outsideSinceNs > 0L &&
                    captureNs - outsideSinceNs >= horizonNs
                ) {
                    val remaining = abs(value) - tol
                    if (remaining > 0f) {
                        val closing = max(0f, (previousError - value) * sign / dt)
                        val horizon = horizonNs / NS_PER_SECOND
                        val brake = (1f - closing * horizon / remaining).coerceIn(0f, 1f)
                        // Smooth entry, full shaping only in the inner half.
                        val t = (2f * (radius - abs(value)) / (radius - tol)).coerceIn(0f, 1f)
                        weight = t * t * (3f - 2f * t) * brake
                    }
                }
            }
            previousError = value
            if (sign != 0) lastNonzeroSign = sign
            wasInApproach = inApproach
        }

        fun shape(pidOutput: Float, kp: Float, gainCeiling: Float = MAX_ASSIST_KP): Float {
            if (weight <= 0f || !pidOutput.isFinite() || !kp.isFinite() || kp <= 0f ||
                !gainCeiling.isFinite() || gainCeiling <= 0f ||
                pidOutput * error <= 0f || abs(error) <= tolerance
            ) return pidOutput

            val maxKp = gainCeiling * gainScale
            if (kp >= maxKp) return pidOutput
            val originalP = kp * abs(error)
            val edgeP = kp * approachRadius
            val shapedP = min(min(MAX_P_MULTIPLIER * originalP, edgeP), maxKp * abs(error))
            // Cap the TOTAL assisted output as well as its P term. Preserve
            // an already faster raw PID, rather than stacking more gain on it.
            val headroom = max(0f, shapedP - abs(pidOutput))
            val extra = min(max(0f, shapedP - originalP) * weight, headroom)
            return if (error > 0f) pidOutput + extra else pidOutput - extra
        }

        fun reset() {
            error = 0f
            tolerance = 0f
            approachRadius = 0f
            previousError = 0f
            lastNonzeroSign = 0
            wasInApproach = false
            suppressed = false
            outsideSinceNs = 0L
            weight = 0f
            gainScale = 0f
        }
    }
}
