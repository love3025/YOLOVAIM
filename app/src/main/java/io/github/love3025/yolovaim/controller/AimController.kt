package io.github.love3025.yolovaim.controller
import io.github.love3025.yolovaim.model.DetectionInfo

import android.graphics.RectF
import android.util.Log
import io.github.love3025.yolovaim.service.FloatService
import io.github.love3025.yolovaim.injector.TouchInjectorInterface
import io.github.love3025.yolovaim.model.AreaConfig
import io.github.love3025.yolovaim.model.AimingState
import io.github.love3025.yolovaim.model.AimFinishController
import io.github.love3025.yolovaim.model.AimPidCore
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
         * 下压范围满量程 = 屏幕高度 × 该比例。1080p 上 = 400px，与上游
         * MAX_OFFSET 一致；用比例而非绝对值，换分辨率不必重调。
         *
         * **标度必须是屏幕空间，不能按目标框高取比例。** 枪口爬升是镜头的旋转，
         * 画面上的位移像素数由 FOV 与分辨率决定，与目标远近无关；而框高与距离
         * 强相关。`7a1e202` 把 range/rate 改成 `strength * boxH` 后，远距离
         * (实测框高 15~20px) 的上限塌到 17px、速率塌到 2.4px/s，比上游慢 37 倍，
         * 等于让远处目标自动关掉压枪 —— 而远距离恰恰最需要它。
         *
         * 速率/预算/衰减常数(RATE_*、FIRE_LATCH_MS、DECAY_EXP_PER_SEC 等)
         * 已随状态机一起搬进 RecoilCore,注释与锚点说明在那边。
         */
        private const val RANGE_MAX_RATIO = 0.37f

        /**
         * 死区占目标框对应边长的比例。见 [convergeTolerance]。
         *
         * 0.25 是起点不是定论，要在真机上验：远距离若出现新的颤动，说明这一带已经
         * 窄于检测噪声，那时该抬 [MIN_CONVERGE_TOL]，**不要**回到绝对像素。
         */
        private const val CONVERGE_TOL_BOX_RATIO = 0.25f
        /**
         * 容差下限(px)。触摸坐标是整数(moveTo 收 Int)，比 1px 更细的修正根本表达
         * 不出来，只会变成一串空转的 MOVE。
         */
        private const val MIN_CONVERGE_TOL = 1f

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
    // Y-axis gain scaling (prevents vertical oscillation when Kp is high)
    var kpYRatio = 0.6f

    // Integral separation threshold (in px) — disable Ki above this error magnitude
    var integralSeparationThresh = 200f

    // Integral clamp (anti-windup). Ki=0.001 is small enough that 100 is fine.
    private val integralLimit = 100f

    /**
     * 平滑度 —— **唯一的阻尼旋钮**，无量纲。0 = 纯 P，越大越平滑。
     *
     * 默认 0.40 = 旧实现的 `velocityDamping 0.35 + kd 0.05`，所以默认手感不变；
     * 区别是它现在完整地暴露在面板上，调它就真的在调阻尼。旧配置的迁移见
     * `ConfigManager` 里对 `aimDamping` 缺省值的处理。
     */
    @Volatile
    var aimDamping = 0.4f

    /**
     * 单轮位移上限(px @ [AimPidCore.DT_REF])。实际上限按本轮 dt 缩放，
     * 所以它现在是一个**速度**上限(600px/20ms = 30000px/s)，掉帧时不会因为
     * dt 变长而被同一个数字卡住。
     */
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
    /**
     * 收敛阈值(px) —— 现在是死区的**上限**，不再直接就是死区。实际用的容差由
     * [convergeTolerance] 按目标框缩放后给出，只会 <= 这个值。面板滑条(0~100)的
     * 语义因此是「最多容忍多少」，0 = 显式关掉死区。
     */
    var convergeThresh = 10f
    /**
     * px — circle radius around crosshair; targets outside this are ignored。
     *
     * 赋值同步 [dynamicCurrentFov]：后者是一份独立的动画状态，只有
     * [updateDynamicFov] 会推动它，而那只在「有检测结果」的帧里跑。
     * 不同步的后果：启动时配置写进了 aimFov，却没人碰
     * dynamicCurrentFov，[effectiveFov] 于是一直返回字段初值（默认 50）
     * —— 配置里存的是 100，HUD 却画 50，直到画面里第一次
     * 出现目标、updateDynamicFov 终于跑了一次才被纠正。
     */
    var aimFov = 50
        set(v) {
            if (field == v) return
            field = v
            // 无目标时的稳态半径就是 aimFov；正在收缩也让它从满圈
            // 重新收 —— 用户刚拖完滑条，下一帧应当先看到他设的那个大小。
            resetDynamicFov()
        }
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
    /**
     * 压枪状态机的纯计算核心。数学(斜坡/预算/衰减/上限)全部在
     * [io.github.love3025.yolovaim.model.RecoilCore],由 RecoilCoreTest 锁行为;
     * 这里只负责参数换算(范围 0~1 → px)与对外转发。
     */
    private val recoilCore = io.github.love3025.yolovaim.model.RecoilCore()

    /**
     * 把同一实例交给 RecoilDriver(FloatService 装配时取一次)。**写权在 driver**
     * —— 125Hz 时钟独占 tick;本类只读 [io.github.love3025.yolovaim.model.RecoilCore.offsetY]
     * 算 effectiveAimY。updateRecoil()/resetRecoil() 保留但只该在 driver 不存在
     * 的路径(单测/InferenceManager 死循环)里调;两边同时 tick 会把状态机推两倍速。
     */
    val recoilCoreForDriver: io.github.love3025.yolovaim.model.RecoilCore get() = recoilCore

    // Class filtering
    var aimClasses: MutableSet<Int> = mutableSetOf()
    var priorityClass: Int = -1
    var classAimOffsets: Map<Int, Float> = emptyMap()
    var boxAimRatio = 0.5f
    var classBoxAimRatios: Map<Int, Float> = emptyMap()

    // State
    val aimingState = AimingState()
    private val bezierMover = BezierMover()
    private val aimFinish = AimFinishController()
    private val pid = AimPidCore()
    private val gains = AimPidCore.Gains()

    /**
     * 上一次**真正发出去**的整数触摸坐标。`Int.MIN_VALUE` = 还没发过。
     *
     * 触摸协议收的是整数([TouchInjectorInterface.moveTo])，而 dt 归一化之后
     * 高帧率下每轮步长按比例变小：144fps、误差 5px、Kp 0.07 时一步只有
     * 0.125px，整数坐标要 8 帧才变一次 —— 另外 7 帧发的是**和上一条一模一样**
     * 的 MOVE。每条都是一次管道写加一次内核 input_event，而且就落在注入的
     * 关键路径上。
     *
     * 亚像素照常累加在 [AimingState.centerX] 这个 float 上，所以不丢精度、
     * 也不改轨迹 —— 只是不再重复发同一个坐标。
     */
    private var lastSentX = Int.MIN_VALUE
    private var lastSentY = Int.MIN_VALUE

    /**
     * 发 MOVE，整数坐标与上次相同则跳过。
     *
     * @return 是否真的发出去了（目前调用方不关心，保留给排障计数）
     */
    private fun emitMove(): Boolean {
        val ix = aimingState.centerX.toInt()
        val iy = aimingState.centerY.toInt()
        if (ix == lastSentX && iy == lastSentY) return false
        lastSentX = ix
        lastSentY = iy
        touchClient()?.moveTo(ix, iy)
        return true
    }

    /** 落指/抬指之后必须清，否则第一条 MOVE 可能被当成重复而被吞掉。 */
    private fun forgetSentPosition() {
        lastSentX = Int.MIN_VALUE
        lastSentY = Int.MIN_VALUE
    }

    /**
     * 接近段增强（[AimFinishController]）—— **默认关闭**。
     *
     * 它的设计目标是补偿「P 输出随误差线性衰减、末段拖尾」，做法是在所有刹车项
     * **之后**把总输出重新抬到 `min(4·kp, 0.2)·|e|`。两个后果：
     *
     * 1. 有效比例增益变成 `min(4·kp, 0.2)`，于是 Kp 滑条在 0.05~0.20 这一整段
     *    输出同一个 0.20 —— 面板上 75% 的行程是死的，「降低 Kp 没用」。
     * 2. 它的 `weight` 由单帧误差差分估出的靠近速度决定，而静止目标的检测框
     *    本身就有 ±1~2px 的抖。每帧真实靠近量只有 2~4px 时，噪声让 `weight`
     *    在 0~1 之间摆，**环路增益以帧率在 1× 和 ~3× 之间跳变**。一个增益随机
     *    变化 3 倍的系统不存在「又快又稳」的参数组，这就是抖动的主因。
     *
     * 保留开关是为了能实机 A/B 对照，不是推荐项。打开后 Kp/平滑度两个旋钮
     * 都会重新失去权限。它原本要解决的「接近段慢」，现在由 dt 归一化
     * (见 [AimPidCore]) 与放宽后的 Kp 上限承担。
     */
    // @Volatile：写在 GUI 线程(guiPanel.onApproachAssistChanged)，读在推理线程。
    // 原先这个 setter 还顺手调了 aimFinish.reset() —— 那是从 GUI 线程去改
    // 推理线程正在读的十几个字段，是一条真实的数据竞争。没有必要：整形器
    // 自己就按采集时间戳判连续性，关掉再打开时 previousCaptureNs 早已陈旧，
    // 第一次 observe() 会自行重建历史。
    @Volatile
    var approachAssistEnabled = false

    // Dynamic FOV state — `dynamicCurrentFov` is the actual radius used for
    // target selection and rendered as the FOV circle; it ranges from
    // `aimFov` (full circle when no target) down to `targetMaxDim + padding`
    // while locked onto a target.
    // @Volatile: aimFov 的 setter 会调 resetDynamicFov() 写这两个字段,而那是
    // 从 GUI 线程来的(guiPanel.onAimFovChanged / onDynamicFovChanged),读侧是
    // 推理线程的 updateDynamicFov / effectiveFov。arm64 上单字读写不会撕裂,但
    // 没有 volatile 就没有可见性保证 —— 用户拖完滑条可能好几帧看不到变化。
    @Volatile private var dynamicCurrentFov = aimFov.toFloat()
    @Volatile private var lostTargetMs: Long = -1

    val effectiveFov: Int
        get() = dynamicCurrentFov.toInt().coerceIn(20, maxOf(aimFov, 20))

    /**
     * 本帧的收敛容差(px)，单轴。进了这一带就算「到位」，不再发 MOVE。
     *
     * 死区本身是必需的：检测框每帧都在抖，没有它自瞄会永远追噪声 —— 每帧注入一次
     * 几像素的随机 MOVE，准星肉眼可见地颤，而真手指不会以 30Hz 永久微颤；带延迟的
     * 闭环追小误差又正是 PID 失稳的地方(derivFilteredX/velocityDamping 那一堆就是在
     * 打这场仗)。触摸坐标还是整数，1px 以下是空转。
     *
     * 但它的单位必须是**目标尺寸的比例，不能是绝对像素**。死区滤的是检测噪声，而框的
     * 定位误差大致与目标尺寸成正比。写成绝对 10px 的后果：同一个数在近距离(框高
     * ~100px)只占 10%，在远距离(框高 15~20px)却是半个框。而 PID 步长与误差成正比、
     * 越接近越慢，所以它一定停在**自己进来那一侧**的内侧、残差接近满阈值，方向就是
     * 接近方向；目标不动这个残差就永久留着 —— 现象是「锁住了但停在框边缘」，而扳机
     * 判的是框内，于是一枪不出。
     *
     * 与压枪标度恰好相反、道理恰好一致：压枪对抗镜头爬升(与距离无关 → 必须绝对屏幕
     * 像素，见 RATE_* 那段)，死区滤检测噪声(随目标尺寸缩放 → 必须取比例)。两个量性质
     * 不同，各自的单位也就不同；混用哪一个都会在远距离塌掉。
     *
     * [convergeThresh] 仍是上限，所以近距离目标行为与从前完全一致，只有小框真的收紧。
     * **扳机的第二条判据必须用同一个返回值**(见 TriggerController.processTrigger)，
     * 否则「自瞄收敛 ⇒ 扳机在靶」这条不变量又会裂开。
     *
     * @param boxDim 目标框在该轴上的边长(px)。<= 0 表示拿不到框尺寸，退回绝对阈值。
     */
    fun convergeTolerance(boxDim: Float): Float {
        // 用户把阈值拉到 0 = 显式关掉死区(abs(e) < 0 恒假，每帧都动)。别用下限把它
        // 悄悄改回 1px。
        if (convergeThresh <= 0f) return 0f
        if (boxDim <= 0f) return convergeThresh
        return min(convergeThresh, boxDim * CONVERGE_TOL_BOX_RATIO)
            .coerceAtLeast(MIN_CONVERGE_TOL)
    }

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
            aimFinish.reset() // new lock: old target's approach history is not reusable
            // 前馈速度同理不可复用：updateVelocity 算的是相邻帧中心差，
            // 跨目标的差是跳变不是速度，喂进 EMA 就是一个假速度尖峰。
            aimingState.prevTargetX = Float.NaN
            aimingState.prevTargetY = Float.NaN
            // PID 的积分与指令速率同理:旧目标攒下的积分对新目标是噪声(符号都
            // 可能反),旧目标复锁前的指令速率则是幻影刹车(见 AimPidCore
            // PHANTOM_GRACE_S)。同一条锁路径里 beginIteration 的衰减管不到
            // 「换目标」—— 那没有 gap。
            pid.reset()
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

    /**
     * [executeAiming] 真正 steer 到的 Y —— 原始瞄点 + 压枪偏移。
     *
     * 单独暴露是为了让**扳机判定拿到同一个 Y**。压枪偏移只加在自瞄的目标点上，
     * 而扳机判的是原始检测框：持续开火时 recoilOffsetY 能累到上百 px，准星被压到
     * 框下边以外，自瞄仍然「收敛」、扳机却认为离靶 —— 连发中途自己停火就是这么
     * 来的。两边共用这个函数，那条错位不再可能出现。
     */
    fun effectiveAimY(aimY: Float): Float = if (recoilEnabled) aimY + recoilCore.offsetY else aimY

    /**
     * @param tolX / @param tolY 本帧的收敛容差，来自 [convergeTolerance]。缺省退回
     *   绝对 [convergeThresh]（只有 InferenceManager 里那份死循环还走这条）。
     * @param frameCaptureNs 本帧采集时刻(System.nanoTime 时基)。缺失/陈旧时保持原 PID，
     *   不启用接近段增益；只用于观察窗口和增益上限，不估算触摸灵敏度。
     */
    fun executeAiming(
        targetX: Float,
        targetY: Float,
        cx: Float,
        cy: Float,
        tolX: Float = convergeThresh,
        tolY: Float = convergeThresh,
        frameCaptureNs: Long = 0L,
        boxCenterX: Float = Float.NaN,
        boxCenterY: Float = Float.NaN
    ) {
        val t0 = System.nanoTime()
        // dt 与目标速度都在这里推进,不在调用方 —— 旧实现里 FloatService 先调
        // updateVelocity() 再调这里,而 dt 只有这里知道,于是两边一旦顺序变了
        // 速度就会用错的周期折算。集中在一处之后那种错位不再可能出现。
        val dt = pid.beginIteration(frameCaptureNs)
        aimingState.updateVelocity(
            if (boxCenterX.isNaN()) targetX else boxCenterX,
            if (boxCenterY.isNaN()) targetY else boxCenterY,
            dt
        )
        // 压枪：偏移量由 updateRecoil() 每帧维护，这里只读。
        // 累加/清零绝不能放回这里 —— executeAiming() 只在选到目标时才被调用
        // (FloatService 的 target != null 分支)，把状态机放进来就等于
        // 「无目标 → 既不累加也不清零」，偏移被冻结着带到下一次交火。
        val adjustedTargetY = effectiveAimY(targetY)
        if (aimMode == 1) {
            aimFinish.reset()
            pid.reset()
            executeAimingBezier(targetX, adjustedTargetY, cx, cy, tolX, tolY)
        } else {
            // 包含已经到位的帧：清掉增益的连续出界历史，单帧检测抖动不应
            // 立刻唤醒增强。精度仍完全由同一对 tolX/tolY 决定。
            if (approachAssistEnabled) {
                aimFinish.observe(
                    targetX - cx, adjustedTargetY - cy, tolX, tolY,
                    convergeThresh, frameCaptureNs, t0
                )
            }
            executeAimingPid(targetX, adjustedTargetY, cx, cy, tolX, tolY)
        }
        if (TRACE) {
            val dtMs = (System.nanoTime() - t0) / 1e6
            if (dtMs > 0.05) Log.d(LAT_TAG, String.format(java.util.Locale.US, "executeAiming=%.2fms mode=%d", dtMs, aimMode))
        }
    }

    private fun executeAimingBezier(
        targetX: Float,
        targetY: Float,
        cx: Float,
        cy: Float,
        tolX: Float,
        tolY: Float
    ) {
        val errorX = targetX - cx
        val errorY = targetY - cy

        if (!aimingState.pointerDown) {
            if (Math.abs(errorX) < tolX && Math.abs(errorY) < tolY) return

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

            forgetSentPosition()
            touchClient()?.swipe(aimingState.centerX.toInt(), aimingState.centerY.toInt(), aimingState.centerX.toInt(), aimingState.centerY.toInt(), 0)
            aimingState.pointerDown = true
            val now = System.currentTimeMillis()
            val dist = Math.sqrt((errorX * errorX + errorY * errorY).toDouble()).toFloat()
            val duration = (bezierDuration * 5 + dist * 0.3f).toInt().coerceIn(200, 800)
            bezierMover.start(now, now + duration)
        } else {
            if (Math.abs(errorX) < tolX && Math.abs(errorY) < tolY) {
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
            emitMove()
        }
    }

    private fun executeAimingPid(
        targetX: Float,
        targetY: Float,
        cx: Float,
        cy: Float,
        tolX: Float,
        tolY: Float
    ) {
        val errorX = targetX - cx
        val errorY = targetY - cy
        if (!aimingState.pointerDown) {
            if (Math.abs(errorX) < tolX && Math.abs(errorY) < tolY) return
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
            aimingState.prevTargetX = Float.NaN
            aimingState.prevTargetY = Float.NaN
            aimingState.smoothVelX = 0f
            aimingState.smoothVelY = 0f
            // 落指等于换了一个起点：积分与指令速率历史都必须清，否则上一次
            // 交火攒下的积分会在按下的第一帧直接推出去一步。
            pid.reset()
            aimFinish.reset() // DOWN has not produced an observed response yet
            forgetSentPosition()
            touchClient()?.swipe(aimingState.centerX.toInt(), aimingState.centerY.toInt(), aimingState.centerX.toInt(), aimingState.centerY.toInt(), 0)
            aimingState.pointerDown = true
            Log.d(TAG, "aim DOWN at (${aimingState.centerX}, ${aimingState.centerY}) target=($targetX, $targetY)")
        } else {
            if (Math.abs(errorX) < tolX && Math.abs(errorY) < tolY) {
                // 收敛后保持按住，检测框消失时由 InferenceManager 负责 lift
                return
            }

            // 控制律整体在 AimPidCore 里：帧率无关 + 单一阻尼项。
            // 这里只负责把面板上的量装进 Gains，以及处理抖动/裁剪/落点。
            gains.kp = kp
            gains.kpYRatio = kpYRatio
            gains.ki = ki
            gains.kf = kf
            gains.kfYRatio = kfYRatio
            gains.kfGain = kfGain
            gains.damping = aimDamping
            gains.integralSeparation = integralSeparationThresh
            gains.integralLimit = integralLimit
            gains.maxStepAtRef = maxPerFrame

            var rawX = pid.stepX(errorX, aimingState.smoothVelX, gains)
            var rawY = pid.stepY(errorY, aimingState.smoothVelY, gains)

            if (aimSwayAmplitude > 0) rawY += computeSway()

            // 接近段增强默认关闭，见 approachAssistEnabled 的说明。
            if (approachAssistEnabled) {
                rawX = aimFinish.shapeX(rawX, kp)
                rawY = aimFinish.shapeY(rawY, kp * kpYRatio, AimFinishController.MAX_ASSIST_KP * kpYRatio)
            }

            // 总位移上限按 dt 缩放,所以它是一个速度上限而不是「每帧多少像素」。
            val cap = pid.stepCap(gains)
            val moveDist = Math.sqrt((rawX * rawX + rawY * rawY).toDouble()).toFloat()
            var moveX = rawX
            var moveY = rawY
            if (moveDist > cap) {
                moveX = rawX / moveDist * cap
                moveY = rawY / moveDist * cap
            }
            // 必须提交**裁剪后**的位移：阻尼项下一轮据此算指令速率,喂裁剪前的
            // 值等于告诉它「我走了没走的路」,大幅扫动之后会多刹一下。
            pid.commit(moveX, moveY)
            aimingState.centerX += moveX
            aimingState.centerY += moveY
            if (applyDragSafety()) return
            emitMove()
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
            aimFinish.reset()
            pid.reset()
            forgetSentPosition()
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
        aimFinish.reset()
        pid.reset()
        forgetSentPosition()
        touchClient()?.lift()
        aimingState.pointerDown = false
        aimingState.lockedTarget = null
    }

    /** 本帧没有可瞄目标时丢弃末段历史；不改变触点或压枪的生命周期。 */
    fun clearFinishHistory() = aimFinish.reset()

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
        // 数学细节(预算毫秒扣减/衰减时间归一/上限兜底)全部在 RecoilCore,注释也
        // 在那边,由 RecoilCoreTest 锁行为。这里只做参数换算:下压范围 0~1 → px
        // (屏幕空间,标度 = RANGE_MAX_RATIO × recoilRefHeight,与目标远近无关)。
        recoilCore.tick(
            enabled = recoilEnabled, held = held, taps = taps,
            dtSec = dtSec, nowMs = nowMs,
            speed = recoilSpeed,
            rangePx = recoilStrength * RANGE_MAX_RATIO * recoilRefHeight,
            resetMs = recoilResetIntervalMs
        )
    }

    /** 排障用：外部只读当前偏移量。 */
    val recoilOffsetDebug: Float get() = recoilCore.offsetY

    fun resetRecoil() {
        recoilCore.reset()
    }

    fun reset() {
        aimFinish.reset()
        aimingState.reset()
        bezierMover.cancel()
        resetRecoil()
    }
}
