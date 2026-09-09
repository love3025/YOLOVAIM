package io.github.love3025.yolovaim.controller

import android.util.Log
import io.github.love3025.yolovaim.injector.TouchInjectorInterface
import io.github.love3025.yolovaim.model.AreaConfig
import io.github.love3025.yolovaim.model.DetectionInfo
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 自动扳机。
 *
 * ## 时间基:判据走推理帧,计时走自己的时钟
 *
 * 反应速度 / 冷却是**墙钟阈值**,而判据(准星在不在靶上)只有推理帧才能刷新。
 * 这两件事必须分开:
 *
 *  - 判据: [processTrigger] 每推理帧算一次,写进快照;
 *  - 计时与出枪: [clock] 这条 [TICK_MS] 的时钟线程推进 [advance]。
 *
 * 旧实现把两者一起压在推理帧上,于是有两个叠加的延迟缺陷:
 *
 *  1. **出枪时刻被量化到一个推理帧周期**。阈值只在帧边界比较,15-30fps 就是
 *     33-66ms 一格,而阈值跨越点落在帧间隔的哪个相位是随机的 —— 同一套设置
 *     每枪延迟都不一样。用户看到的就是「开枪慢,而且慢得不固定」。
 *  2. **计时起点是「推理算完」的时刻**,反应速度因此叠加在采集+推理延迟之上。
 *     推理耗时一抖(QNN→GPU→CPU 回退、降频、检测数变化)延迟跟着抖。
 *
 * 压枪当初就是因为同一个缺陷从帧基换成时间基的,见 RECOIL-REDESIGN-TASK.txt
 * §5.3「单份 kick 的落位快慢被帧周期顶死」—— 扳机这条路一直没跟着改。
 *
 * 现在:计时起点是**该帧的采集时刻**(processTrigger 的 frameCaptureNs,取自
 * `Image.getTimestamp()`),出枪时刻是「采集时刻 + 反应速度」,抖动 ~2ms 且与
 * 推理耗时无关。推理比反应速度还慢时 elapsed 一上来就已过阈值 → 判定成立当帧
 * 立刻出枪,反应速度成了**下限**而不是加数。
 *
 * 时钟线程**不会凭空开枪**:判据只来自最近一帧的快照,且快照超过
 * [SNAP_MAX_AGE_MS] 没刷新就不再算在靶 —— 采集卡死 / 切后台 / 推理挂掉时
 * 自动停火,不会变成瞎打的全自动。
 *
 * ## 冷却语义:任意两枪之间的最短间隔
 *
 * 两段式(反应速度/冷却)之外,冷却还是一道**硬地板**:出枪时刻 ≥ 上一枪
 * 成功派发时刻 + 冷却,与阶段窗口取 max。没有这道地板,离靶重置会让下一枪
 * 退回反应速度阶段,两条真实路径都能借此把设置的开火间隔整个绕过 —— 见
 * [lastShotNs] 的说明。
 *
 * @param touchClient 必须是 `TouchService`(不是裸注入器):[fireTriggerTap] 在锁内
 *   调用它,而 TouchService.triggerTap 是投给 tapExecutor 的非阻塞派发。裸注入器
 *   的 triggerTap 会在调用线程上 sleep(触摸时长)+ 等 IPC,那样时钟线程会被自己
 *   的枪堵住。
 * @param fireArea 开火区。**用户没在「区域设置」里配过时必须返回 null** —— 早期版本
 *   在配置为空时补的是 `AreaConfig()` 默认值(150x150 @ 0,0),于是每一枪都点在屏幕
 *   左上角,扳机看起来完全失效但日志里一切正常。
 * @param fallbackTapCircle 开火区未配置时的兜底落点:(圆心x, 圆心y, 半径),取自
 *   FloatService 持有的那个真实触发圆圈。这里不再自己维护一份 overlay ——
 *   曾经有过,但 setupTriggerOverlay() 从未被调用,坐标恒为 0,兜底路径同样点左上角。
 */
class TriggerController(
    private val touchClient: () -> TouchInjectorInterface?,
    private val fireArea: () -> AreaConfig?,
    private val fallbackTapCircle: () -> Triple<Int, Int, Int>
) {
    /**
     * 每成功派发一枪后的回调(锁内调用,必须非阻塞)。
     *
     * 用途:压枪。自动扳机的点击在注入层被 TOUCH_TRIGGER_SLOT 排除、不计入
     * 开火区上升沿(touch_core 的 updateZones),于是扳机连发时压枪状态机
     * 看不到任何 taps,只能靠 triggerFired 锁存电平当长按 —— 同射速下自动
     * 扳机会比人手连点多压一倍的量(电平恒真 vs 每枪 100ms 预算)。挂上这个
     * 回调后,FloatService 把它接到 RecoilCore:每枪一发 FIRE_LATCH_MS 预算,
     * 与人手连点同一语义。
     */
    var onShotFired: (() -> Unit)? = null
    companion object {
        private const val TAG = "TriggerController"

        /**
         * 离靶宽限期(ms):连续离靶不足这个时长只算「抖了一下」,反应速度/冷却的
         * 计时保持不变。
         *
         * 约 2 个推理帧 @15fps —— 够吃掉检测框的逐帧抖动,又短于任何真实的
         * 「目标离开准星」。放宽这里不会误射:开枪始终要求快照为「在靶」,宽限
         * 只保留计时,所以最坏的后果是准星回到靶上后出枪更快。
         */
        private const val OFF_TARGET_GRACE_MS = 150L

        /**
         * 快照保鲜期(ms):最近一帧的判据超过这个时长没刷新,时钟线程一律按离靶
         * 处理,不许开枪。
         *
         * 这是「计时与判据解耦」的安全边界 —— 没有它,采集停了(切后台、
         * MediaProjection 断、推理挂死)而快照恰好停在「在靶」上,时钟就会按
         * 冷却节奏一直打空枪。
         *
         * 取值只需**覆盖一个推理帧间隔**:准星还在靶上时每一帧都会刷新快照,
         * 所以它只在「帧不来了」的时候才会到期(目标真的离开是由 snapOnTarget
         * 立刻变 false 表达的,不走这条路)。100ms ≈ 15fps 一帧再加抖动 ——
         * 本项目推理帧率的下限;比这更慢的设备退回「等下一帧再开枪」,也就是
         * 修改前的行为,不会更差。上限则把「凭旧证据开枪」压在 0.1s 内,远小于
         * 管计时的 [OFF_TARGET_GRACE_MS]。
         *
         * **按快照发布时刻算,不是采集时刻**:采集时刻已经含了一整条采集+推理
         * 延迟,慢设备上快照会一出生就过期,扳机反而彻底不响。
         */
        private const val SNAP_MAX_AGE_MS = 100L

        // advance() 的返回值:该在锁外执行哪些动作(急停是阻塞 IPC,开枪要能回填
        // 「是否真的派发出去」,两者都不能在持锁时做)。
        private const val ACT_NONE = 0
        private const val ACT_LIFT = 1
        private const val ACT_FIRE = 2

        /**
         * 时钟线程的步长(ms)。2ms 换来的是把出枪抖动从「一个推理帧」(33-66ms)
         * 压到与调度同量级;代价是一条只在「已上膛」期间存在的线程每秒醒 500 次
         * 读几个字段 —— 相对于旁边那条连续跑 YOLO 的推理线程可以忽略。
         */
        private const val TICK_MS = 2L

        /**
         * 丢枪重试的固定余量(ms),加在 [triggerTouchDuration] 上构成完整退避,见
         * [commitShot]。
         *
         * 没有退避的后果不是误射(tapInFlight 的 CAS 挡住了重复点击),而是空转:
         * 冷却短于触摸时长时(滑条允许:冷却最小 10ms、触摸时长最大 50ms),
         * 出枪条件每 2ms 满足一次,每次都撞在还没走完的 tap 上被丢 —— 上一枪
         * 占用的那几十毫秒里,时钟线程以 500Hz 忙轮询、每 2ms 刷一条 warn。
         *
         * 退避量级取「上一枪大概率已走完」:tapInFlight 的占用时长 ≈ 触摸时长 +
         * 一次派发,所以按触摸时长退避再加这个 IPC 余量,重试落地时 tap 基本已
         * 释放。该场景下实际射速本来就被触摸时长顶死,推迟到退避边界不损失
         * 任何东西;tap 被注入层卡死(真正要排障的故障)时,退避也把 warn 的
         * 刷屏率压到人读得过来的程度。
         */
        private const val RETRY_MARGIN_MS = 10L

        private const val MS_TO_NS = 1_000_000L

        /** 状态排障日志的间隔(ms)。一条顶三种故障的判别,间隔够大不会冲爆 logcat。 */
        private const val TRACE_STATE_MS = 300L
    }

    // Trigger settings
    var triggerEnabled = false
    var triggerReactionSpeed = 100
    var triggerCooldown = 200
    var triggerUpFluct = 3
    var triggerDownFluct = 3
    var triggerTouchDuration = 10
    var autoStopEnabled = false
    var triggerOffsetYRatio = 0f
    /**
     * 触发半径(采集像素,0.1 步进):准星到检测框的**距离**在这个数以内就算在靶,框内恒为 0。
     * 0 = 关闭,退回「准星必须落在框内」。见 [processTrigger] 判据三。
     */
    var triggerRadiusPx = 0f
    var triggerClasses: MutableSet<Int> = mutableSetOf()
    var classTriggerOffsets: Map<Int, Float> = emptyMap()

    /** 状态机 + 快照的唯一锁。推理线程与时钟线程都只在持锁时读写下面这些字段。 */
    private val lock = Any()

    // ---- 状态机(持 [lock]) ----
    /** 计时起点(ns, [System.nanoTime]):上膛时=帧采集时刻,开过枪后=上一枪时刻。0=空闲 */
    private var lastTriggerNs = 0L
    /**
     * 上一枪成功派发的时刻(ns)。**跨重置保留** —— [noteOffTarget] 的宽限到顶
     * 重置不清它,只有 [shutdown] 清(新会话从零开始)。
     *
     * 为什么需要它:冷却的原语义只是「同一轮在靶里的连发间隔」,重置会让下一枪
     * 退回反应速度阶段。两条真实路径会利用这一点把冷却整个绕过:
     *
     *  1. **后坐力重获靶**。每枪的后坐力把准星顶出判定框、压枪再拉回来,只要
     *     「出框→回框」超过 [OFF_TARGET_GRACE_MS],连发里的每一枪都成了
     *     「重新获靶的第一枪」—— 实际枪距 = 后坐力恢复 + 反应速度,冷却设再长
     *     也压不住射速。
     *  2. **低帧率重置**。推理帧间隔 > 保鲜期+宽限(约 250ms,即 <4fps)时,
     *     帧与帧之间快照过期 → 宽限到顶 → 重置,每帧都是第一枪,枪距 ≈ 帧间隔。
     *     旧帧基实现的状态跨帧保留,这种帧率下反而遵守冷却 —— 时间基重构在这里
     *     引入过回归,本字段就是补丁。
     *
     * 用它把冷却升级成「任意两枪之间的最短间隔」(硬地板):[advance] 的出枪
     * 时刻取「阶段窗口」与「自上一枪起算的冷却」的 max。连续在靶的连发里两个
     * deadline 同一起算点,地板恒不约束 —— 行为与从前逐位相同;只有重置过的枪
     * 才可能被地板压住。代价:全新目标的第一枪也吃上一枪的剩余冷却 —— 这正是
     * 「开火间隔」的字面语义。
     */
    private var lastShotNs = 0L
    private var autoStopDone = false  // 本轮急停是否已执行
    /** 连续离靶的起点(ns);0 = 当前判据为在靶。见 [OFF_TARGET_GRACE_MS]。 */
    private var offTargetSinceNs = 0L
    /**
     * 已决定开枪、还没回填计时。派发那一下在锁外做(见 [runActions]),这个标志
     * 挡住那几微秒里另一条线程重复判定同一枪。
     */
    private var firing = false
    /**
     * 丢枪(tapInFlight 被占)后的下一次允许尝试时刻(ns);0 = 不在退避中。
     * 见 [RETRY_MARGIN_MS]。持 [lock]。
     */
    private var retryAfterNs = 0L
    // ---- 排障仪表(只被推理线程读写,除两个计数器) ----
    private var lastMissLogNs = 0L
    private var traceWhy = "init"
    private var traceNearDist = -1f
    private var traceOffX = 0f
    private var traceOffY = 0f
    private var traceElapsedMs = 0L
    private var traceWindowMs = 0
    private var tracePrevPubNs = 0L
    private var traceFps = 0f
    private var traceShots = 0
    private var traceDrops = 0

    // ---- 最近一帧的快照(持 [lock]) ----
    /** 最近一帧的判据结果 */
    private var snapOnTarget = false
    /** 该帧的**采集**时刻(ns):计时起点用它,把采集+推理延迟从反应速度里摘出去 */
    private var snapFrameNs = 0L
    /** 该判据的**发布**时刻(ns):保鲜期用它,见 [SNAP_MAX_AGE_MS] */
    private var snapPublishNs = 0L
    /** 该帧玩家是否在手动开火 —— 状态机冻结,自动扳机让位 */
    private var snapManualFire = false

    /** FloatService 的压枪状态机每帧读它(推理线程),时钟线程写它 → volatile */
    @Volatile
    var triggerFired = false
        private set

    // ---- 时钟 ----
    private val clock: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread({
                // 与推理线程齐平:这条线程决定出枪时刻,被普通优先级的后台任务
                // 抢掉几十 ms 就等于把刚修掉的量化误差换个来源又加回来。
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                r.run()
            }, "yolovaim-trigger").apply { isDaemon = true }
        }.also {
            // 反复 cancel 周期任务会在队列里留下已取消的节点,不设这个标志就只
            // 涨不落。
            (it as? ScheduledThreadPoolExecutor)?.removeOnCancelPolicy = true
        }

    /** 非 null = 时钟正在跑(仅在「已上膛/宽限期内」期间)。持 [lock] 访问。 */
    private var ticker: ScheduledFuture<*>? = null

    /**
     * 开一枪。
     *
     * @return 是否真的把这一枪交给了注入层。`false` = 被上一枪占住(TouchService
     *   的 tapInFlight)而丢弃 —— 见 [commitShot]:丢弃不推进冷却计时,否则一次
     *   丢弃要静默吃掉一整个冷却周期(表现就是「该响的时候没响」)。
     */
    fun fireTriggerTap(): Boolean {
        val area = fireArea()
        val x: Int
        val y: Int
        if (area != null) {
            x = area.x + (Math.random() * area.width).toInt()
            y = area.y + (Math.random() * area.height).toInt()
        } else {
            val (cx, cy, r) = fallbackTapCircle()
            x = cx + ((Math.random() - 0.5) * 2 * r).toInt()
            y = cy + ((Math.random() - 0.5) * 2 * r).toInt()
        }
        val sent = touchClient()?.triggerTap(x, y, triggerTouchDuration.coerceIn(1, 50)) ?: false
        if (!sent) {
            traceDrops++
            // 丢弃是真实故障信号(冷却 < 触摸时长,或注入层卡住),不能只当噪声。
            Log.w(TAG, "trigger tap dropped (上一枪未完成) tap=($x,$y)")
        } else {
            traceShots++   // 仪表计数器,跨线程不加锁:丢一次自增对排障无影响
            if (area != null) {
                Log.d(TAG, "trigger fire! area=(${area.x},${area.y} ${area.width}x${area.height}) tap=($x,$y)")
            } else {
                Log.d(TAG, "trigger fire (未配置开火区,落在触发圆圈)! tap=($x,$y)")
            }
        }
        return sent
    }

    /**
     * 推理帧回调:算判据 → 写快照 → 立刻推进一次状态机。
     *
     * @param frameCaptureNs 本帧的**采集**时刻([android.media.Image.getTimestamp],
     *   与 [System.nanoTime] 同一时钟)。反应速度从这个时刻起算,所以采集+推理这段
     *   延迟不再叠加到出枪延迟上。传 0 或不可信的值会退回 `nanoTime()`(调用方
     *   负责甄别,见 FloatService)。
     * @param fireZoneState pre-sampled result of [TouchInjectorInterface.isFingerInFireZone].
     *   The caller already needs this value in the same frame (to drive recoil
     *   control), and it describes a physical finger that injected touches are
     *   explicitly excluded from, so re-querying it here would cost a second
     *   blocking IPC round-trip to return the same answer. Pass null to have
     *   this function query it itself.
     * @param aimTarget 本帧自瞄正在 steer 的那个框,自瞄没接管时为 null。只有这个框
     *   会多吃一条「准星在自瞄收敛区内」的判据 —— 见下面「判据二」。
     * @param aimPointX 自瞄的瞄点 X。
     * @param aimPointY 自瞄的瞄点 Y,**必须是 [AimController.effectiveAimY] 的结果**
     *   (含框内偏移 / Y偏移 / 压枪),否则这条判据认的不是自瞄真正停下的那个点。
     * @param aimToleranceX / @param aimToleranceY 自瞄本帧的收敛容差(px),**必须是
     *   [AimController.convergeTolerance] 的返回值**,与自瞄自己用的那两个数逐位相同。
     *   传绝对的 convergeThresh 会让扳机比自瞄更宽松 —— 那正是「自瞄不准、扳机跟着
     *   一起不准」的来源。
     */
    fun processTrigger(
        lastDetections: List<DetectionInfo>,
        centerX: Float,
        centerY: Float,
        hasDetects: Boolean,
        fireZoneState: Boolean? = null,
        aimTarget: DetectionInfo? = null,
        aimPointX: Float = 0f,
        aimPointY: Float = 0f,
        aimToleranceX: Float = 0f,
        aimToleranceY: Float = 0f,
        frameCaptureNs: Long = 0L
    ) {
        val nowNs = System.nanoTime()
        val frameNs = if (frameCaptureNs > 0L) frameCaptureNs else nowNs
        // 几何仪表每帧先清空。**不清就会留上一帧的值** —— 早退路径不走几何,
        // 状态行会把几秒前的距离一直重复打出来,看着像「准星贴在框边上不开枪」,
        // 其实是这几秒里一个框都没有。第一版仪表就栽在这里。
        traceNearDist = -1f; traceOffX = 0f; traceOffY = 0f

        // 整帧没有检测结果 / 扳机被关 / 注入断开,都算离靶,同样走宽限期。
        // 旧实现在这里直接 return,lastTriggerNs 于是一直留着 —— 目标消失几秒后
        // 重新出现时 elapsed 早已远超反应速度,第一枪立刻打出去,反应速度形同没设。
        // 三个条件分开记原因:排障时「没检测到」和「检测到了但准星没进框」是完全
        // 不同的两种病,合成一条 return 就分不出来。
        if (!triggerEnabled || !hasDetects || touchClient()?.isConnected() != true) {
            traceWhy = when {
                !triggerEnabled -> "disabled"
                !hasDetects -> "nodet"      // 本帧一个检测框都没有
                else -> "disc"              // 注入通道断了
            }
            publishAndAdvance(onTarget = false, frameNs = frameNs, nowNs = nowNs, manualFire = false)
            return
        }

        // 手动开火期间**不动**扳机状态机:这不是「离靶」,是玩家自己在打,
        // 自动扳机只是让位。把它也算成离靶会改变松手瞬间的手感,与判据错位无关。
        val fingerOnFire = fireZoneState ?: (touchClient()?.isFingerInFireZone() ?: false)
        if (fingerOnFire) {
            traceWhy = "manual"
            publishAndAdvance(onTarget = false, frameNs = frameNs, nowNs = nowNs, manualFire = true)
            return
        }

        val triggerDets = if (triggerClasses.isEmpty()) lastDetections
            else lastDetections.filter { it.classId in triggerClasses }

        if (triggerDets.isEmpty()) {
            traceWhy = "nocls"     // 有框,但没有一个属于扳机类别
            publishAndAdvance(onTarget = false, frameNs = frameNs, nowNs = nowNs, manualFire = false)
            return
        }

        // 准星坐标保持 Float。原先 toInt() 截断到整像素,判定边界上因此多出半个
        // 像素的偏差 —— 单独看无所谓,但下面第二条判据的容差可以小到 0,那半像素
        // 就不再是可以忽略的量。
        val cx = centerX
        val cy = centerY
        val radius = triggerRadiusPx.coerceAtLeast(0f)
        var onTarget = false
        // 排障用:本帧最近的那个框离准星多远、偏在哪边。
        var nearDist = Float.MAX_VALUE
        var nearOffX = 0f
        var nearOffY = 0f
        for (det in triggerDets) {
            val r = det.rect
            val classOff = classTriggerOffsets[det.classId] ?: triggerOffsetYRatio
            val extendY = r.height() * (-classOff)
            val bottom = r.bottom + extendY

            // 准星到这个框的距离(框内 = 0)。这就是「准星与方框之间那条线的长度」:
            // 逐轴取超出框的那一段,再取斜边。
            val dx = when {
                cx < r.left -> r.left - cx
                cx > r.right -> cx - r.right
                else -> 0f
            }
            val dy = when {
                cy < r.top -> r.top - cy
                cy > bottom -> cy - bottom
                else -> 0f
            }
            val dist = hypot(dx, dy)
            if (dist < nearDist) {
                nearDist = dist
                nearOffX = r.centerX() - cx
                nearOffY = (r.top + bottom) * 0.5f - cy
            }

            // 判据一:准星落在检测框内(下边可由扳机 Y偏移 向下延)。原判据的**等价
            // 写法** —— `dist == 0` 与 `cx in [left,right] && cy in [top,bottom]`
            // 是同一个闭区域,只是复用了上面这条线的长度。自瞄没接管时
            // (aimTarget == null 且触发半径为 0)只剩这一条,单独用扳机的行为如旧。
            if (dist <= 0f) {
                onTarget = true
                break
            }

            // 判据三:准星到框的距离在触发半径内 —— 用户显式设的一圈宽容量。
            //
            // 为什么需要它:判据一是**包含**判定,能不能成立取决于框有多大。远距离
            // 人形框高只有 15~20px,准星差几个像素就在框外;而检测框本身逐帧抖动,
            // 「看着已经瞄上了却不开枪」多半就是这几个像素。更隐蔽的一种:准星被
            // 假定在采集画面正中(FloatService 的 centerX/centerY),游戏准星若不在
            // 正中(挖孔屏偏移、采集区与显示区不一致),包含判定会**系统性**落空,
            // 而半径是唯一能补偿它的旋钮。
            //
            // 与判据二的区别:判据二只对自瞄正在 steer 的那一个框成立,容差是自瞄
            // 自己的收敛容差(按框缩放,用户改不了);这一条对所有触发类别的框成立,
            // 半径由用户直接给,与框大小无关。
            if (radius > 0 && dist <= radius) {
                onTarget = true
                break
            }

            // 判据二:准星落在自瞄的收敛区内 —— 「自瞄收敛」必须蕴含「扳机在靶」。
            //
            // 只加这一条的原因:两套判据原先是各自独立的几何。自瞄收敛于瞄点
            // ±convergeThresh(逐轴、绝对像素),扳机判准星是否落在框内;两者只在
            // 瞄点深深位于框内、且框远大于阈值时才重合。实测有四条错位:
            //   · 框内偏移 = 1(最上) / 0(最下) 时瞄点正好落在框的边线上,加上 ±阈值
            //     的死区,准星合法地停在框外 —— 自瞄「锁定」了,扳机认为离靶
            //   · 自瞄 Y偏移 向上(-1.5~1.5),扳机 Y偏移 只能向下延(-2~0),
            //     `cy >= top` 这条上边界没有任何设置项能放宽 → 打头时永远不触发
            //   · 阈值是绝对像素(默认 10、可调到 100),而远距离框高只有 15~20px,
            //     X 轴同理会横向出框
            //   · 压枪偏移只加在瞄点上(经 aimPointY 带进来),能累到上百 px,持续
            //     开火时准星被压到框外 → 连发中途扳机自己停火
            // 写成「或」而不是把框撑大到包住收敛区:后者的包围盒会连带把瞄点与框
            // 之间那条空白竖带也算成在靶,而这里要的只是「自瞄停下的那个点」。
            // 容差就是自瞄本帧用的那两个数(逐轴、按框缩放),所以收敛(|e| < tol)必然
            // 满足这一条(<=),且自瞄收紧多少扳机就跟着收紧多少 —— 不会出现「扳机比
            // 自瞄还宽」。
            if (det === aimTarget) {
                val tolX = aimToleranceX.coerceAtLeast(0f)
                val tolY = aimToleranceY.coerceAtLeast(0f)
                if (abs(cx - aimPointX) <= tolX && abs(cy - aimPointY) <= tolY) {
                    onTarget = true
                    break
                }
            }
        }

        traceNearDist = if (nearDist == Float.MAX_VALUE) -1f else nearDist
        traceOffX = nearOffX
        traceOffY = nearOffY
        traceWhy = if (onTarget) "on" else "geom"   // geom = 有框,准星没进(半径外)
        publishAndAdvance(onTarget, frameNs, nowNs, manualFire = false)
    }

    /** 时钟线程的一步。与 [processTrigger] 走同一个 [advance],只是不带新判据。 */
    private fun tick() {
        try {
            runActions(synchronized(lock) { advance(System.nanoTime()) })
        } catch (e: Exception) {
            // scheduleAtFixedRate 的任务抛一次就不再被调度 —— 那等于扳机静默失效。
            Log.e(TAG, "trigger clock tick: ${e.message}")
        }
    }

    private fun publishAndAdvance(onTarget: Boolean, frameNs: Long, nowNs: Long, manualFire: Boolean) {
        runActions(synchronized(lock) {
            snapOnTarget = onTarget
            snapFrameNs = frameNs
            snapPublishNs = nowNs
            snapManualFire = manualFire
            advance(nowNs)
        })
        traceState(onTarget, manualFire, nowNs)
    }

    /**
     * 每 [TRACE_STATE_MS] 一条整体状态。三种故障在这一条上直接分开:
     *
     *  - `on=0 near=NNpx` 连着好几秒 → 判据没成立,准星根本没进框(近处看 off:
     *    逐渐变小 = 自瞄还在收敛;长期不变 = 准星中心或坐标映射有系统偏差)。
     *    这种情况**调触发半径**,而不是调反应速度。
     *  - `manual=1` 连着好几秒 → 有物理手指压在开火区里,扳机按设计让位、状态机
     *    冻结。开火区框到了手指常驻的位置就会这样。
     *  - `on=1 fired=1 elapsed<win` → 在靶,纯粹被冷却顶着,那就是冷却时间的值。
     *
     * `fps` 是判据的刷新率(推理帧率);`shots/drop` 是累计派发与被丢弃的枪数。
     */
    private fun traceState(onTarget: Boolean, manualFire: Boolean, nowNs: Long) {
        if (tracePrevPubNs != 0L) {
            val dtMs = (nowNs - tracePrevPubNs) / 1e6f
            if (dtMs > 0.5f) {
                val f = 1000f / dtMs
                traceFps = if (traceFps == 0f) f else 0.8f * traceFps + 0.2f * f
            }
        }
        tracePrevPubNs = nowNs
        if (nowNs - lastMissLogNs < TRACE_STATE_MS * MS_TO_NS) return
        lastMissLogNs = nowNs
        Log.d(TAG, String.format(
            java.util.Locale.US,
            "trig on=%d why=%s manual=%d near=%s off=(%d,%d) radius=%.1f elapsed=%d/%dms fired=%d fps=%.1f shots=%d drop=%d",
            if (onTarget) 1 else 0, traceWhy, if (manualFire) 1 else 0,
            if (traceNearDist < 0f) "-" else "${traceNearDist.toInt()}px",
            traceOffX.toInt(), traceOffY.toInt(), triggerRadiusPx,
            traceElapsedMs, traceWindowMs, if (triggerFired) 1 else 0,
            traceFps, traceShots, traceDrops
        ))
    }

    /**
     * 在锁外执行 [advance] 决定的动作。**顺序是语义的一部分**:急停必须落在这一枪
     * 之前 —— 它的全部意义就是「先松摇杆,给游戏时间注册停止,再开枪」。两者同一步
     * 到期(推理延迟已经超过反应速度时会这样)也得保持这个先后。
     */
    private fun runActions(act: Int) {
        if (act and ACT_LIFT != 0) doAutoStop()
        if (act and ACT_FIRE != 0) {
            var sent = false
            // finally:派发路径抛异常时也必须把 firing 放掉,否则扳机从此永久静默。
            try { sent = fireTriggerTap() } finally { synchronized(lock) { commitShot(sent) } }
        }
    }

    /** 回填一枪的结果。**必须持 [lock] 调用。** */
    private fun commitShot(sent: Boolean) {
        firing = false
        // 丢枪(tapInFlight)时计时不动:白吃一个冷却周期会表现成「该响的时候
        // 没响」。重试也不立刻做 —— 每个时钟 tick 都去撞还没走完的 tap 是纯
        // 空转,退避到上一枪大概率已结束的时刻,见 RETRY_MARGIN_MS。
        if (!sent) {
            retryAfterNs = System.nanoTime() +
                (triggerTouchDuration.coerceIn(1, 50) + RETRY_MARGIN_MS) * MS_TO_NS
            return
        }
        retryAfterNs = 0L
        triggerFired = true
        lastTriggerNs = System.nanoTime()
        lastShotNs = lastTriggerNs   // 冷却硬地板的起算点,跨重置保留
        autoStopDone = false
        // 压枪预算:每枪一发,见 onShotFired 的说明。null 安全调用,没挂就是
        // 旧语义(电平锁存)。
        onShotFired?.invoke()
    }

    /**
     * 状态机推进一步。**必须持 [lock] 调用**,返回该在锁外执行的动作位图
     * ([ACT_LIFT] / [ACT_FIRE]) —— 急停是一次阻塞 IPC,持锁做能让时钟线程把
     * 推理线程堵在锁上;开枪要回填「是否真的派发出去」。
     */
    private fun advance(nowNs: Long): Int {
        // 手动开火:冻结。时钟也停掉 —— 状态不动,它没事可做。
        if (snapManualFire) {
            stopTicker()
            return ACT_NONE
        }

        // 「在靶」= 最近一帧说在靶,且这条判据还没过保鲜期。
        if (!snapOnTarget || nowNs - snapPublishNs > SNAP_MAX_AGE_MS * MS_TO_NS) {
            noteOffTarget(nowNs)
            return ACT_NONE
        }

        offTargetSinceNs = 0L
        // 第一发:从**准星进入目标的那一帧的采集时刻**起算,反应速度后开枪。
        if (lastTriggerNs == 0L) { lastTriggerNs = snapFrameNs; autoStopDone = false }
        val windowMs = if (!triggerFired) triggerReactionSpeed.coerceIn(10, 500)
                       else triggerCooldown.coerceIn(10, 2000)   // 第二发起用冷却
        // 冷却硬地板:出枪时刻还必须 ≥ 上一枪 + 冷却,与阶段窗口取 max。急停的
        // 60ms 提前量按 max 之后的 due 算 —— 地板压枪时若还按阶段窗口算,摇杆
        // 会提前好几秒松开。连续在靶的连发里地板与窗口同一起算点,恒不约束,
        // 行为与从前逐位相同;见 [lastShotNs]。
        val floorMs = if (lastShotNs == 0L) 0 else triggerCooldown.coerceIn(10, 2000)
        val dueNs = maxOf(lastTriggerNs + windowMs * MS_TO_NS,
                          lastShotNs + floorMs * MS_TO_NS)
        val effWindowMs = ((dueNs - lastTriggerNs) / MS_TO_NS).toInt()
        val elapsedMs = (nowNs - lastTriggerNs) / MS_TO_NS
        traceElapsedMs = elapsedMs
        traceWindowMs = effWindowMs
        var act = ACT_NONE
        if (autoStopDue(elapsedMs, effWindowMs)) act = act or ACT_LIFT
        // 退避期内的丢枪重试不触发 —— 出枪条件里这道门与 firing 并列,挡的是
        // 「每 2ms 撞一次还没走完的 tap」。计时照常推进,退避结束后条件仍满足
        // 就出枪,不改变冷却语义。
        if (!firing && nowNs >= retryAfterNs && nowNs >= dueNs) {
            firing = true
            act = act or ACT_FIRE
            // lag = 实际出枪时刻 - 应出枪时刻。改成时间基之前这个数是 0~一个推理
            // 帧周期(33-66ms)且每枪不同,那正是「开枪慢且慢得不固定」的量化来源;
            // 现在应当稳定在 0~3ms(时钟步长 + 调度)。排障时先看这一条。due 是
            // max 之后的窗口,被地板压过的枪 lag 也只含调度抖动;floor=+Xms 标出
            // 地板比阶段窗口多压的那段,它不算 lag。
            Log.d(TAG, "shot due=${effWindowMs}ms actual=${elapsedMs}ms lag=${elapsedMs - effWindowMs}ms" +
                       " first=${!triggerFired}" +
                       (if (effWindowMs > windowMs) " floor=+${effWindowMs - windowMs}ms" else ""))
        }
        // 只要还在这条路上,时钟就得继续跑 —— 下一枪的时刻由它决定,不等下一帧。
        startTicker()
        return act
    }

    /**
     * 离靶一步。连续离靶不足 [OFF_TARGET_GRACE_MS] 只保留计时,超过才真正重置。
     * **必须持 [lock] 调用。**
     *
     * 旧实现在离靶时无条件归零计时,于是反应速度的倒计时从头再来。准星停在判定区
     * 边线上时,检测框的逐帧抖动会让判据反复翻转,倒计时永远攒不满 → 一枪都不出。
     * 现象是「扳机完全失效」而不是「慢半拍」。
     *
     * 宽限期内只保留计时,不改变开枪条件(开枪始终要求快照为在靶且未过保鲜期),
     * 所以这里放宽不会误射。
     */
    private fun noteOffTarget(nowNs: Long) {
        if (lastTriggerNs == 0L && !triggerFired) { stopTicker(); return }
        if (offTargetSinceNs == 0L) offTargetSinceNs = nowNs
        if (nowNs - offTargetSinceNs < OFF_TARGET_GRACE_MS * MS_TO_NS) {
            // 宽限期内:计时留着,时钟也留着 —— 采集彻底停了也要有人来把它归零。
            startTicker()
            return
        }
        triggerFired = false
        lastTriggerNs = 0L
        autoStopDone = false
        offTargetSinceNs = 0L
        retryAfterNs = 0L
        stopTicker()
    }

    /**
     * 急停判定:开枪前 60ms 松开摇杆,给游戏时间注册停止;窗口本身不足 60ms 就
     * 立刻松开。**必须持 [lock] 调用**,只做判定并占掉 [autoStopDone],真正的
     * IPC 由调用方在锁外做([doAutoStop])。
     */
    private fun autoStopDue(elapsedMs: Long, windowMs: Int): Boolean {
        if (!autoStopEnabled || autoStopDone) return false
        if (windowMs >= 60 && elapsedMs < windowMs - 60) return false
        autoStopDone = true
        return true
    }

    private fun doAutoStop() {
        val lifted = touchClient()?.liftJoystickFinger() ?: false
        Log.d(TAG, "autoStop: liftJoystickFinger=$lifted")
    }

    private fun startTicker() {
        if (ticker != null) return
        ticker = try {
            // FixedDelay 而不是 FixedRate:每一 tick 都是从墙钟重新算的,补跑漏掉的
            // 那几次毫无意义,而 FixedRate 在线程被抢占后会连着补一串。
            clock.scheduleWithFixedDelay({ tick() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {   // 已 shutdown
            Log.e(TAG, "trigger clock start: ${e.message}")
            null
        }
    }

    private fun stopTicker() {
        ticker?.cancel(false)
        ticker = null
    }

    /** 服务销毁时调用。时钟线程是 daemon,但留着它跑没有意义。 */
    fun shutdown() {
        synchronized(lock) {
            stopTicker()
            lastTriggerNs = 0L
            lastShotNs = 0L   // 冷却地板只约束同一次服务会话内的枪
            offTargetSinceNs = 0L
            snapOnTarget = false
            triggerFired = false
            firing = false
            retryAfterNs = 0L
        }
        clock.shutdownNow()
    }
}
