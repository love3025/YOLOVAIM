package io.github.love3025.yolovaim.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/*
 * 注意：本文件里的 `simulate()` 复刻的是 **2026-09-07 之前的旧控制律**
 * (`kp*e + ki*I + kd*derivative + kf*v - 0.35*上一帧位移`)。生产控制律已换成
 * [AimPidCore]（帧率无关 + 单一阻尼项），而 [AimFinishController] 本身也已
 * 默认关闭（见 AimController.approachAssistEnabled）。
 *
 * 这些测试仍然有效，但它们验证的是**整形器自己的契约**（范围外逐位透传、
 * 反向刹车不覆盖、时间戳异常撤销增强等），**不是**生产行为的保证。要看
 * 生产控制律的可调性断言，去 AimPidCoreTest。
 */
class AimFinishControllerTest {
    // 生产侧 Y 轴上限 = MAX_ASSIST_KP × kpYRatio，kpYRatio 默认 0.6。
    private val yCeiling = AimFinishController.MAX_ASSIST_KP * 0.6f

    private class Samples(val controller: AimFinishController = AimFinishController()) {
        var capture = 1_000_000_000L

        fun frame(
            x: Float = 4f,
            y: Float = 0f,
            tx: Float = 2f,
            ty: Float = 2f,
            old: Float = 10f,
            step: Long = 25_000_000L,
            age: Long = 10_000_000L
        ) {
            capture += step
            controller.observe(x, y, tx, ty, old, capture, capture + age)
        }

        fun stable(x: Float = 4f, y: Float = 0f, tx: Float = 2f, ty: Float = 2f, old: Float = 10f) {
            repeat(6) { frame(x, y, tx, ty, old) }
        }
    }

    @Test fun outsideTheApproachBandBothOutputsAreBitForBitUnchanged() {
        for ((x, y) in listOf(200f to 160f, 128f to 128f, -128f to -200f)) {
            val s = Samples()
            s.stable(x, y)
            assertEquals(0.271f.toBits(), s.controller.shapeX(0.271f, 0.07f).toBits())
            assertEquals((-0.131f).toBits(), s.controller.shapeY(-0.131f, 0.042f, yCeiling).toBits())
        }
    }

    @Test fun largeBoxesWithUnchangedToleranceKeepOriginalOutput() {
        val s = Samples()
        s.stable(8f, 8f, tx = 10f, ty = 10f)
        assertEquals(0.2f, s.controller.shapeX(0.2f, 0.07f), 0f)
        assertEquals(0.2f, s.controller.shapeY(0.2f, 0.042f, yCeiling), 0f)
    }

    @Test fun fineCorrectionIsStrongerButBoundedByTheAxisGainCeiling() {
        val s = Samples()
        s.stable(4f, 4f)
        val x = s.controller.shapeX(0.2f, 0.07f)
        val y = s.controller.shapeY(0.1f, 0.042f, yCeiling)
        assertTrue(x > 0.2f)
        assertTrue(y > 0.1f)
        assertTrue(x <= AimFinishController.MAX_ASSIST_KP * 4f)
        assertTrue(y <= AimFinishController.MAX_ASSIST_KP * 0.6f * 4f)
    }

    @Test fun correctionIsSymmetricForBothDirections() {
        val positive = Samples().also { it.stable(4f, 4f) }
        val negative = Samples().also { it.stable(-4f, -4f) }
        assertEquals(positive.controller.shapeX(0.2f, 0.07f), -negative.controller.shapeX(-0.2f, 0.07f), 0f)
        assertEquals(positive.controller.shapeY(0.1f, 0.042f, yCeiling), -negative.controller.shapeY(-0.1f, 0.042f, yCeiling), 0f)
    }

    @Test fun itDoesNotCreateAMinimumStepOrOverrideBraking() {
        val s = Samples().also { it.stable() }
        assertEquals(0f, s.controller.shapeX(0f, 0.07f), 0f)
        assertEquals(-0.2f, s.controller.shapeX(-0.2f, 0.07f), 0f)
        assertEquals(0.2f, s.controller.shapeX(0.2f, 0f), 0f)
        assertEquals(0.9f, s.controller.shapeX(0.9f, 0.07f), 0f)
    }

    @Test fun axesAlreadyWithinToleranceReceiveNoAdditionalMovement() {
        val s = Samples()
        s.stable(1f, 4f)
        assertEquals(0.02f, s.controller.shapeX(0.02f, 0.07f), 0f)
        assertTrue(s.controller.shapeY(0.1f, 0.042f, yCeiling) > 0.1f)
    }

    @Test fun aSingleJitterFrameAfterRestDoesNotWakeTheBoost() {
        val s = Samples().also { it.stable() }
        s.frame(1f)
        s.frame(3f)
        assertEquals(0.2f, s.controller.shapeX(0.2f, 0.07f), 0f)
        s.frame(1f)
        s.frame(3f)
        assertEquals(0.2f, s.controller.shapeX(0.2f, 0.07f), 0f)
    }

    @Test fun predictedArrivalWithdrawsTheExtraGain() {
        val s = Samples().also { it.stable(5f) }
        s.frame(3f) // 2 px/frame closing, only 1 px until tolerance
        assertEquals(0.2f, s.controller.shapeX(0.2f, 0.07f), 0f)
    }

    @Test fun aCrossingSuppressesTheRestOfTheFinishingAttempt() {
        val s = Samples().also { it.stable() }
        s.frame(0f)
        repeat(12) { s.frame(-4f) }
        assertEquals(-0.2f, s.controller.shapeX(-0.2f, 0.07f), 0f)
        s.frame(-160f) // new coarse approach explicitly rearms assistance
        s.stable(-4f)
        assertTrue(s.controller.shapeX(-0.2f, 0.07f) < -0.2f)
    }

    @Test fun resetDropsThePreviousTargetsHistory() {
        val s = Samples().also { it.stable() }
        s.controller.reset()
        s.frame()
        assertEquals(0.2f, s.controller.shapeX(0.2f, 0.07f), 0f)
        s.frame()
        assertEquals(0.2f, s.controller.shapeX(0.2f, 0.07f), 0f)
        s.frame()
        assertTrue(s.controller.shapeX(0.2f, 0.07f) > 0.2f)
    }

    @Test fun invalidAndDiscontinuousTimestampsUseOriginalPid() {
        for ((step, age) in listOf(
            0L to 10_000_000L,
            -1L to 10_000_000L,
            200_000_000L to 10_000_000L,
            25_000_000L to 200_000_000L,
            25_000_000L to -1L
        )) {
            val s = Samples().also { it.stable() }
            s.frame(step = step, age = age)
            assertEquals(0.2f, s.controller.shapeX(0.2f, 0.07f), 0f)
        }
        val s = Samples().also { it.stable() }
        s.controller.observe(4f, 0f, 2f, 2f, 10f, 0L, s.capture)
        assertEquals(0.2f, s.controller.shapeX(0.2f, 0.07f), 0f)
    }

    @Test fun disabledDeadzoneAndInvalidGeometryDoNotAddGain() {
        for ((tol, old) in listOf(0f to 0f, 0f to 10f, 2f to Float.NaN,
            2f to Float.MAX_VALUE, -1f to 10f)) {
            val s = Samples().also { it.stable(tx = tol, ty = tol, old = old) }
            assertEquals(0.2f, s.controller.shapeX(0.2f, 0.07f), 0f)
        }
        val s = Samples().also { it.stable(x = Float.NaN) }
        assertEquals(0.2f, s.controller.shapeX(0.2f, 0.07f), 0f)
    }

    @Test fun entryIsContinuousAtTheApproachBoundary() {
        val s = Samples().also { it.stable(127.999f) }
        assertEquals(0.2f, s.controller.shapeX(0.2f, 0.07f), 0.00001f)
    }

    @Test fun olderFramesWaitForAnObservationWindowAndHaveALowerCeiling() {
        val slow = Samples()
        repeat(4) { slow.frame(8f, age = 75_000_000L) }
        assertEquals(0.3f, slow.controller.shapeX(0.3f, 0.07f), 0f)
        slow.frame(8f, age = 75_000_000L)
        val slowOutput = slow.controller.shapeX(0.3f, 0.07f)
        val fresh = Samples().also { it.stable(8f) }.controller.shapeX(0.3f, 0.07f)
        assertTrue(slowOutput > 0.3f)
        assertTrue(slowOutput <= 0.1f * 8f)
        assertTrue(slowOutput < fresh)
    }

    @Test fun alreadyStrongPidAndNonFiniteParametersAreNotAmplified() {
        val controller = Samples().also { it.stable(8f, 8f) }.controller
        assertEquals(1f, controller.shapeX(1f, 0.2f), 0f)
        assertEquals(1f, controller.shapeY(1f, 0.12f, yCeiling), 0f)
        for (kp in listOf(-1f, 0f, Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE)) {
            assertEquals(1f, controller.shapeX(1f, kp), 0f)
        }
        for (ceiling in listOf(-1f, 0f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(1f, controller.shapeY(1f, 0.042f, ceiling), 0f)
        }
    }

    /**
     * A deliberately simple, deterministic 1-D plant: screen displacement is
     * gain * INTEGER touch displacement, visible after delayFrames + 1 frames.
     * Reference arithmetic follows AimController's existing PID. These tests
     * check regressions and the tail's trend, not real-device hit rate/latency.
     */
    private fun simulate(
        enabled: Boolean,
        gain: Float = 1f,
        delayFrames: Int = 1,
        kp: Float = 0.07f,
        kd: Float = 0.05f,
        kf: Float = 0.125f,
        tolerance: Float = 2f,
        fps: Int = 40,
        initialError: Float = 40f,
        vertical: Boolean = false
    ): Simulation {
        val finish = AimFinishController()
        var error = initialError
        var touch = 1000f
        var previousIntegerTouch = 1000
        var integral = 0f
        var derivative = 0f
        var previousError = 0f
        var previousMove = 0f
        var velocity = 0f
        var previousTarget = Float.NaN
        val changes = FloatArray(420)
        val errors = ArrayList<Float>()
        val outputs = ArrayList<Float>()
        val stepNs = 1_000_000_000L / fps
        repeat(400) { frame ->
            error -= gain * changes[frame]
            if (!previousTarget.isNaN()) {
                val v = error - previousTarget
                velocity = 0.7f * velocity + 0.3f * if (abs(v) < 2f) 0f else v
            }
            previousTarget = error
            val captureNs = 1_000_000_000L + frame * stepNs
            finish.observe(
                if (vertical) 0f else error, if (vertical) error else 0f,
                tolerance, tolerance, 10f, captureNs, captureNs + delayFrames * stepNs
            )
            errors.add(error)
            if (frame == 0) { // existing first frame sends only DOWN
                previousTarget = Float.NaN
                velocity = 0f
                finish.reset()
                outputs.add(0f)
                return@repeat
            }
            if (abs(error) < tolerance) {
                outputs.add(0f)
                return@repeat
            }
            if (abs(error) < 200f) {
                if (error * previousError <= 0f) integral = 0f
                integral = (integral + error).coerceIn(-100f, 100f)
            } else integral *= 0.5f
            derivative = 0.4f * (error - previousError) + 0.6f * derivative
            var output = kp * error + 0.001f * integral + kd * derivative + kf * velocity - 0.35f * previousMove
            if (enabled) output = if (vertical) finish.shapeY(output, kp, yCeiling) else finish.shapeX(output, kp)
            output = output.coerceIn(-600f, 600f)
            previousError = error
            previousMove = output
            touch += output
            val integerTouch = touch.toInt()
            changes[frame + delayFrames + 1] += (integerTouch - previousIntegerTouch).toFloat()
            previousIntegerTouch = integerTouch
            outputs.add(output)
        }
        val entry = errors.indexOfFirst { abs(it) < 10f }
        val settled = errors.indexOfLast { abs(it) >= tolerance } + 1
        return Simulation(errors, outputs, entry, settled)
    }

    private data class Simulation(
        val errors: List<Float>, val outputs: List<Float>, val entry: Int, val settled: Int
    ) {
        val tailFrames: Int get() = settled - entry
    }

    @Test fun defaultPidApproachAndTailBothGetShorter() {
        for (fps in listOf(30, 40, 60)) {
            for (vertical in listOf(false, true)) {
                val kp = if (vertical) 0.042f else 0.07f
                val kd = if (vertical) 0.0425f else 0.05f
                val kf = if (vertical) 0.0875f else 0.125f
                val before = simulate(false, kp = kp, kd = kd, kf = kf, fps = fps, vertical = vertical)
                val after = simulate(true, kp = kp, kd = kd, kf = kf, fps = fps, vertical = vertical)
                assertTrue(after.entry < before.entry)
                assertTrue(after.settled <= before.settled * 0.8f)
                assertTrue("fps=$fps vertical=$vertical before=${before.tailFrames} after=${after.tailFrames}",
                    after.tailFrames < before.tailFrames)
                assertTrue(after.errors.takeLast(60).all { abs(it) < 2f })
                println("aim-response vs raw PID fps=$fps vertical=$vertical " +
                    "total ${before.settled} -> ${after.settled}, tail ${before.tailFrames} -> ${after.tailFrames} frames")
            }
        }
    }

    @Test fun representativeGainDelayGridDoesNotIntroducePersistentOscillation() {
        for (initial in listOf(4f, 12f, 40f, 100f, 200f)) {
            for (baseKp in listOf(0.07f, 0.2f)) {
                for (vertical in listOf(false, true)) {
                    val kp = baseKp * if (vertical) 0.6f else 1f
                    val kd = if (vertical) 0.0425f else 0.05f
                    val kf = if (vertical) 0.0875f else 0.125f
                    for (gain in listOf(0.5f, 1f, 2f, 4f)) {
                        for (delay in 0..3) {
                            for (tolerance in listOf(1f, 2f, 5f, 10f)) {
                                val before = simulate(false, gain, delay, kp, kd, kf, tolerance,
                                    initialError = initial, vertical = vertical)
                                if (before.errors.takeLast(60).all { abs(it) < tolerance }) {
                                    val after = simulate(true, gain, delay, kp, kd, kf, tolerance,
                                        initialError = initial, vertical = vertical)
                                    assertTrue("initial=$initial kp=$kp vertical=$vertical gain=$gain " +
                                        "delay=$delay tol=$tolerance tail=${after.errors.takeLast(10)}",
                                        after.errors.takeLast(60).all { abs(it) < tolerance })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun farTargetsKeepTheSameCoarseMotionAndThenSettleSooner() {
        for (initial in listOf(-200f, 200f)) {
            val before = simulate(false, initialError = initial)
            val after = simulate(true, initialError = initial)
            val entry = before.errors.indexOfFirst { abs(it) < 128f }
            assertTrue(entry > 0)
            for (i in 0 until entry) {
                assertEquals(before.errors[i].toBits(), after.errors[i].toBits())
                assertEquals(before.outputs[i].toBits(), after.outputs[i].toBits())
            }
            assertTrue(after.settled <= before.settled * 0.8f)
            assertTrue(after.errors.takeLast(60).all { abs(it) < 2f })
        }
    }
}
