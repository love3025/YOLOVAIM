package io.github.love3025.yolovaim.model

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 「平滑度越高是不是越抖？」的直接回归测试。
 *
 * [AimPidCoreTest] 只断言了**过冲**随平滑度单调不增，那不足以回答这个问题：
 * 过冲下降的同时，指令完全可能在奈奎斯特频率上振铃、或者把检测噪声放大 ——
 * 那才是用户眼里的「抖」。这里对**静止但带检测噪声**的目标测两个量：
 *
 * - 收敛后准星的残余移动 RMS —— 用户看到的抖
 * - 指令的相邻帧反号率 —— 回路自己的高频振铃
 *
 * ## 这个文件抓到过的真实缺陷
 *
 * 阻尼项最初写成教科书形式 `-damping · (vOwn - vTarget) · dt`。vTarget 是相邻帧
 * 框中心的差分估计，静止目标上光检测噪声就有 ±200px/s —— 于是「平滑度」这个
 * 旋钮同时在给噪声加增益，残余抖动 **随平滑度从 0.23px 涨到 0.95px**、反号率
 * 47%→72%，方向和旋钮的名字完全相反。改成 derivative-on-measurement
 * （阻尼只吃精确已知的 vOwn，目标速度交给 Kf 前馈）后压平到 0.23→0.25。
 *
 * ## 这个测试**不**声称的事
 *
 * 平滑度治的是**过冲与振荡**，不是检测噪声引起的微抖 —— 后者在下表里基本持平
 * （+9%），因为噪声驱动的指令是高频的，进 `ownVelFiltered` 那道低通之后几乎
 * 相互抵消，阻尼项根本看不见它。治噪声抖动的是收敛阈值(死区)和对目标位置的
 * 滤波，不是这个旋钮。本测试里没有死区，所以看到的是纯噪声地板。
 */
class AimDampingJitterTest {

    /** @return (残余抖动 RMS px, 指令反号率, 收敛耗时秒) */
    private fun run(damping: Float, kp: Float = 0.15f, noisePx: Float = 2f): Triple<Float, Float, Float> {
        val core = AimPidCore()
        val state = AimingState()
        val g = AimPidCore.Gains().also {
            it.kp = kp; it.ki = 0.001f; it.kf = 0.05f; it.damping = damping
            it.kpYRatio = 1f; it.kfYRatio = 1f
        }
        val rng = Random(20260908)
        val fps = 50f
        val dtNs = (1e9f / fps).toLong()
        val trueTarget = 300f
        var crosshair = 0f
        var t = 0L
        val delay = ArrayDeque<Float>()
        val delayFrames = 2

        var convergedAt = -1f
        var sumSq = 0f
        var samples = 0
        var flips = 0
        var flipChances = 0
        var prevStep = 0f
        var prevCross = 0f

        for (i in 1..1200) {
            t += dtNs
            val observed = trueTarget + (rng.nextFloat() * 2f - 1f) * noisePx
            val dt = core.beginIteration(t)
            state.updateVelocity(observed, 0f, dt)
            val step = core.stepX(observed - crosshair, state.smoothVelX, g)
            core.commit(step, 0f)
            delay.addLast(step)
            while (delay.size > delayFrames) crosshair += delay.removeFirst()

            if (convergedAt < 0f && abs(trueTarget - crosshair) <= 3f) convergedAt = i / fps
            // 收敛之后再留 100 帧瞬态，然后开始统计
            if (convergedAt > 0f && i > convergedAt * fps + 100) {
                val d = crosshair - prevCross
                sumSq += d * d; samples++
                if (prevStep != 0f && step != 0f) {
                    flipChances++
                    if (step * prevStep < 0f) flips++
                }
            }
            prevStep = step
            prevCross = crosshair
        }
        return Triple(
            if (samples > 0) sqrt(sumSq / samples) else 0f,
            if (flipChances > 0) flips.toFloat() / flipChances else 0f,
            convergedAt
        )
    }

    /** 滑条实际能取到的范围。上限的由来见 [AimPidCore.MAX_DAMPING]。 */
    private val sliderGrid = listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, AimPidCore.MAX_DAMPING)

    @Test
    fun `raising damping does not meaningfully raise residual jitter`() {
        val rows = sliderGrid.map { d -> val (rms, flip, _) = run(d); Triple(d, rms, flip) }
        rows.forEach { (d, rms, flip) ->
            println("damping=%.1f  残余抖动RMS=%.3fpx  指令反号率=%.1f%%".format(d, rms, flip * 100))
        }
        val first = rows.first().second
        val last = rows.last().second
        // 「越高越抖」若成立，这里会像修正前那样涨 4 倍。允许 25% 的余量：
        // 平滑度对噪声地板是中性的，不是负相关。
        assertTrue(
            "整条滑条上残余抖动不应显著上升(0.0→$first, ${AimPidCore.MAX_DAMPING}→$last)",
            last <= first * 1.25f + 0.01f
        )
        for (i in 1 until rows.size) {
            val (dPrev, rmsPrev, _) = rows[i - 1]
            val (dCur, rmsCur, _) = rows[i]
            assertTrue(
                "平滑度 $dCur 的残余抖动 $rmsCur 相对 $dPrev 的 $rmsPrev 不应跳变",
                rmsCur <= rmsPrev * 1.1f + 0.01f
            )
        }
    }

    @Test
    fun `damping trades speed for overshoot, not for jitter`() {
        val (jitFast, _, tFast) = run(0f)
        val (jitSmooth, _, tSmooth) = run(AimPidCore.MAX_DAMPING)
        println("damping=0.0  收敛 %.2fs  抖动 %.3fpx".format(tFast, jitFast))
        println("damping=%.1f  收敛 %.2fs  抖动 %.3fpx".format(AimPidCore.MAX_DAMPING, tSmooth, jitSmooth))
        assertTrue("高平滑度更慢 —— 这是它唯一的代价", tSmooth >= tFast)
        assertTrue("高平滑度不该把噪声抖动放大", jitSmooth <= jitFast * 1.25f + 0.01f)
    }

    @Test
    fun `overshoot reaches zero at the slider ceiling`() {
        // MAX_DAMPING 取 1.0 的依据：过冲在这里归零，再往上只涨指令反号率。
        fun overshoot(d: Float): Float {
            val core = AimPidCore()
            val g = AimPidCore.Gains().also {
                it.kp = 0.35f; it.ki = 0f; it.kf = 0f; it.damping = d
                it.kpYRatio = 1f; it.kfYRatio = 1f
            }
            var cross = 0f; var t = 0L; var worst = 0f
            val pipe = ArrayDeque<Float>()
            repeat(600) {
                t += 20_000_000L
                core.beginIteration(t)
                val step = core.stepX(200f - cross, 0f, g)
                core.commit(step, 0f)
                pipe.addLast(step)
                while (pipe.size > 3) cross += pipe.removeFirst()
                if (cross > 200f) worst = maxOf(worst, cross - 200f)
            }
            return worst
        }
        val none = overshoot(0f)
        val ceiling = overshoot(AimPidCore.MAX_DAMPING)
        println("过冲: damping=0 → %.2fpx, damping=%.1f → %.2fpx".format(none, AimPidCore.MAX_DAMPING, ceiling))
        assertTrue("零阻尼工况本应大幅过冲(实测 $none)", none > 100f)
        assertTrue("滑条顶端应把过冲收到 1px 内(实测 $ceiling)", ceiling < 1f)
    }

    @Test
    fun `values beyond the ceiling are clamped, not obeyed`() {
        fun firstStep(d: Float): Float {
            val core = AimPidCore()
            val g = AimPidCore.Gains().also {
                it.kp = 0.2f; it.ki = 0f; it.kf = 0f; it.damping = d; it.kpYRatio = 1f
            }
            var t = 1_000_000_000L
            var s = 0f
            repeat(6) {
                t += 20_000_000L
                core.beginIteration(t)
                s = core.stepX(200f, 0f, g)
                core.commit(s, 0f)
            }
            return s
        }
        // 导入一份 damping=5 的旧/手改配置，不应把回路推进振荡区
        assertTrue("超上限的配置值必须被钳住", abs(firstStep(5f) - firstStep(AimPidCore.MAX_DAMPING)) < 1e-4f)
    }
}
