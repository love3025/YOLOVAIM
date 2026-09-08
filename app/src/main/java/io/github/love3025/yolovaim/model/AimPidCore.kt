package io.github.love3025.yolovaim.model

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * PID 位置环的纯计算核心 —— 帧率无关、每个旋钮单调。
 *
 * ## 为什么要有这个类
 *
 * 旧实现把控制律直接写在 [io.github.love3025.yolovaim.controller.AimController]
 * 里，而那个类要 `FloatService`，于是控制律**无法单元测试** —— 「Kp 越大越快、
 * Kd 越大越平滑」这种承诺只能靠实机手感确认。这里把它拆成纯 Kotlin，
 * 单调性和帧率无关性都由 `AimPidCoreTest` 直接验证。
 *
 * ## 与旧实现的三处差异（都是「参数调不动」的直接原因）
 *
 * **一、Kp 改成指数逼近，不再是「每帧走 kp·e」。**
 * 旧式 `kp*error` 是**每迭代**一次的比例，于是同一组参数在 30fps 和 50fps
 * 下走出两条不同的曲线 —— 用户看到的就是「速度不一致」。现在 kp 的语义是
 * 「每 [DT_REF] 收掉的误差比例」，先换算成速率 `-ln(1-kp)/DT_REF`，本轮再走
 * `1-exp(-rate·dt)`。50fps 下与旧实现逐位等价，其他帧率下**轨迹相同**。
 * 副作用是提频不再自动变快，而是变细、变准 —— 这是对的：控制频率该影响
 * 平滑度与延迟，不该影响激进程度，否则每次帧率波动都等于偷偷改了增益。
 *
 * **二、D 项和「速度阻尼」合成唯一一个阻尼旋钮。**
 * 旧实现两项并存：`kd * EMA(e - e_prev)` 与 `velocityDamping * 上一帧位移`。
 * 把两者都写成对闭合速率的作用可以看出它们是**同一项**：
 * ```
 *   旧 D 项      = -kd            · (vOwn - vTarget) · dt
 *   旧 速度阻尼   = -velocityDamping · vOwn            · dt
 * ```
 * 即等效阻尼系数是 `kd + velocityDamping`，而 `velocityDamping` 是写死的
 * 0.35、面板上没有。默认 kd=0.05 时用户手里那个滑条只掌管 12.5% 的阻尼，
 * 拉到上限 0.2 也只有 36% —— 这就是「加高 kd 没用」的全部原因。现在只有
 * [Gains.damping] 一个量，默认 0.40 = 旧的 0.35+0.05，默认阻尼总强度不变
 * 而旋钮有了权限（刹车曲线与旧版不完全相同，见 [RATE_TAU]）。
 *
 * **三、阻尼只作用在「已知的指令速率」上，不碰任何有噪的估计。**
 * 即 `derivative on measurement`：我们自己发出去的位移是精确已知的，而目标
 * 速度是从相邻帧框中心差分出来的估计（静止目标上光检测抖动就有 ±200px/s）。
 * 阻尼项里只留前者，噪声才不会被「平滑度」这个旋钮放大 —— 否则旋钮的作用
 * 方向会和它的名字相反（实测见 `AimDampingJitterTest`）。目标速度的超前补偿
 * 由 **Kf 前馈**单独负责，有自己独立的、用户可调的增益。
 * 旧实现要靠一个固定 EMA 去压 D 项的噪声尖峰，代价是给整条回路加相位滞后。
 *
 * ## 不在这里做的事
 *
 * 不估算触摸灵敏度（屏幕位移 / 手指位移），所以 `kp` 的物理单位仍然未知、
 * 仍然需要用户按自己的游戏灵敏度调一次。带回路延迟时稳定域由
 * `kp × 灵敏度 × 延迟` 决定，本类只保证**同一组参数在帧率抖动下表现一致**，
 * 不保证任意参数都稳定。控制频率、外推、灵敏度标定都是后续阶段的事。
 *
 * 纯 Kotlin，逐帧零对象分配，只在推理线程上使用。
 */
class AimPidCore {
    companion object {
        /**
         * 参考迭代周期(s)。所有滑条数值的语义都锚在这里，用户已保存的参数
         * 不需要重调。
         *
         * P / I / F 三项在 50fps 下与旧实现逐位等价。**阻尼项不是**：总强度
         * 默认等于旧的 `velocityDamping 0.35 + kd 0.05`，但它现在作用在滤波
         * 后的指令速率上（见 [RATE_TAU]，为的是让 0~1.5 全行程都稳定），
         * 所以大幅扫动时的刹车曲线与旧版不完全相同。
         */
        const val DT_REF = 0.02f

        /** dt 上限(s)。超过就当采集断流，丢弃速率历史而不是按一个巨大的 dt 积分。 */
        const val MAX_DT = 0.15f

        private const val MIN_DT = 0.001f
        private const val LN2 = 0.6931472f

        /**
         * 指令速率反馈的一阶滤波时间常数(s)。
         *
         * **不加这个滤波，平滑度滑条会有一道悬崖。** 阻尼项直接吃上一轮的指令
         * 位移时，递推是 `u_n = P_n - damping·u_{n-1}`，极点就在 `-damping`：
         * damping >= 1 时 |极点| >= 1，回路自己发散。实测 damping=0.8 过冲
         * 25px、1.2 过冲 298px —— 旋钮在 1.0 附近由「越大越稳」翻转成
         * 「越大越炸」，这正是用户要的「慢慢调」调不出来的那种非单调。
         * 旧实现把系数写死成 0.35 才没暴露出来。
         *
         * 加一阶滞后之后状态矩阵行列式为 0、迹为 `a(1-damping)`，
         * a = dt/(tau+dt) = 0.5 时极点是 `0.5(1-damping)`，稳定域扩到
         * `damping < 3` —— 覆盖整条 0~1.5 的滑条，全行程单调。
         */
        private const val RATE_TAU = 0.02f

        /**
         * 闪断判定的富余量(s),加在一个正常帧间隔之上构成阈值:gap 超过
         * `帧间隔 + 这个值` 才按 [RATE_TAU] 衰减速率历史。
         *
         * 上界受 [MAX_DT] 管:超过 MAX_DT 判断流,速率整个清零(老行为)。
         *
         ** 60ms 的由来,和它换掉的东⻄:
         *
         * gap 在 (帧间隔, 帧间隔+60ms] 内时**不清**速率 —— 这是正常掉帧抖动
         * 的地盘:30fps 的帧间隔 33ms,抖到 50-80ms 的一个慢帧不稀奇,清了它
         * 之后几帧阻尼权威下降,轻微更容易过冲。这条线取保守,把抖动挡在外面。
         *
         * gap 在 (帧间隔+60ms, MAX_DT] 内时衰减 —— 这是「目标闪断又回来」的
         * 地盘(检测框在置信度阈值上抖、动态 FOV 把目标瞬时挤出圈、半遮挡)。
         * 修法不是拍脑清零而是按滤波器自己的时间常数衰减:**这一段 gap 里
         * 一条 MOVE 都没发,真实指令速率就是 0,滤波器本来就该已经衰减到
         * e^(-gap/τ) ≈ 0.3%**。原样保留它,复锁第一步的阻尼项就用一个几百
         * px/s 的幻影速度猛刹 —— 快拉(丢前 ~3000px/s)时 P 项被压过,净输出
         * **反向** ~130px:准星朝远离目标的方向甩一下,足以把准头甩出触发
         * 判定。慢设备(30fps)上 2-4 帧的置信度抖动就是 60-130ms,正落在
         * 这个窗口里,是高频受害者。
         *
         * 阈值两侧的行为都有界且连续:60ms 处衰减因子 e^(-3)≈5%,与清零的
         * 差别已经小于检测噪声;抖动侧衰减因子 ≥ e^(-5)≈0.7%,等于没衰减。
         * 真机上如果 30fps 抖动实际能到 70ms+,把这个数往下调一档是唯一
         * 需要动的地方。
         */
        private const val PHANTOM_GRACE_S = 0.06f

        /** `-ln(1-kp)` 在 kp→1 处发散，留出余量。 */
        private const val MAX_KP = 0.95f

        /**
         * 平滑度上限。**1.0 不是随手取的整数，是极点的零点。**
         *
         * 指令速率反馈的极点是 `a(1-damping)`（a = dt/(RATE_TAU+dt) = 0.5 @50fps）：
         * damping = 1.0 时极点恰为 0，即无差拍(deadbeat)，阻尼回路自己不残留任何
         * 振荡。再往上极点转负，指令开始逐帧反号 —— 而那时已经没有东西可买了。
         * 实测（kp=0.35 + 3 帧延迟）：
         *
         * | damping | 0 | 0.2 | 0.4 | 0.6 | 0.8 | **1.0** | 1.2 | 1.5 |
         * |---|--:|--:|--:|--:|--:|--:|--:|--:|
         * | 过冲(px) | 146.5 | 100.8 | 64.9 | 36.9 | 16.6 | **0.38** | 0 | 0 |
         * | 指令反号率 | 47% | 50% | 53% | — | — | **61%** | — | 72% |
         *
         * 过冲在 1.0 处已经归零，1.0 以上只涨反号率。所以滑条止于此，
         * 导入的旧配置也在这里钳住。
         */
        const val MAX_DAMPING = 1.0f

        /**
         * kp（每 [DT_REF] 收掉的误差比例）→ 指数逼近速率(1/s)。
         *
         * kp=0.07 → 3.63/s，kp=0.2 → 11.2/s。
         */
        fun kpToRate(kp: Float): Float {
            val k = kp.coerceIn(0f, MAX_KP)
            if (k <= 0f) return 0f
            return -ln(1f - k) / DT_REF
        }
    }

    /**
     * 一轮的增益快照。由调用方每轮填好传入 —— 用户随时可能拖滑条，
     * 增益不该在核心里留副本，否则又多一处可能与面板漂开的状态。
     */
    class Gains {
        /** 响应速度。语义：每 [DT_REF] 收掉的误差比例。 */
        var kp = 0.07f
        /** Y 轴 Kp 折减（触控与目标运动在 Y 上噪声更大）。 */
        var kpYRatio = 0.6f
        var ki = 0.001f
        /** 目标速度前馈。 */
        var kf = 0.05f
        var kfYRatio = 1f
        /** 前馈内部放大：把 0~0.2 的滑条映射到有可见超前量的范围。 */
        var kfGain = 2.5f
        /**
         * 平滑度（唯一的阻尼项，无量纲）。0 = 纯 P，越大过冲越小、也越慢。
         * 默认 0.40 = 旧实现的 `velocityDamping 0.35 + kd 0.05`。
         * 使用时钳到 [MAX_DAMPING]。
         */
        var damping = 0.4f
        /** 积分分离阈值：误差大于它时不累积，只做半衰。 */
        var integralSeparation = 200f
        var integralLimit = 100f
        /** 单轮步长上限(px @ [DT_REF])，按 dt 缩放后使用。 */
        var maxStepAtRef = 600f
    }

    private val x = Axis()
    private val y = Axis()
    private var prevCaptureNs = 0L
    private var dt = DT_REF
    /**
     * 上一轮的标称帧间隔(s),供 [beginIteration] 判「这轮之前的空窗有多长」。
     * 故意不用 `dt`:dt 被钳在 [MIN_DT, MAX_DT] 里,掉帧那一轮会被钳大,
     * 拿它当「正常间隔」会把刚发生的掉帧又当成正常,富余量就永远算不出来。
     */
    private var prevDtForGap = DT_REF

    /**
     * 用本帧采集时刻推进一轮，返回本轮 dt(s)。
     *
     * 时间戳**倒退、重复或间隔超过 [MAX_DT]** 时退回 [DT_REF] 并丢弃速率历史 ——
     * 拿一个陈旧的 `prevStep` 除以一个错的 dt 会算出几千 px/s 的假指令速率，
     * 阻尼项会据此猛刹一下，表现就是掉帧时准星抽一下。
     *
     * gap 在**正常帧间隔的富余**([PHANTOM_GRACE_S])与 [MAX_DT] 之间时按
     * [RATE_TAU] 衰减速率历史而不是原样保留 —— 详见该常量的说明。
     *
     * 「**根本没有时间戳**」(captureNs <= 0) 是另一回事，不是异常：
     * [io.github.love3025.yolovaim.manager.InferenceManager] 那条循环从来不传。
     * 那种情况按标称 [DT_REF] 走并**保留**速率历史，否则阻尼项每轮都看到
     * `vOwn = 0`，等于在那条路径上把平滑度整个关掉。
     */
    fun beginIteration(captureNs: Long): Float {
        val contiguous: Boolean
        // 超出「一个正常帧间隔」的部分(s)。> 0 说明这轮之前有一段空窗,
        // 指令速率滤波器在这段空窗里应当衰减,见 PHANTOM_GRACE_S。
        var idleGapS = 0f
        if (captureNs <= 0L) {
            // 无时间戳源:按标称周期走,视作连续。
            dt = DT_REF
            prevCaptureNs = 0L
            contiguous = true
        } else {
            val gapNs = captureNs - prevCaptureNs
            contiguous = prevCaptureNs > 0L &&
                gapNs > 0L && gapNs <= (MAX_DT * 1e9f).toLong()
            dt = if (contiguous) (gapNs / 1e9f).coerceIn(MIN_DT, MAX_DT) else DT_REF
            if (contiguous && prevCaptureNs > 0L) {
                val nominalS = prevDtForGap
                idleGapS = (gapNs / 1e9f) - nominalS
                if (idleGapS < 0f) idleGapS = 0f
                if (idleGapS <= PHANTOM_GRACE_S) idleGapS = 0f
            }
            prevCaptureNs = captureNs
            prevDtForGap = if (contiguous) dt else DT_REF
        }
        x.beginIteration(dt, contiguous, idleGapS)
        y.beginIteration(dt, contiguous, idleGapS)
        return dt
    }

    /** @param targetVelPxS 目标在该轴上的速度(px/s)，已滤波。 */
    fun stepX(error: Float, targetVelPxS: Float, g: Gains): Float =
        x.step(error, targetVelPxS, dt, g, g.kp, g.kf)

    fun stepY(error: Float, targetVelPxS: Float, g: Gains): Float =
        y.step(error, targetVelPxS, dt, g, g.kp * g.kpYRatio, g.kf * g.kfYRatio)

    /**
     * 记录本轮**真正落到屏幕上**的位移。必须在总步长裁剪之后调用 —— 阻尼项
     * 下一轮要用它算指令速率，喂裁剪前的值就等于告诉阻尼「我走了没走的路」。
     */
    fun commit(stepX: Float, stepY: Float) {
        x.commit(stepX, dt)
        y.commit(stepY, dt)
    }

    /** 单轮步长上限(px)，按 dt 缩放。 */
    fun stepCap(g: Gains): Float = g.maxStepAtRef * (dt / DT_REF)

    fun reset() {
        prevCaptureNs = 0L
        prevDtForGap = DT_REF
        dt = DT_REF
        x.reset()
        y.reset()
    }

    private class Axis {
        private var integral = 0f
        private var prevError = 0f
        private var prevStep = 0f
        private var prevDt = DT_REF
        /** 滤波后的指令速率(px/s)。见 [RATE_TAU]。 */
        private var ownVelFiltered = 0f
        /** 上一轮调用方是否真的落了指令。见 [beginIteration]。 */
        private var committed = false
        // kp → rate 的换算里有一次 ln(),而 kp 只在用户拖滑条时变。缓存掉,
        // 每帧就只剩 P 项那一次 exp()。
        private var cachedKp = Float.NaN
        private var cachedRate = 0f

        /**
         * 每轮开始时推进速率估计。**必须在这里推进，不能放在 [step] 里** ——
         * 调用方有两条完全不进 [step] 的路径：
         *
         * 1. 收敛进死区后 `AimController` 直接 return，不算也不发 MOVE；
         * 2. 采集断流 / 时间戳异常。
         *
         * 放在 step() 里的后果：死区里悬停时滤波值再也不更新，一直停在最后
         * 一次真实步长对应的速率上，悬停越久这个**幻影速度**越稳。出死区的
         * 第一步于是被它反向刹住 —— 实测悬停 60 帧后第一步只剩纯 P 的 84%，
         * 断流 500ms 后只剩 50%，误差再小一点就直接变成反向移动。
         *
         * @param idleGapS 本轮之前的空窗时长(s),已经过 [PHANTOM_GRACE_S] 过滤
         *   (0 = 正常帧/可忽略抖动)。空窗里一条 MOVE 都没发,滤波器按它自己的
         *   时间常数衰减 —— 这正是它对「没有新信息」的正确响应,而不是把
         *   空窗前的旧值原样带过来当现状(那正是复锁反向抽动的来源)。
         */
        fun beginIteration(dt: Float, contiguous: Boolean, idleGapS: Float = 0f) {
            if (!contiguous) {
                // 断流期间一个指令也没发出去，真实速度就是 0，不是「上次那个」。
                prevStep = 0f
                prevDt = DT_REF
                ownVelFiltered = 0f
                committed = false
                return
            }
            // 没提交 = 这一轮准星一步没动。照实告诉滤波器。
            if (!committed) prevStep = 0f
            val instant = if (prevDt > 0f) prevStep / prevDt else 0f
            ownVelFiltered += (instant - ownVelFiltered) * (dt / (RATE_TAU + dt))
            if (idleGapS > 0f) {
                ownVelFiltered *= exp(-idleGapS / RATE_TAU)
            }
            committed = false
        }
        // kp → rate 的换算里有一次 ln(),而 kp 只在用户拖滑条时变。缓存掉,
        // 每帧就只剩 P 项那一次 exp()。
        fun step(
            error: Float,
            targetVelPxS: Float,
            dt: Float,
            g: Gains,
            kp: Float,
            kf: Float
        ): Float {
            if (!error.isFinite() || !targetVelPxS.isFinite()) return 0f

            // ---- I：以 error·秒累积,再按 DT_REF 归一,滑条量纲不变 ----
            if (abs(error) < g.integralSeparation) {
                if (error * prevError <= 0f) integral = 0f
                integral += error * (dt / DT_REF)
                integral = integral.coerceIn(-g.integralLimit, g.integralLimit)
            } else {
                // 旧实现每帧 `*0.5`。换成同一半衰期的指数：帧率变了衰减
                // 的**速度**不变,否则高帧率下积分会被清得快得多。
                integral *= exp(-LN2 * dt / DT_REF)
            }
            prevError = error

            // ---- P：指数逼近 ----
            if (kp != cachedKp) {
                cachedKp = kp
                cachedRate = kpToRate(kp)
            }
            var out = if (kp <= 0f) 0f else error * (1f - exp(-cachedRate * dt))

            // ---- I ----
            out += integral * g.ki

            // ---- F：目标速度前馈(px/s → 本轮 px) ----
            out += targetVelPxS * kf * g.kfGain * dt

            // ---- 阻尼：只作用在**自己发出去的**指令速率上 ----
            //
            // 教科书 PD 的 ė = vTarget - vOwn。这里**故意只取 -vOwn**,即所谓
            // 「derivative on measurement」(对测量量求导,而不是对误差求导) ——
            // 工业 PID 的默认形式,理由正是本项目这个场景:
            //
            //   vOwn    是我们自己发出的位移,精确已知,零噪声。
            //   vTarget 是相邻帧框中心的差分**估计**,静止目标上光检测抖动就有
            //           ±2px/帧 = ±200px/s。
            //
            // 把 vTarget 留在这一项里,等于让「平滑度」同时给噪声估计加增益 ——
            // 越想平滑,注入的噪声越多。实测(AimDampingJitterTest,修正前)残余
            // 抖动 RMS 随平滑度从 0.23px 涨到 0.95px、指令反号率 47%→72%,
            // 方向与旋钮的名字完全相反。旧实现的 velocityDamping 本来就只吃
            // vOwn,是合并两项时把 kd 那一半的 vTarget 带了进来。
            //
            // 目标速度的超前补偿是 **Kf 前馈**的职责,它有自己独立的、用户可调
            // 的增益(见上面的 F 项)。两件事不该共用一个旋钮。
            //
            // ownVelFiltered 由 beginIteration() 推进,这里只读。
            out -= g.damping.coerceIn(0f, MAX_DAMPING) * ownVelFiltered * dt

            return if (out.isFinite()) out else 0f
        }

        fun commit(step: Float, dt: Float) {
            prevStep = if (step.isFinite()) step else 0f
            prevDt = dt
            committed = true
        }

        fun reset() {
            integral = 0f
            prevError = 0f
            prevStep = 0f
            prevDt = DT_REF
            ownVelFiltered = 0f
            committed = false
            cachedKp = Float.NaN
        }
    }
}
