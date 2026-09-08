package io.github.love3025.yolovaim.model

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 控制器状态生命周期的回归测试 —— 「调用方这一轮没发指令」和「采集断流」
 * 两种情况下，阻尼项用的速率估计必须仍然对应**真实发生过的位移**。
 */
class AimPidLifecycleTest {

    private fun gains(damping: Float = 0.4f) = AimPidCore.Gains().also {
        it.kp = 0.15f; it.ki = 0f; it.kf = 0f; it.damping = damping
        it.kpYRatio = 1f; it.kfYRatio = 1f
    }

    /**
     * 进死区（收敛后不再发 MOVE）悬停一段时间，再让目标移出死区。
     *
     * 悬停期间准星**一步没动**，所以出死区的第一步不该被任何刹车拖住，
     * 更不该被拖成反向。`AimController` 在收敛分支是直接 `return` 的，
     * 不会调 `commit()` —— 核心必须自己认出「这一轮没落指令」。
     */
    @Test
    fun `hovering in the deadzone must not leave a phantom velocity`() {
        val core = AimPidCore()
        val g = gains()
        var t = 1_000_000_000L
        var cross = 0f

        // 1) 正常追一段，建立起真实的指令速率
        repeat(30) {
            t += 20_000_000L
            core.beginIteration(t)
            val s = core.stepX(100f - cross, 0f, g)
            core.commit(s, 0f)
            cross += s
        }
        val lastRealStep = 100f - cross

        // 2) 进死区悬停 60 帧：beginIteration 照常走，但不算也不提交任何步长
        //    （这正是 AimController 收敛分支的行为）
        repeat(60) {
            t += 20_000_000L
            core.beginIteration(t)
        }

        // 3) 目标移出死区 8px，看第一步
        t += 20_000_000L
        core.beginIteration(t)
        val firstStep = core.stepX(8f, 0f, g)
        println("悬停后第一步 = %.4fpx (纯 P 应为 %.4fpx, 悬停前残余误差 %.2fpx)"
            .format(firstStep, 8f * 0.15f, lastRealStep))

        assertTrue("出死区第一步不该反向(实测 $firstStep)", firstStep > 0f)
        // 悬停期间没动过，所以不该有任何刹车 —— 应该就是裸 P
        assertTrue(
            "出死区第一步应约等于纯 P 步长(实测 $firstStep, 期望 ~${8f * 0.15f})",
            firstStep > 8f * 0.15f * 0.9f
        )
    }

    /** 采集断流很久之后，速率估计必须已经衰减掉，而不是原样保留。 */
    @Test
    fun `a long capture stall clears the rate estimate`() {
        val core = AimPidCore()
        val g = gains(damping = AimPidCore.MAX_DAMPING)
        var t = 1_000_000_000L
        repeat(10) {
            t += 20_000_000L
            core.beginIteration(t)
            val s = core.stepX(200f, 0f, g)
            core.commit(s, 0f)
        }
        // 断流 500ms（远超 MAX_DT）：期间一个指令也没发出去，真实速度就是 0
        t += 500_000_000L
        core.beginIteration(t)
        val afterStall = core.stepX(200f, 0f, g)
        val pureP = 200f * 0.15f
        println("断流后第一步 = %.4fpx (纯 P = %.4fpx)".format(afterStall, pureP))
        assertTrue("断流后不该残留刹车(实测 $afterStall, 纯 P $pureP)", afterStall > pureP * 0.95f)
        assertTrue("也不该超过纯 P", afterStall <= pureP * 1.05f)
    }
}
