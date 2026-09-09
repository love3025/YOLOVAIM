package io.github.love3025.yolovaim.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RecoilCore 的行为契约 —— 三条不变量 + 边界情形。
 *
 * 数值锚点全部来自 AimController 时代的实测记录
 * (RECOIL-REDESIGN-TASK.txt 第五节):
 *   50% 速度 = 90px/s、FIRE_LATCH_MS=100、衰减 = 0.7/帧@30fps 的指数等价。
 */
class RecoilCoreTest {

    private fun core() = RecoilCore()

    private fun rate(speed: Float) =
        RecoilCore.RATE_SLOW + (RecoilCore.RATE_FAST - RecoilCore.RATE_SLOW) * speed

    // ── 不变量 1：时间基 —— 推进总量与分帧方式无关 ──

    @Test
    fun `长按一秒 推进量与分帧数无关`() {
        // 同样 1s 开火:一帧走 vs 30 帧走,结果应一致
        val oneBigTick = core()
        oneBigTick.tick(true, held = true, taps = 0, dtSec = 1f, nowMs = 1000,
            speed = 0.5f, rangePx = 400f, resetMs = 300)

        val thirtyTicks = core()
        var now = 0L
        repeat(30) {
            thirtyTicks.tick(true, held = true, taps = 0, dtSec = 1f / 30f, nowMs = now,
                speed = 0.5f, rangePx = 400f, resetMs = 300)
            now += 33
        }
        assertEquals(oneBigTick.offsetY, thirtyTicks.offsetY, 0.5f)
        assertEquals(rate(0.5f), thirtyTicks.offsetY, 1f) // ≈ 90px
    }

    @Test
    fun `连点预算按毫秒扣 与帧相位无关`() {
        // 100ms 预算:一次 tick 走 vs 三次 33ms tick 走,总量一致
        val a = core()
        a.tick(true, held = false, taps = 1, dtSec = 0.1f, nowMs = 0,
            speed = 0.5f, rangePx = 400f, resetMs = 300)

        val b = core()
        var now = 0L
        b.tick(true, held = false, taps = 1, dtSec = 0f, nowMs = now,
            speed = 0.5f, rangePx = 400f, resetMs = 300) // 发预算,不推进
        repeat(3) {
            b.tick(true, held = false, taps = 0, dtSec = 0.033f, nowMs = now,
                speed = 0.5f, rangePx = 400f, resetMs = 300)
            now += 33
        }
        // a 走满 100ms 预算;b 走 99ms(3×33),差 1ms ≈ 0.9px
        assertEquals(a.offsetY, b.offsetY, rate(0.5f) * 0.001f + 0.01f)
    }

    // ── 不变量 2：连点每枪恒量 ──

    @Test
    fun `每枪推进量恒定 与按压时长无关`() {
        // taps=1 且 held=false:每枪推进 = 速率 × FIRE_LATCH_MS,不随 dt 变
        val c = core()
        c.tick(true, held = false, taps = 1, dtSec = 0.001f, nowMs = 0,
            speed = 0.5f, rangePx = 400f, resetMs = 300) // 只发预算
        c.tick(true, held = false, taps = 0, dtSec = 1f, nowMs = 1,
            speed = 0.5f, rangePx = 400f, resetMs = 300) // 预算耗尽,走 100ms
        assertEquals(rate(0.5f) * 0.1f, c.offsetY, 0.01f) // 90 × 0.1 = 9px
    }

    @Test
    fun `预算上限 MAX_PENDING_ROUNDS`() {
        val c = core()
        // 注入层边沿计数异常暴增(一次 100 发):预算被夹到 5 发
        c.tick(true, held = false, taps = 100, dtSec = 0f, nowMs = 0,
            speed = 1f, rangePx = 400f, resetMs = 300)
        c.tick(true, held = false, taps = 0, dtSec = 10f, nowMs = 1,
            speed = 1f, rangePx = 400f, resetMs = 300)
        assertEquals(rate(1f) * RecoilCore.FIRE_LATCH_MS / 1000f * RecoilCore.MAX_PENDING_ROUNDS,
            c.offsetY, 0.01f)
    }

    // ── 不变量 3：上限 ──

    @Test
    fun `持续开火停在 rangePx 不越界`() {
        val c = core()
        var now = 0L
        repeat(300) { // 10s @30fps,90px/s 应到 900px,但被夹在 400
            c.tick(true, held = true, taps = 0, dtSec = 1f / 30f, nowMs = now,
                speed = 0.5f, rangePx = 400f, resetMs = 300)
            now += 33
        }
        assertEquals(400f, c.offsetY, 0.01f)
        assertTrue(c.offsetY <= 400f)
    }

    // ── 回落语义 ──

    @Test
    fun `松手后 resetMs 内不衰减`() {
        val c = core()
        c.tick(true, held = true, taps = 0, dtSec = 0.1f, nowMs = 0,
            speed = 0.5f, rangePx = 400f, resetMs = 300)
        val before = c.offsetY
        // 松手 200ms < 300ms:不动
        c.tick(true, held = false, taps = 0, dtSec = 0.2f, nowMs = 300,
            speed = 0.5f, rangePx = 400f, resetMs = 300)
        assertEquals(before, c.offsetY, 0.001f)
    }

    @Test
    fun `超过 resetMs 后按指数衰减 且低于半像素归零`() {
        val c = core()
        c.tick(true, held = true, taps = 0, dtSec = 0.1f, nowMs = 0,
            speed = 0.5f, rangePx = 400f, resetMs = 300) // 9px
        // 衰减一个 33ms 帧:k = exp(0.033 × -10.7) ≈ 0.701
        c.tick(true, held = false, taps = 0, dtSec = 0.033f, nowMs = 400,
            speed = 0.5f, rangePx = 400f, resetMs = 300)
        assertEquals(9f * Math.exp(0.033 * RecoilCore.DECAY_EXP_PER_SEC).toFloat(), c.offsetY, 0.01f)
        // 衰减到 0.4px → 归零
        c.tick(true, held = false, taps = 0, dtSec = 1f, nowMs = 1400,
            speed = 0.5f, rangePx = 400f, resetMs = 300)
        assertEquals(0f, c.offsetY, 0.001f)
    }

    @Test
    fun `resetMs 等于 0 预算耗尽后立即清零`() {
        val c = core()
        c.tick(true, held = true, taps = 0, dtSec = 0.1f, nowMs = 0,
            speed = 0.5f, rangePx = 400f, resetMs = 0)
        val v = c.offsetY
        assertTrue(v > 0f)
        c.tick(true, held = false, taps = 0, dtSec = 0.033f, nowMs = 100,
            speed = 0.5f, rangePx = 400f, resetMs = 0)
        assertEquals(0f, c.offsetY, 0.001f)
    }

    // ── 开关与重置 ──

    @Test
    fun `开关关闭 状态归零 含预算`() {
        val c = core()
        c.tick(true, held = true, taps = 5, dtSec = 0.1f, nowMs = 0,
            speed = 0.5f, rangePx = 400f, resetMs = 300)
        assertTrue(c.offsetY > 0f)
        c.tick(false, held = true, taps = 0, dtSec = 0.033f, nowMs = 100,
            speed = 0.5f, rangePx = 400f, resetMs = 300)
        assertEquals(0f, c.offsetY, 0.001f)
        // 残留预算不应在重新开启后白推进
        c.tick(true, held = false, taps = 0, dtSec = 1f, nowMs = 200,
            speed = 0.5f, rangePx = 400f, resetMs = 300)
        assertEquals(0f, c.offsetY, 0.001f)
    }

    @Test
    fun `reset 清偏移与预算`() {
        val c = core()
        c.tick(true, held = true, taps = 3, dtSec = 0.05f, nowMs = 0,
            speed = 0.5f, rangePx = 400f, resetMs = 300)
        c.reset()
        assertEquals(0f, c.offsetY, 0.001f)
        c.tick(true, held = false, taps = 0, dtSec = 1f, nowMs = 100,
            speed = 0.5f, rangePx = 400f, resetMs = 300)
        assertEquals(0f, c.offsetY, 0.001f)
    }

    // ── 参数边界 ──

    @Test
    fun `负参数不崩且被夹`() {
        val c = core()
        c.tick(true, held = true, taps = 0, dtSec = 0.1f, nowMs = 0,
            speed = -0.5f, rangePx = -10f, resetMs = 300)
        assertEquals(0f, c.offsetY, 0.001f) // 负范围被夹成 0,偏移恒 0
        c.tick(true, held = true, taps = 0, dtSec = 0.1f, nowMs = 100,
            speed = 1.5f, rangePx = 400f, resetMs = 300)
        // 速度夹到 1:本 tick 从 0 起走 150px/s × 0.1s = 15px
        assertEquals(rate(1f) * 0.1f, c.offsetY, 0.01f)
    }

    // ── 外部补预算(自动扳机的枪) ──

    @Test
    fun `creditExternalShot 与 taps 同语义`() {
        val a = core() // 注入层边沿数到 1 发
        a.tick(true, held = false, taps = 1, dtSec = 0f, nowMs = 0,
            speed = 0.5f, rangePx = 400f, resetMs = 300)
        a.tick(true, held = false, taps = 0, dtSec = 1f, nowMs = 1,
            speed = 0.5f, rangePx = 400f, resetMs = 300)

        val b = core() // 扳机线程补 1 发
        b.creditExternalShot()
        b.tick(true, held = false, taps = 0, dtSec = 0f, nowMs = 0,
            speed = 0.5f, rangePx = 400f, resetMs = 300) // 只合并预算
        b.tick(true, held = false, taps = 0, dtSec = 1f, nowMs = 1,
            speed = 0.5f, rangePx = 400f, resetMs = 300)
        assertEquals(a.offsetY, b.offsetY, 0.01f)
        assertEquals(rate(0.5f) * 0.1f, b.offsetY, 0.01f)
    }

    @Test
    fun `外部预算也受 MAX_PENDING_ROUNDS 夹`() {
        val c = core()
        repeat(20) { c.creditExternalShot() } // 20 发
        c.tick(true, held = false, taps = 0, dtSec = 0f, nowMs = 0,
            speed = 1f, rangePx = 400f, resetMs = 300)
        c.tick(true, held = false, taps = 0, dtSec = 10f, nowMs = 1,
            speed = 1f, rangePx = 400f, resetMs = 300)
        assertEquals(rate(1f) * RecoilCore.FIRE_LATCH_MS / 1000f * RecoilCore.MAX_PENDING_ROUNDS,
            c.offsetY, 0.01f)
    }

    @Test
    fun `开关关闭时外部预算也清零`() {
        val c = core()
        c.creditExternalShot()
        c.tick(false, held = false, taps = 0, dtSec = 0.033f, nowMs = 0,
            speed = 0.5f, rangePx = 400f, resetMs = 300)
        c.tick(true, held = false, taps = 0, dtSec = 1f, nowMs = 100,
            speed = 0.5f, rangePx = 400f, resetMs = 300)
        assertEquals(0f, c.offsetY, 0.001f)
    }
}
