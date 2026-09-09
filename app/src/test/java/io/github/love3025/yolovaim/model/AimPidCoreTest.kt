package io.github.love3025.yolovaim.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs
import org.junit.Test

/**
 * 这些测试断言的是**可调性**，不是手感：
 *
 * - 同一组参数在不同帧率下走出同一条轨迹（治「速度不一致」）
 * - Kp 单调：越大越快，全行程有效（治「降低 Kp 没用」）
 * - 平滑度单调：越大过冲越小（治「加高 Kd 没用」）
 *
 * 闭环模型：手指位移按单位增益变成准星位移，附带 `delayFrames` 帧的链路延迟。
 * 这是简化模型，不是实机命中率或耗时承诺 —— 它证明的是参数关系的性质。
 */
class AimPidCoreTest {

    private class Sim(
        val kp: Float = 0.07f,
        val damping: Float = 0.4f,
        val ki: Float = 0f,
        val kf: Float = 0f,
        val delayFrames: Int = 1,
        val fps: Float = 50f
    ) {
        val core = AimPidCore()
        val gains = AimPidCore.Gains().also {
            it.kp = kp; it.ki = ki; it.kf = kf; it.damping = damping
            it.kpYRatio = 1f; it.kfYRatio = 1f
        }
        var crosshair = 0f
        var captureNs = 0L
        private val pipeline = ArrayDeque<Float>()

        /** @return 本步之后的误差 */
        fun step(target: Float): Float {
            captureNs += (1e9f / fps).toLong()
            core.beginIteration(captureNs)
            val err = target - crosshair
            val move = core.stepX(err, 0f, gains)
            val cap = core.stepCap(gains)
            val clamped = move.coerceIn(-cap, cap)
            core.commit(clamped, 0f)
            pipeline.addLast(clamped)
            while (pipeline.size > delayFrames) crosshair += pipeline.removeFirst()
            return target - crosshair
        }

        /** 跑到 |误差| <= tol，返回耗时(秒)；不收敛返回 -1。 */
        fun timeToConverge(target: Float, tol: Float, maxSteps: Int = 4000): Float {
            for (i in 1..maxSteps) {
                if (abs(step(target)) <= tol) return i / fps
            }
            return -1f
        }

        /** 最大过冲(越过目标的最大距离)。 */
        fun overshoot(target: Float, steps: Int = 600): Float {
            var worst = 0f
            for (i in 1..steps) {
                step(target)
                if (crosshair > target) worst = maxOf(worst, crosshair - target)
            }
            return worst
        }
    }

    // ---------- 帧率无关 ----------

    @Test
    fun `same params converge in same wall-clock time across frame rates`() {
        val t30 = Sim(fps = 30f).timeToConverge(200f, 2f)
        val t50 = Sim(fps = 50f).timeToConverge(200f, 2f)
        val t120 = Sim(fps = 120f).timeToConverge(200f, 2f)
        assertTrue("30fps 未收敛", t30 > 0f)
        assertTrue("50fps 未收敛", t50 > 0f)
        assertTrue("120fps 未收敛", t120 > 0f)
        // 旧实现 kp*error 是每帧比例，30fps 的耗时会是 50fps 的 1.67 倍。
        // 指数逼近之后三者应落在同一个墙钟时间附近。
        assertEquals("30 vs 50fps 墙钟耗时", t50, t30, t50 * 0.25f)
        assertEquals("120 vs 50fps 墙钟耗时", t50, t120, t50 * 0.25f)
    }

    @Test
    fun `old per-frame law would have failed the frame-rate test`() {
        // 反证：直接用旧式 kp*error 迭代，30fps 与 50fps 的墙钟耗时必然差 ~1.67 倍。
        fun oldLaw(fps: Float): Float {
            var c = 0f
            for (i in 1..4000) {
                c += (200f - c) * 0.07f
                if (abs(200f - c) <= 2f) return i / fps
            }
            return -1f
        }
        val r = oldLaw(30f) / oldLaw(50f)
        assertTrue("旧实现本应表现出帧率相关(实测比值 $r)", r > 1.5f)
    }

    @Test
    fun `jittery frame times do not change the trajectory much`() {
        // 固定 50fps 对照一段 25~60fps 抖动的采集。
        val steady = Sim(fps = 50f)
        val stTime = steady.timeToConverge(200f, 2f)

        val jit = Sim(fps = 50f)
        val rng = java.util.Random(7)
        var t = 0L
        var elapsed = 0f
        var converged = -1f
        for (i in 1..4000) {
            val dtSec = 1f / (25f + rng.nextFloat() * 35f)
            t += (dtSec * 1e9f).toLong()
            jit.core.beginIteration(t)
            val err = 200f - jit.crosshair
            val move = jit.core.stepX(err, 0f, jit.gains)
            jit.core.commit(move, 0f)
            jit.crosshair += move
            elapsed += dtSec
            if (abs(200f - jit.crosshair) <= 2f) { converged = elapsed; break }
        }
        assertTrue("抖动帧率下未收敛", converged > 0f)
        // 无延迟对照,允许 35% 余量:dt 抖动只该影响步长粒度,不该改变收敛速度量级
        assertEquals("抖动 vs 稳定帧率", stTime, converged, stTime * 0.35f + 0.05f)
    }

    // ---------- Kp 单调且全行程有效 ----------

    @Test
    fun `higher kp is strictly faster across the whole slider range`() {
        val range = listOf(0.03f, 0.05f, 0.07f, 0.10f, 0.15f, 0.20f, 0.30f, 0.40f)
        val times = range.map { kp -> kp to Sim(kp = kp, delayFrames = 0).timeToConverge(200f, 2f) }
        times.forEach { (kp, t) -> assertTrue("kp=$kp 未收敛", t > 0f) }
        for (i in 1 until times.size) {
            val (kpPrev, tPrev) = times[i - 1]
            val (kpCur, tCur) = times[i]
            assertTrue(
                "Kp 必须单调:kp=$kpCur 耗时 $tCur 应严格小于 kp=$kpPrev 的 $tPrev",
                tCur < tPrev
            )
        }
    }

    @Test
    fun `kp above zero point zero five is not pinned to a single gain`() {
        // 这是「降低 Kp 没用」的回归测试:接近增强开着时,0.05~0.20 全段
        // 有效增益都是 0.2,这一段的耗时会完全相同。
        val t005 = Sim(kp = 0.05f, delayFrames = 0).timeToConverge(200f, 2f)
        val t020 = Sim(kp = 0.20f, delayFrames = 0).timeToConverge(200f, 2f)
        assertTrue("kp 0.05 与 0.20 不应给出同一条轨迹", t005 > t020 * 1.5f)
    }

    // ---------- 平滑度单调 ----------

    @Test
    fun `higher damping monotonically reduces overshoot`() {
        // 取一个欠阻尼工况:高 Kp + 3 帧延迟,零阻尼时必然过冲。
        val dampings = listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, AimPidCore.MAX_DAMPING)
        val over = dampings.map { d ->
            d to Sim(kp = 0.35f, damping = d, delayFrames = 3).overshoot(200f)
        }
        assertTrue("零阻尼工况本应过冲,实测 ${over.first().second}", over.first().second > 1f)
        for (i in 1 until over.size) {
            val (dPrev, oPrev) = over[i - 1]
            val (dCur, oCur) = over[i]
            assertTrue(
                "平滑度必须单调:damping=$dCur 过冲 $oCur 不应大于 damping=$dPrev 的 $oPrev",
                oCur <= oPrev + 1e-3f
            )
        }
    }

    @Test
    fun `damping has real authority over the loop`() {
        // 「加高 Kd 没用」的回归测试:旧 Kd 滑条拉满只能改变约 1/3 的阻尼,
        // 现在这一个旋钮必须能把过冲压掉绝大部分。
        val none = Sim(kp = 0.35f, damping = 0f, delayFrames = 3).overshoot(200f)
        val full = Sim(kp = 0.35f, damping = 1.2f, delayFrames = 3).overshoot(200f)
        assertTrue("阻尼拉满应把过冲压到 1/4 以下(0→$none, 1.2→$full)", full < none * 0.25f)
    }

    // ---------- 保护与边界 ----------

    @Test
    fun `zero kp never moves`() {
        val s = Sim(kp = 0f)
        repeat(50) { s.step(200f) }
        assertEquals(0f, s.crosshair, 1e-6f)
    }

    @Test
    fun `stale or backwards timestamps fall back to reference period`() {
        val core = AimPidCore()
        assertEquals(AimPidCore.DT_REF, core.beginIteration(0L), 1e-9f)          // 无时间戳
        assertEquals(AimPidCore.DT_REF, core.beginIteration(1_000_000_000L), 1e-9f) // 首帧
        assertEquals(0.02f, core.beginIteration(1_020_000_000L), 1e-6f)         // 正常 20ms
        assertEquals(AimPidCore.DT_REF, core.beginIteration(1_020_000_000L), 1e-9f) // 重复
        assertEquals(AimPidCore.DT_REF, core.beginIteration(900_000_000L), 1e-9f)   // 倒退
        assertEquals(AimPidCore.DT_REF, core.beginIteration(2_000_000_000L), 1e-9f) // 间隔过大
    }

    @Test
    fun `frame drop does not produce a damping spike`() {
        val g = AimPidCore.Gains().also { it.kp = 0.2f; it.damping = 1.0f; it.kpYRatio = 1f }
        val core = AimPidCore()
        var t = 1_000_000_000L
        // 正常跑几轮，攒出指令速率历史
        var last = 0f
        repeat(5) {
            t += 20_000_000L
            core.beginIteration(t)
            last = core.stepX(200f, 0f, g)
            core.commit(last, 0f)
        }
        // 掉一大帧：dt 无效 → 速率历史被丢弃，阻尼项不该据此猛刹
        t += 500_000_000L
        core.beginIteration(t)
        val afterDrop = core.stepX(200f, 0f, g)
        assertTrue("掉帧后不应反向抽动(实测 $afterDrop)", afterDrop > 0f)
        // 断流期间一个指令也没发出去,真实速度就是 0,所以正确响应恰是**无阻尼的
        // 纯 P 步长** —— 不是「和稳态一样」。稳态那 $last 之所以更小,正是因为
        // 那时确实在动、确实该刹。上限仍由 P 项本身兜住,不会超。
        val pureP = 200f * 0.2f
        assertTrue("断流后不该残留刹车(实测 $afterDrop, 纯 P $pureP)", afterDrop > pureP * 0.95f)
        assertTrue("也不该超过纯 P(实测 $afterDrop)", afterDrop <= pureP * 1.05f)
    }

    @Test
    fun `non finite inputs are ignored`() {
        val g = AimPidCore.Gains()
        val core = AimPidCore()
        core.beginIteration(1_000_000_000L)
        assertEquals(0f, core.stepX(Float.NaN, 0f, g), 0f)
        assertEquals(0f, core.stepX(Float.POSITIVE_INFINITY, 0f, g), 0f)
        assertEquals(0f, core.stepX(10f, Float.NaN, g), 0f)
    }

    @Test
    fun `step cap scales with dt so it is a speed limit`() {
        val g = AimPidCore.Gains().also { it.maxStepAtRef = 600f }
        val core = AimPidCore()
        var t = 1_000_000_000L
        t += 20_000_000L; core.beginIteration(t)
        assertEquals(600f, core.stepCap(g), 1e-3f)
        t += 40_000_000L; core.beginIteration(t)   // 40ms
        assertEquals(1200f, core.stepCap(g), 1e-3f)
    }

    @Test
    fun `feedforward leads a moving target`() {
        val withKf = Sim(kf = 0.2f, delayFrames = 2)
        val withoutKf = Sim(kf = 0f, delayFrames = 2)
        // 目标以 300px/s 匀速走，比较稳态跟随误差
        fun trailingError(s: Sim): Float {
            var target = 400f
            var err = 0f
            repeat(300) {
                target += 300f / s.fps
                s.captureNs += (1e9f / s.fps).toLong()
                s.core.beginIteration(s.captureNs)
                err = target - s.crosshair
                val mv = s.core.stepX(err, 300f, s.gains)
                s.core.commit(mv, 0f)
                s.crosshair += mv
            }
            return abs(err)
        }
        val lead = trailingError(withKf)
        val noLead = trailingError(withoutKf)
        assertTrue("前馈应减小移动目标的跟随误差($lead vs $noLead)", lead < noLead)
    }
    @Test
    fun `brief target loss does not reverse the first step after relock`() {
        // 场景:快拉中(kp 0.2,丢前指令速率 ~2700px/s),自瞄目标闪断 100ms 又
        // 回来(同一条锁,beginIteration 判 contiguous —— 100ms < MAX_DT 150ms)。
        // 修复前:速率滤波器原样保留丢前的值,复锁第一步被幻影速度反向刹住,
        // 净输出朝远离目标的方向(-130px 量级,实测见 AimPidCore 的
        // PHANTOM_GRACE_S 注释)。修复后:滤波器按自己的时间常数衰减过,
        // 第一步必须是朝目标的正向。
        val g = AimPidCore.Gains().also { it.kp = 0.2f; it.damping = 0.4f; it.kpYRatio = 1f }
        val core = AimPidCore()
        var t = 1_000_000_000L
        // 阶段1:恒定大误差快拉 2s @50fps,建立稳态指令速率
        repeat(100) {
            t += 20_000_000L
            core.beginIteration(t)
            var mv = core.stepX(400f, 0f, g)
            val cap = core.stepCap(g)
            mv = mv.coerceIn(-cap, cap)
            core.commit(mv, 0f)
        }
        // 阶段2:目标闪断 100ms —— executeAiming 不被调,指针未抬,状态原样留着
        t += 100_000_000L
        core.beginIteration(t)
        // 阶段3:复锁,误差只剩 15px(准头已在目标上)
        val first = core.stepX(15f, 0f, g)
        assertTrue("复锁第一步不该反向(实测 $first)", first > 0f)
        // 修复后应接近纯 P 步长(滤波器衰减到 e^-5≈0.7%,阻尼项近似消失)
        val pureP = 15f * 0.2f
        assertTrue("复锁第一步不该残留幻影刹车(实测 $first, 纯P $pureP)",
            first > pureP * 0.5f)
    }

    @Test
    fun `normal frame jitter inside the grace window keeps damping authority`() {
        // 富余量(60ms)之内的掉帧不触发衰减 —— 抖动后阻尼权威必须还在,
        // 否则 PHANTOM_GRACE_S 取太低会反过来吃掉平滑度(见常量注释)。
        // 判据:大幅扫动后,正常路径(带阻尼)的过冲必须显著小于把阻尼
        // 关掉的对照 —— 掉一帧 50ms(超帧间隔 30ms,在富余量内)后仍如此。
        val kp = 0.35f
        fun sweep(damping: Float): Float {
            val s = Sim(kp = kp, damping = damping, fps = 50f, delayFrames = 3)
            // 先正常跑几步建立速率历史
            repeat(10) { s.step(400f) }
            // 掉一帧 50ms(超标称 20ms,富余 30ms < 60ms,不该衰减)
            s.captureNs += 30_000_000L
            return s.overshoot(400f, steps = 200)
        }
        val withD = sweep(0.8f)
        val noD = sweep(0f)
        assertTrue("富余量内的掉帧后阻尼仍该压过冲($withD vs $noD)", withD < noD * 0.5f)
    }
}
