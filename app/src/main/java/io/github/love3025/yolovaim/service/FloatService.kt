package io.github.love3025.yolovaim.service
import io.github.love3025.yolovaim.model.DetectionInfo

import android.app.*
import android.content.*
import android.content.res.Configuration
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.*
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin
import kotlin.math.min
import io.github.love3025.yolovaim.controller.AimController
import io.github.love3025.yolovaim.controller.TriggerController
import io.github.love3025.yolovaim.manager.InferenceManager
import io.github.love3025.yolovaim.manager.OverlayManager
import io.github.love3025.yolovaim.manager.ConfigManager
import io.github.love3025.yolovaim.view.FloatBallView
import io.github.love3025.yolovaim.view.OverlayCanvasView
import io.github.love3025.yolovaim.view.GuiPanelView
import io.github.love3025.yolovaim.view.TriggerOverlayView
import io.github.love3025.yolovaim.view.TouchDisplayView
import io.github.love3025.yolovaim.view.AreaSettingsView
import io.github.love3025.yolovaim.model.AimingState
import io.github.love3025.yolovaim.model.AreaConfig
import io.github.love3025.yolovaim.model.BezierMover
import io.github.love3025.yolovaim.injector.InjectorCallback
import io.github.love3025.yolovaim.injector.HudClient
import io.github.love3025.yolovaim.inference.JniCallBack
import io.github.love3025.yolovaim.util.ProjectionHolder
import io.github.love3025.yolovaim.util.allowDisplayCutout

class FloatService : Service() {

    companion object {
        const val TAG = "FloatService"; const val CH_ID = "aimbot_ch"

        /**
         * Per-frame trace logging, compiled out by default in every build type.
         *
         * The sites guarded by this run once per inference frame (100-300/s on
         * QNN HTP). Each one costs a String.format plus an __android_log_write
         * socket round-trip on the same core the inference loop runs on, and
         * neither is stripped in release (isMinifyEnabled = false, and the
         * default proguard config does not drop android.util.Log). At that rate
         * the output is unreadable anyway. Flip to true locally when you
         * actually want a frame-by-frame trace.
         */
        const val TRACE = false

        /**
         * 链路延迟仪表(每 [LAT_WINDOW] 帧一条聚合日志)。与 [TRACE] 是两个
         * 独立开关，因为代价差一个数量级：TRACE 每帧一次 String.format +
         * logd socket 写；这里每帧只多 ~8 次 nanoTime(aarch64 上 20-30ns 一
         * 次，合计 <0.3µs)，一整个窗口才出一条日志。
         *
         * **聚合里 max 比 avg 重要。** 要抓的是尾部事件 —— execCmd 的
         * CMD_TIMEOUT_MS=500 判死、URGENT_DISPLAY 推理线程等默认优先级
         * readerExec 的优先级反转、HUD 掩码大块写把 cmdLock 占住 —— 这些在
         * 均值里全被 60 帧摊平看不见。所以每一段都同时记 avg 和 max。
         *
         * 旧的 T0-T6 那套在 InferenceManager 里，而那份推理循环从来没被
         * 调用过(startInferLoop 只被自己内部调)，所以真跑的循环一直没有
         * 仪表。重建时按现在真正想回答的问题重新分段：多出 fire/hold 两段
         * 阻塞 IPC 和 hud 一段，那三处是当前的可疑点。
         */
        const val TRACE_LAT = false
        const val LAT_TAG = "YolovaimLatency"
        /** 聚合窗口(帧)。60 帧 ≈ 0.5-2s，一条日志/窗口，logcat 读得过来。 */
        const val LAT_WINDOW = 60

        // 分段下标。idle 是「上一帧干完 → 这一帧拿到图」的真实空闲，
        // 不是单次 acquire 耗时：循环是事件驱动的(awaitFrame park)，
        // 空闲大 = 被采集帧率喂不饱，空闲趋零 = 推理已经吃满采集。
        const val LAT_IDLE = 0
        const val LAT_FIRE = 1   // consumeFireState 阻塞往返
        const val LAT_HOLD = 2   // isFingerInTriggerZone 阻塞往返(仅按住激发开启)
        const val LAT_JNI  = 3   // detect(): 预处理+推理+后处理
        const val LAT_HUD  = 4   // pushInferInfo + publishDetections
        const val LAT_AIM  = 5   // 选靶 + PID/Bezier + MOVE 管道写
        const val LAT_TRIG = 6   // processTrigger(triggerTap 已异步，应接近 0)
        const val LAT_TOTAL = 7  // 拿到图 → 本帧活干完
        // 上面各段之和曾比 total 少 1.64ms/帧(7.7%)。差额落在这两处没插桩的
        // 必付开销上，加桩把它算清楚，别再让它以「未计入」的名义待在账里。
        const val LAT_ACQ = 8    // image.planes[0] + plane.buffer + 裁剪区换算
        const val LAT_TD  = 9    // hwBuf.close() + image.close()(缓冲还池)
        const val LAT_N = 10
    }

    private lateinit var wm: WindowManager
    private lateinit var ballView: FloatBallView
    private lateinit var overlayView: OverlayCanvasView
    private lateinit var guiPanel: GuiPanelView

    private var ballParams: WindowManager.LayoutParams? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var guiParams: WindowManager.LayoutParams? = null
    private var guiVisible = false; private var ballAdded = false
    private var overlayAdded = false; private var guiAdded = false

    var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null

    // Frame-arrival signalling for the inference loop.
    //
    // The loop used to poll acquireLatestImage() and Thread.yield() on a
    // THREAD_PRIORITY_URGENT_DISPLAY thread. Inference takes ~3ms while the
    // compositor only produces a frame every 8-16ms, so ~80% of the wall clock
    // was spent burning a big core in the yield loop. That is not just wasted
    // CPU: it heats the SoC, and the resulting thermal throttling drags the NPU
    // down with it (the "smooth for the first few minutes, then drops frames"
    // symptom).
    //
    // ImageReader's own listener carries exactly the semantics the loop wants
    // ("a new frame exists"), so the loop now blocks until it fires. Frame
    // throughput is unchanged: acquireLatestImage() could never return more
    // frames than the VirtualDisplay produced.
    private var readerThread: HandlerThread? = null
    private var readerHandler: Handler? = null
    // ReentrantLock rather than an intrinsic monitor: Kotlin's Any does not
    // expose wait/notify, so a monitor would need a java.lang.Object cast.
    private val frameLock = java.util.concurrent.locks.ReentrantLock()
    private val frameCond: java.util.concurrent.locks.Condition = frameLock.newCondition()
    private var frameReady = false
    private var captureVirtualDisplay: android.hardware.display.VirtualDisplay? = null
    var mediaRecorder: android.media.MediaRecorder? = null
    private var recordSurface: Surface? = null
    var recordEnabled = false
    var autoSaveDataset = false
    private var datasetCounter = -1  // -1 = 未初始化，首次保存时扫描目录
    private val datasetDir: java.io.File by lazy {
        java.io.File(getExternalFilesDir(null), "dataset").apply { mkdirs() }
    }
    private var captureW = 0; private var captureH = 0  // natural display size for ImageReader + coords
    // 使用 Display.getRealSize() 获取完整屏幕尺寸（包括挖孔区域），
    // 避免 displayMetrics 可能受安全区影响
    private val screenSize: Point get() {
        val p = Point()
        wm.defaultDisplay.getRealSize(p)
        return p
    }
    private val screenWidth get() = screenSize.x
    private val screenHeight get() = screenSize.y
    /**
     * 当前显示旋转(0/1/2/3)。注入层和 zone 判定都要它:横屏有 90° 和 270°
     * 两种,只按 w>h 推会把两者算成同一张坐标表,落点整体点镜像。
     */
    fun currentDisplayRotation(): Int = displayRotation

    private val displayRotation: Int get() = try {
        @Suppress("DEPRECATION") wm.defaultDisplay.rotation
    } catch (_: Exception) { if (screenWidth > screenHeight) 1 else 0 }
    private val screenDensity get() = resources.displayMetrics.densityDpi

    private val executor = Executors.newSingleThreadExecutor()
    private val inferRunning = AtomicBoolean(false)
    val aimbotOn = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val detectionBuffer = Array(20) { DetectionInfo(RectF(), -1, "") }
    private var lastDetections: List<DetectionInfo> = emptyList()
    private var centerX = 0f; private var centerY = 0f
    private var cachedRange = 0f; private var cachedRangePx = 0

    val touchService = TouchService(this)
    private var currentSpeed = 0.3f; private var currentConfidence = 0.50f
    private var modelRunning = false
    private var lastModelIndex = 0
    var currentClasses: Map<Int, String> = emptyMap()

    // Class filtering for aimbot
    private var aimClasses: MutableSet<Int> = mutableSetOf()  // empty = all
    private var priorityClass: Int = -1
    private var classAimOffsets: Map<Int, Float> = emptyMap()  // per-class Y offset
    private var boxAimRatio = 0.5f  // 0=top, 0.5=center, 1=bottom
    private var classBoxAimRatios: Map<Int, Float> = emptyMap()  // per-class box aim ratio
    private var classTriggerOffsets: Map<Int, Float> = emptyMap()  // per-class trigger Y offset
    private var triggerClasses: MutableSet<Int> = mutableSetOf()  // empty = all

    // PID auto-aim state
    private var aimOffsetYRatio = 0f; private var aimSwayAmplitude = 0; private var aimPrediction = 0; private var triggerOffsetYRatio = 0f
    private var kp = 0.07f; private var ki = 0.001f; private var kf = 0.05f
    /** 平滑度(唯一的阻尼旋钮)。旧配置里的 kd 在 ConfigManager.load 折算进来。 */
    private var aimDamping = 0.4f
    /** 接近段增强,默认关。见 AimController.approachAssistEnabled。 */
    private var approachAssist = false
    private var aimHoldEnabled = false
    private var recoilEnabled = false; private var recoilStrength = 0.5f
    private var recoilSpeed = 0.5f; private var recoilResetIntervalMs = 300
    private val aimingState = AimingState()

    // Bezier aim state
    private var aimMode = 0 // 0=PID, 1=Bezier
    private var bezierDuration = 30; private var bezierControlOffset = 0.3f; private var bezierRandomSpread = 0.1f
    private val bezierMover = BezierMover()
    private var convergeThresh = 10f
    private var aimFov = 50
    private var showFov = false
    private var dynamicFov = false
    private var fovZoomDelay = 0
    private var showInferInfo = false
    private var showDetectionBox = false

    // ====================== 防捕获 native HUD ======================
    // true = root daemon 的防捕获 layer 已激活,五项纯显示元素走 IPC;
    // false = OverlayCanvasView + OverlayManager TextView 兜底(无 root /
    // HUD_ON 失败 / 自检失败,改进方案.md §6)。
    @Volatile private var hudNative = false
    // 首次启用自检:HUD_ON 成功不代表 AUTO_MIRROR 采集路径也排除该 layer
    // (demo 只验证过截屏),要在真实采集帧上找检查图案像素验证。
    // 判据是洋红色(daemon 端 HUD_CHECK_ON 画的图案)而不是白色 —— 白色
    // 会和游戏自身的屏幕中心准星撞车,首版在真机上就是这么误判回退的。
    @Volatile private var hudSelfCheckPending = false
    private var hudCheckStage = 0    // 0=idle 1=图案已下发,采样中
    private var hudCheckFrames = 0
    private var hudCheckMaxHits = 0
    // 自检采样尝试次数上限:wrapHardwareBuffer/copy 失败时旧实现是裸 return,
    // 既不计数也不清 pending —— 一旦该路径持续失败,每个采集帧都会做一次
    // 全屏 HardwareBuffer→Bitmap 拷贝且永不退出。
    private var hudCheckAttempts = 0
    private val hudCheckMaxAttempts = 60
    // 推理热路径上的值变门闩:值没变不发 IPC
    private var lastHudFov = -1
    private var hudBoxesSent = false
    // 推理信息文字:同文本跳过(与兜底 setInferInfo 的门闩一致,不做时间节流)
    @Volatile private var lastHudInfoText: String? = null
    private val hudInfoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFEB3B.toInt() // OverlayManager TextView 同款浅黄
        typeface = Typeface.DEFAULT_BOLD
    }
    // 掩码渲染的复用缓冲(只在推理线程访问)。旧实现每帧新建 Bitmap +
    // IntArray(w*h) ≈ 200KB,60fps 就是 12MB/s 的主线程分配,GC 反复
    // 打断 UI —— 这就是"连 GUI 菜单都卡"的那一份。
    private var hudMaskBmp: Bitmap? = null
    private var hudMaskCanvas: Canvas? = null
    private var hudMaskRaw: ByteArray? = null          // 位图原生字节(RGBA)
    private var hudMaskRawBuf: java.nio.ByteBuffer? = null
    private var hudMask: ByteArray? = null             // 提取出的 8bit 覆盖率
    private val hudInfoFg = 0xFFFFEB3B.toInt()         // 浅黄字
    private val hudInfoBg = 0xCC101010.toInt()         // 半透明黑底药丸

    // EMA-smoothed fps derived from each frame's full inference budget
    // (preproc + invoke + postproc). EMA keeps the displayed number from
    // bouncing on single-frame stalls while still tracking real changes
    // within ~5 frames.
    private var inferFps = 0f

    // Hold-to-fire (按住激发) state — uses trigger slot, separate from aim slot

    // Touch display overlay
    private var touchDisplayEnabled = false
    private var touchDisplayView: TouchDisplayView? = null
    private var touchDisplayAdded = false

    // Area settings overlay
    private var areaSettingsView: AreaSettingsView? = null
    private var areaSettingsAdded = false
    private val savedAreas = mutableListOf<AreaConfig>()

    // 用户是否真的在「区域设置」里配过区域。savedAreas 永远被补满 4 个占位以喂给
    // AreaSettingsView，所以「有没有元素」并不能代表「配没配」—— 占位是
    // AreaConfig() 的默认值(150x150 @ 0,0)，当成真实开火区用就会把每一枪都点到
    // 屏幕左上角。区分这两者是本标志存在的唯一理由。
    private var areasConfigured = false

    // Area index constants — magic number prevention
    private val AREA_INDEX_FIRE = 0
    private val AREA_INDEX_TRIGGER = 1
    private val AREA_INDEX_AIM = 2
    private val AREA_INDEX_JOYSTICK = 3

    // Device resolution for uinput — auto-detected by detect_touch_device() in native code.
    // Hardcoded defaults are NOT used; pass placeholder 0 values.
    var deviceAbsMaxX = 0
    var deviceAbsMaxY = 0

    private var triggerEnabled = false; private var triggerReactionSpeed = 100; private var triggerCooldown = 200
    private var triggerUpFluct = 3; private var triggerDownFluct = 3
    private var triggerTouchDuration = 10; private var triggerTouchRange = 100
    private var triggerRadiusPx = 0
    private var triggerShowArea = false
    private var autoStopEnabled = false
    private var triggerOverlay: TriggerOverlayView? = null
    private var triggerOverlayAdded = false
    private var triggerAreaX = 0; private var triggerAreaY = 0; private var lastTriggerMs = 0L
    private var triggerFired = false  // 扳机是否已射过第一发（第二发起用冷却时间）
    private val hasDetects = AtomicBoolean(false)

    // Controllers and Managers
    private lateinit var aimController: AimController
    private lateinit var triggerController: TriggerController
    private lateinit var inferenceManager: InferenceManager
    private lateinit var overlayManager: OverlayManager

    /**
     * 旋转 180°(横屏 ↔ 反向横屏 / 竖屏 ↔ 倒置竖屏)时屏幕尺寸与 Configuration
     * 都不变,[onConfigurationChanged] 不会回调,注入层就一直按旧 rotation 算 ——
     * 这正是「充电口换一边就全失效」里 onConfigurationChanged 兜不住的那一半。
     * DisplayListener 的 onDisplayChanged 对纯旋转也会回调,补上这个缺口。
     */
    private var lastKnownRotation = -1
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != android.view.Display.DEFAULT_DISPLAY) return
            val rot = displayRotation
            if (rot == lastKnownRotation) return
            lastKnownRotation = rot
            Log.d(TAG, "display rotation -> $rot (${screenWidth}x${screenHeight})")
            touchService.setDisplayRotation(rot)
            // 区域是屏幕坐标,旋转后要按新坐标系重发,否则 zone 判定用的还是旧框
            updateTriggerZone(); updateFireZone(); updateJoystickZone()
        }
    }

    override fun onCreate() {
        super.onCreate(); wm = getSystemService(WINDOW_SERVICE) as WindowManager
        lastKnownRotation = displayRotation
        (getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
            .registerDisplayListener(displayListener, mainHandler)
        ConfigManager.init(this)
        loadConfigToService()
        createNotificationChannel(); startForeground(1, buildNotification())
        initControllers()
        touchService.onStateChanged = { state ->
            val text = when (state) {
                TouchService.ConnectionState.DISCONNECTED -> "Disconnected"
                TouchService.ConnectionState.CONNECTING -> "Connecting"
                TouchService.ConnectionState.CONNECTED -> "Running"
                TouchService.ConnectionState.ERROR -> "Error"
            }
            ProjectionHolder.touchStatusText = text
            Log.d(TAG, "TouchService state: $text")
        }
    }

    private fun initControllers() {
        aimController = AimController(
            service = this,
            touchClient = { touchService },
            savedAreas = { savedAreas }
        )

        triggerController = TriggerController(
            touchClient = { touchService },
            fireArea = { if (areasConfigured) savedAreas.getOrNull(AREA_INDEX_FIRE) else null },
            fallbackTapCircle = {
                val size = dp(triggerTouchRange.coerceAtLeast(30))
                // triggerAreaX/Y 由本类持有的触发圆圈维护(setupTriggerOverlay +
                // onPositionChanged)，用户拖动后也是最新值。
                Triple(triggerAreaX + size / 2, triggerAreaY + size / 2, size / 2)
            }
        )

        inferenceManager = InferenceManager(
            service = this,
            aimController = aimController,
            triggerController = triggerController,
            overlayCanvasView = { if (overlayAdded) overlayView else null }
        )

        overlayManager = OverlayManager(
            context = this,
            wm = wm,
            screenWidth = { screenWidth },
            screenHeight = { screenHeight },
            dp = { dp(it) }
        )

        // Wire inference-info overlay through OverlayManager.setInferInfo so
        // the user gets a real TextView instead of Canvas.drawText inside the
        // full-screen overlay (which can be clipped by status-bar insets).
        // InferenceManager marshals the callback to the main thread for us.
        // (HUD 激活时同一条入口改走 pushInferInfo → daemon 位图。)
        inferenceManager.onInferInfoUpdate = { text -> pushInferInfo(text) }

        // Load config to controllers
        loadConfigToControllers()
    }

    private fun loadConfigToControllers() {
        // AimController
        aimController.kp = kp
        aimController.ki = ki
        aimController.aimDamping = aimDamping
        aimController.approachAssistEnabled = approachAssist
        aimController.kf = kf
        aimController.aimMode = aimMode
        aimController.bezierDuration = bezierDuration
        aimController.bezierControlOffset = bezierControlOffset
        aimController.bezierRandomSpread = bezierRandomSpread
        aimController.convergeThresh = convergeThresh
        aimController.aimFov = aimFov
        aimController.dynamicFov = dynamicFov
        aimController.fovZoomDelay = fovZoomDelay
        aimController.aimOffsetYRatio = aimOffsetYRatio
        aimController.aimSwayAmplitude = aimSwayAmplitude
        aimController.aimPrediction = aimPrediction
        aimController.aimHoldEnabled = aimHoldEnabled
        aimController.aimClasses = aimClasses.toMutableSet()
        aimController.priorityClass = priorityClass
        aimController.classAimOffsets = classAimOffsets
        aimController.boxAimRatio = boxAimRatio
        aimController.classBoxAimRatios = classBoxAimRatios
        aimController.recoilEnabled = recoilEnabled
        aimController.recoilStrength = recoilStrength
        aimController.recoilSpeed = recoilSpeed
        aimController.recoilResetIntervalMs = recoilResetIntervalMs

        // TriggerController
        triggerController.triggerEnabled = triggerEnabled
        triggerController.triggerReactionSpeed = triggerReactionSpeed
        triggerController.triggerCooldown = triggerCooldown
        triggerController.triggerUpFluct = triggerUpFluct
        triggerController.triggerDownFluct = triggerDownFluct
        triggerController.triggerTouchDuration = triggerTouchDuration
        triggerController.triggerRadiusPx = triggerRadiusPx
        triggerController.autoStopEnabled = autoStopEnabled
        triggerController.triggerOffsetYRatio = triggerOffsetYRatio
        triggerController.triggerClasses = triggerClasses.toMutableSet()
        triggerController.classTriggerOffsets = classTriggerOffsets
    }

    private fun loadConfigToService() {
        val cfg = ConfigManager.getConfig()
        kp = cfg.speed
        currentSpeed = cfg.speed
        currentConfidence = cfg.confidence
        triggerEnabled = cfg.triggerEnabled
        triggerReactionSpeed = cfg.triggerReactionSpeed
        triggerCooldown = cfg.triggerCooldown
        triggerUpFluct = cfg.triggerUpFluctuation
        triggerDownFluct = cfg.triggerDownFluctuation
        triggerTouchDuration = cfg.triggerTouchDuration
        triggerTouchRange = cfg.triggerTouchRange
        triggerRadiusPx = cfg.triggerRadiusPx
        triggerShowArea = cfg.triggerShowArea
        autoStopEnabled = cfg.autoStopEnabled
        aimHoldEnabled = cfg.aimHoldEnabled
        aimOffsetYRatio = cfg.aimOffsetYRatio
        aimSwayAmplitude = cfg.aimSwayAmplitude
        aimPrediction = cfg.aimPrediction
        triggerOffsetYRatio = cfg.triggerOffsetYRatio
        recoilEnabled = cfg.recoilEnabled
        recoilStrength = cfg.recoilStrength
        recoilSpeed = cfg.recoilSpeed
        recoilResetIntervalMs = cfg.recoilResetIntervalMs
        ki = cfg.ki; kf = cfg.kf
        aimDamping = cfg.aimDamping; approachAssist = cfg.approachAssist
        aimMode = cfg.aimMode
        bezierDuration = cfg.bezierDuration
        bezierControlOffset = cfg.bezierControlOffset
        bezierRandomSpread = cfg.bezierRandomSpread
        convergeThresh = cfg.convergeThresh.toFloat()
        aimFov = cfg.aimFov.coerceIn(20, (min(screenWidth, screenHeight) / 2).coerceAtLeast(20))
        showFov = cfg.showFov
        dynamicFov = cfg.dynamicFov
        fovZoomDelay = cfg.fovZoomDelay.coerceIn(0, 100)
        showInferInfo = cfg.showInferInfo
        showDetectionBox = cfg.showDetectionBox
        touchDisplayEnabled = cfg.aimTouchDisplay
        cachedRangePx = normalizeRangePx(cfg.range)
        aimbotOn.set(cfg.aimbotEnabled)
        aimClasses = cfg.aimClasses.toMutableSet()
        priorityClass = cfg.priorityClass
        classAimOffsets = cfg.classAimOffsets
        boxAimRatio = cfg.boxAimRatio
        classBoxAimRatios = cfg.classBoxAimRatios
        classTriggerOffsets = cfg.classTriggerOffsets
        triggerClasses = cfg.triggerClasses.toMutableSet()
        savedAreas.clear()
        savedAreas.addAll(cfg.areas)
        areasConfigured = cfg.areas.isNotEmpty()
        // 确保有4个区域（兼容旧配置）。注意这里补的是占位，不是可用配置 ——
        // 判断「用户配没配」一律看 areasConfigured。
        while (savedAreas.size < 4) {
            savedAreas.add(AreaConfig(name = when (savedAreas.size) {
                0 -> "开火区"
                1 -> "触发区"
                2 -> "瞄准区"
                else -> "摇杆范围"
            }, color = when (savedAreas.size) {
                1 -> android.graphics.Color.parseColor("#FF1976D2")
                3 -> android.graphics.Color.parseColor("#FF4CAF50")
                else -> android.graphics.Color.WHITE
            }))
        }
        JniCallBack.setConfidence(cfg.confidence)
        val cfgIdx = cfg.modelIndex
        if (cfgIdx !in 0 until ProjectionHolder.modelList.size) {
            ConfigManager.updateConfig { modelIndex = 0 }
        }
        ProjectionHolder.selectedModelIndex = if (cfgIdx in 0 until ProjectionHolder.modelList.size) cfgIdx else 0
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "RELOAD_MODEL") {
            Log.d(TAG, "收到RELOAD_MODEL, useCpuInference=${ConfigManager.getConfig().useCpuInference}")
            val entry = ProjectionHolder.modelList.getOrNull(ProjectionHolder.selectedModelIndex)
            if (entry != null) {
                loadModel(entry.filename)
                Log.d(TAG, "模型重新加载完成 (CPU推理设置变更), 后端=${JniCallBack.getBackend()}")
            }
            return START_STICKY
        }
        if (intent?.action == "HUD_PREF_CHANGED") {
            // 设置页"防录屏"开关切换。开关只决定显示层走哪条路线,
            // 不触碰推理 / 压枪 / 扳机等任何其他逻辑。
            val enabled = ConfigManager.getConfig().useNativeHud
            Log.i(TAG, "收到HUD_PREF_CHANGED, useNativeHud=$enabled, 当前hudNative=$hudNative")
            if (enabled) {
                // hudOn 是带 3s 超时的阻塞往返(execCmd):不能卡主线程,也不
                // 能压在推理 executor 上(单线程,会冻住推理循环 —— 同
                // TouchService tapExecutor 的理由)。失败结果经
                // onHudUnavailable 广播回设置页弹开关。
                if (!hudNative) Thread { setupHud() }.apply {
                    name = "yolovaim-hud-toggle"; isDaemon = true
                }.start() // 关→开:激活 native HUD,撤掉兜底渲染
            } else {
                if (hudNative) hudFallBack("user disabled 防录屏") // 开→关:关 layer,恢复原路线
            }
            return START_STICKY
        }
        if (intent?.action == "SYNC_MODEL") {
            val idx = ProjectionHolder.selectedModelIndex
            if (idx != lastModelIndex) {
                val entry = ProjectionHolder.modelList.getOrNull(idx)
                if (entry != null) {
                    lastModelIndex = idx
                    loadModel(entry.filename)
                    if (guiAdded) { guiPanel.modelIndex = idx; guiPanel.buildUI() }
                    Log.d(TAG, "同步模型切换: index=$idx, ${entry.displayName}")
                }
            }
            return START_STICKY
        }
        if (intent?.action == "RECONNECT_TOUCH") {
            val newMethod = ProjectionHolder.selectedTouchMethod
            Log.d(TAG, "收到RECONNECT_TOUCH, method=$newMethod")
            executor.execute {
                try {
                    Log.d(TAG, "RECONNECT: 开始断开旧连接 (state=${touchService.state})")
                    touchService.stopGeteventListener()
                    touchService.destroyRemote()
                    touchService.disconnect()
                    Log.d(TAG, "RECONNECT: 旧连接已断开, 开始连接新方式: $newMethod")
                    // 直接在当前线程执行，不提交到 executor 队列末尾
                    reconnectTouchInline()
                } catch (e: Exception) {
                    Log.e(TAG, "RECONNECT_TOUCH error: ${e.message}", e)
                }
            }
            return START_STICKY
        }
        if (intent?.action == "STOP") {
            if (mediaRecorder != null) toggleRecording(false)
            inferRunning.set(false); wakeInferLoop()
            executor.shutdown()
            cleanupViews()
            touchService.stopGeteventListener()
            touchService.destroyRemote()
            touchService.disconnect()
            mediaProjection?.stop()
            try { stopForeground(true) } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }
        val code = ProjectionHolder.resultCode; val data = ProjectionHolder.resultData
        if (data != null) {
            try {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = manager.getMediaProjection(code, data); setupImageReader()
            } catch (e: Exception) { Log.e(TAG, "projection创建失败: ${e.message}") }
        }
        setupBall(); setupOverlay(); initTouchInjector()
        lastModelIndex = ProjectionHolder.selectedModelIndex
        // Load classes map for current model
        val entry = ProjectionHolder.modelList.getOrNull(ProjectionHolder.selectedModelIndex)
        currentClasses = entry?.classes ?: emptyMap()
        if (aimClasses.isEmpty() && currentClasses.isNotEmpty()) aimClasses = currentClasses.keys.toMutableSet()
        if (triggerClasses.isEmpty() && currentClasses.isNotEmpty()) triggerClasses = currentClasses.keys.toMutableSet()
        Log.d(TAG, "启动模型类别: $currentClasses, aimClasses=$aimClasses, triggerClasses=$triggerClasses")
        // 兜底:引擎还没初始化就直接启动了服务。首次安装的典型路径就是它——
        // loadDefaultModel 只在 MainActivity onCreate+500ms 跑一次,那时模型
        // 列表还是空的;导入流程又不会加载引擎(下拉框 setText(...,false)
        // 不触发选择监听),用户点启动时 g_engine 是 null,推理循环全程
        // "Engine not initialized",状态栏显示"运行中 none"。
        if (entry != null && JniCallBack.getBackend() == "none") {
            Log.w(TAG, "引擎未初始化,服务启动时兜底加载: ${entry.filename}")
            loadModel(entry.filename)
        }
        ProjectionHolder.updateState(1, JniCallBack.getBackend())
        return START_NOT_STICKY
    }

    private fun broadcastState(state: Int, modelName: String? = null) {
        ProjectionHolder.updateState(state, modelName ?: ProjectionHolder.currentModelName)
    }

    private fun cleanupViews() {
        // native HUD 的 layer 生命周期跟着 daemon,但显式关掉干净:
        // 长驻的空 layer 也占合成开销(改进方案.md §5.2)
        try { if (hudNative) { hud()?.hudOff(); hudNative = false; hudSelfCheckPending = false; hudCheckStage = 0 } } catch (_: Exception) {}
        // 服务停止 = 防捕获层必然没了。广播 reason 置空:这是正常停止,
        // 不是故障,设置页不需要弹提示,开关保持 config 现值即可。
        ProjectionHolder.updateHudState(false, null)
        try { if (ballAdded) { wm.removeView(ballView); ballAdded = false } } catch (_: Exception) {}
        try { if (overlayAdded) { wm.removeView(overlayView); overlayAdded = false } } catch (_: Exception) {}
        try { if (guiAdded) { wm.removeView(guiPanel); guiAdded = false; guiVisible = false } } catch (_: Exception) {}
        try { if (triggerOverlayAdded) { wm.removeView(triggerOverlay); triggerOverlayAdded = false } } catch (_: Exception) {}
        try { if (touchDisplayAdded) { wm.removeView(touchDisplayView); touchDisplayAdded = false } } catch (_: Exception) {}
        try { if (areaSettingsAdded) { wm.removeView(areaSettingsView); areaSettingsAdded = false } } catch (_: Exception) {}
        // OverlayManager also owns the inference-info TextView. Without this
        // the line outlives the service — clear it here so a MainActivity
        // "Stop FloatService" actually drops every overlay window.
        try { overlayManager.cleanup() } catch (_: Exception) {}
    }

    private fun setupBall() {
        val size = dp(35)
        ballView = FloatBallView(this)
        ProjectionHolder.floatBallView = ballView
        ballParams = makeParams(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).apply { gravity = Gravity.TOP or Gravity.START; x = 50; y = 200 }
        ballView.onMoveCallback = { dx, dy -> ballParams?.let { it.x += dx; it.y += dy; wm.updateViewLayout(ballView, it) } }
        ballView.onClickCallback = { toggleGui() }; wm.addView(ballView, ballParams); ballAdded = true
    }

    private fun setupOverlay() {
        overlayView = OverlayCanvasView(this)
        ProjectionHolder.overlayCanvasView = overlayView
        val cfg = ConfigManager.getConfig()
        overlayView.aimbotEnabled = cfg.aimbotEnabled
        overlayView.showCaptureRange = cfg.showCaptureRange
        overlayView.showDetectionBox = cfg.showDetectionBox
        overlayView.showCenterDot = cfg.showCenterDot
        overlayView.showFov = cfg.showFov
        overlayView.fovRadius = cfg.aimFov
        overlayView.rangeRadius = cfg.range.coerceIn(50, 800)
        // Pin the overlay's draw frame to the MediaProjection capture dimensions.
        // setupImageReader() runs before setupOverlay() in onStartCommand, so
        // captureW/H already hold the current screen size here.
        overlayView.setGeometry(captureW, captureH)
        // Explicit (0,0) + physical screen size — without these, WindowManager
        // may reposition the overlay window when other overlay windows (e.g.
        // the GUI panel with FLAG_NOT_TOUCH_MODAL) become interactive, which
        // visibly shifts the center dot / capture range / detection rectangles
        // upward relative to the screen midpoint.
        overlayParams = makeParams(screenWidth, screenHeight, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }
        wm.addView(overlayView, overlayParams); overlayAdded = true

        // Create the inference-info TextView overlay up front. It stays GONE
        // until the user toggles "显示推理信息" — showing it can race with
        // WindowManager if the user opens the panel and flips the toggle
        // before the loop hits its first frame.
        overlayManager.setupInferInfoView()
    }

    // ====================== 防捕获 native HUD ======================
    // 数据流:推理线程 → 这里 publish* → RootInjectorClient 行协议('!' 无
    // 回复)→ root_daemon HUD 模块 → 防捕获 layer 绘制。物理屏可见,
    // 采集合成(MediaProjection/截屏/录屏)排除 —— 推理输入不再被自身
    // UI 污染(改进方案.md §1 的正反馈回路由此消除)。

    private fun hud(): HudClient? = if (hudNative) touchService.hud() else null

    /**
     * 注入连接建立后调用(初始连接与重连共用)。尝试激活 native HUD;
     * 失败或无 root → 保持 OverlayCanvasView 兜底。成功 → 重放全部状态
     * 并撤掉兜底渲染,再排一次采集帧自检。
     */
    private fun setupHud() {
        // 用户在设置页关掉"防录屏" → 强制走原 OverlayCanvasView 路线,
        // 不再尝试激活(也不吃无 root / HUD_ON 失败的自动回退)。
        if (!ConfigManager.getConfig().useNativeHud) {
            hudNative = false
            Log.i(TAG, "HUD: disabled by user (防录屏开关关闭), using fallback renderer")
            ProjectionHolder.updateHudState(false, null)
            updateHudDiagnostics()
            return
        }
        val client = touchService.hud()
        if (client == null) {
            Log.i(TAG, "HUD: root daemon unavailable, using fallback renderer")
            hudNative = false
            onHudUnavailable("未连接 Root 守护进程（防录屏需要 Root）")
            return
        }
        val ok = try { client.hudOn() } catch (e: Exception) {
            Log.w(TAG, "HUD: hudOn threw: ${e.message}"); false
        }
        hudNative = ok
        if (ok) {
            Log.i(TAG, "HUD: native anti-capture layer active")
            ProjectionHolder.updateHudState(true, null)
            // 门闩先清、再重放。反过来的话 replayHudState() 里刚记下的
            // lastHudFov 会被这里的 -1 冲掉,那行赋值成了死代码,而且下一帧还会
            // 把同一个半径再发一遍。
            hudSelfCheckPending = true
            hudCheckStage = 0
            hudCheckFrames = 0
            hudCheckMaxHits = 0
            hudCheckAttempts = 0
            lastHudFov = -1
            hudBoxesSent = false
            lastHudInfoText = null
            replayHudState(client)
            mainHandler.post {
                // 撤掉兜底渲染:两套叠加同屏会互相污染(兜底元素烧进采集帧)
                try { if (overlayAdded) { wm.removeView(overlayView); overlayAdded = false } } catch (_: Exception) {}
                overlayManager.setInferInfo("")
                updateHudDiagnostics()
            }
        } else {
            Log.w(TAG, "HUD: HUD_ON failed (symbol miss or layer creation), using fallback renderer")
            onHudUnavailable("设备不支持防捕获图层（符号缺失或图层创建失败）")
        }
    }

    /**
     * 防录屏激活失败。开关语义是"开着 = 生效"，所以除了走兜底渲染，
     * 还要把 useNativeHud 写回 false 并广播给设置页 —— 设置页的开关据此
     * 弹回关闭，避免"开关显示开着、实际却在走会被录进画面的路线"的假象。
     */
    private fun onHudUnavailable(reason: String) {
        if (ConfigManager.getConfig().useNativeHud) {
            ConfigManager.updateConfig { useNativeHud = false }
            Log.w(TAG, "HUD: 激活失败，防录屏开关回退为关闭 ($reason)")
        }
        ProjectionHolder.updateHudState(false, reason)
        updateHudDiagnostics()
    }

    /** daemon 重连/重启后的状态重放:开关 + 几何 + 当前值。 */
    private fun replayHudState(client: HudClient) {
        val cfg = ConfigManager.getConfig()
        client.hudToggle("captureRange", cfg.showCaptureRange)
        client.hudToggle("box", cfg.showDetectionBox)
        client.hudToggle("centerDot", cfg.showCenterDot)
        client.hudToggle("fov", cfg.showFov)
        client.hudToggle("inferInfo", cfg.showInferInfo)
        client.hudGeo(captureW, captureH)
        client.hudRange(normalizeRangePx(cfg.range))
        val fov = aimController.effectiveFov
        lastHudFov = fov
        client.hudFov(fov)
    }

    /** native HUD 失效(自检失败等)时恢复兜底渲染。 */
    private fun hudFallBack(reason: String) {
        hudNative = false
        hudSelfCheckPending = false
        hudCheckStage = 0
        hudCheckAttempts = 0
        try { touchService.hud()?.hudOff() } catch (_: Exception) {}
        Log.w(TAG, "HUD: fallback ($reason)")
        // 自检失败等运行时失效:开关同样回退(开着 = 生效的语义);
        // 用户主动关闭那条路 config 已是 false,只广播状态、不算故障。
        if (ConfigManager.getConfig().useNativeHud) {
            ConfigManager.updateConfig { useNativeHud = false }
            ProjectionHolder.updateHudState(false, reason)
        } else {
            ProjectionHolder.updateHudState(false, null)
        }
        mainHandler.post {
            try {
                if (!overlayAdded && overlayParams != null) {
                    wm.addView(overlayView, overlayParams)
                    overlayAdded = true
                }
            } catch (e: Exception) { Log.e(TAG, "HUD fallback addView: ${e.message}") }
            updateHudDiagnostics()
        }
    }

    // GUI 面板的诊断入口:系统 tab 一行小字显示当前 HUD 模式。
    // 模式存服务侧字段:触摸连接回调(诊断首次更新发生在这里)远早于
    // GUI 面板创建,只更新面板会把模式丢掉,面板建成后就永远是 unknown。
    @Volatile private var hudDiagMode = "unknown"

    private fun updateHudDiagnostics() {
        hudDiagMode = when {
            !ConfigManager.getConfig().useNativeHud -> "off"
            hudNative -> "native"
            else -> "fallback"
        }
        mainHandler.post {
            if (this::guiPanel.isInitialized) guiPanel.updateHudMode(hudDiagMode)
        }
    }

    /** 检测框发布:native HUD 或兜底 canvas,二选一。推理线程调用。 */
    private fun publishDetections(dets: List<DetectionInfo>) {
        if (hudNative) sendHudBoxes(dets) else overlayView.updateDetections(dets)
    }

    private fun sendHudBoxes(dets: List<DetectionInfo>) {
        if (!showDetectionBox) return // 开关关闭 → 推理热路径零 IPC(验收项)
        val n = dets.size.coerceAtMost(20) // daemon 端协议上限,与 detectionBuffer 等长
        if (n == 0) {
            // 清屏也要发一次,否则上一帧的框留在 layer 上
            if (hudBoxesSent) { hud()?.hudBoxes(IntArray(0)); hudBoxesSent = false }
            return
        }
        val a = IntArray(n * 4)
        var i = 0
        for (d in dets) {
            a[i++] = d.rect.left.toInt(); a[i++] = d.rect.top.toInt()
            a[i++] = d.rect.right.toInt(); a[i++] = d.rect.bottom.toInt()
        }
        hud()?.hudBoxes(a)
        hudBoxesSent = true
    }

    /**
     * FOV 半径发布:native 路径值变才发(动态 FOV 动画结束后值恒定)。
     *
     * [lastHudFov] 只在真的发出去时才推。圈没显示时这里不发 —— 动态 FOV
     * 收放期间那是每帧一条 IPC,画都没画的东西不值得占 cmdLock —— 但要是
     * 顺手把门闩也推了,daemon 端存的半径就永远停在隐藏前那一刻:再打开时
     * 门闩认为「已经发过」,过期半径不会被任何后续帧纠正
     * (daemon 侧 hud_renderer.h set_fov 隐藏时也存值,存的前提是收得到)。
     */
    private fun publishFov(r: Int) {
        if (hudNative) {
            if (r != lastHudFov && showFov) {
                lastHudFov = r
                hud()?.hudFov(r)
            }
        } else {
            overlayView.fovRadius = r
        }
    }

    /** 推理信息发布:native 路径渲染覆盖率掩码 → 一次 IPC;兜底走 TextView。 */
    private fun pushInferInfo(text: String) {
        // native 路径在调用线程(推理线程)直接渲染 + 一次 write,不再经过
        // mainHandler:主线程只负责 TextView 兜底那条路。
        if (hudNative) pushHudInfoText(text)
        else mainHandler.post { overlayManager.setInferInfo(text) }
    }

    private fun pushHudInfoText(text: String) {
        if (!showInferInfo) return
        if (text == lastHudInfoText) return // 同文本跳过 —— 与兜底 setInferInfo 的门闩一致
        // 不做时间节流:兜底 TextView 每帧刷新,native 路径也必须每帧,
        // 否则两条路线的推理信息刷新率不同(防录屏只该改变"是否被录到",
        // 不该改变任何显示效果)。
        lastHudInfoText = text
        val client = hud() ?: return
        try {
            // TextView 同款视觉:11sp 粗体浅黄字 + 半透明黑底药丸
            // (OverlayManager.kt setupInferInfoView 的参数)。药丸底不在
            // 这里画 —— 只把抗锯齿覆盖率传下去,由 daemon 按 fg/bg 合成,
            // 结果与旧的"整张 ARGB 位图"逐位一致。
            val density = resources.displayMetrics.density
            // 兜底是 TextView.textSize = 11f,单位 sp —— 跟随系统字体大小设置。
            // 用 density 会把字体缩放钉死在 1.0,系统字体调大过的机器上两条
            // 路线字号就不一样了,所以字号用 scaledDensity、内边距用 density
            // (dp(10)/dp(3) 本来就是 dp)。
            @Suppress("DEPRECATION")
            hudInfoPaint.textSize = 11f * resources.displayMetrics.scaledDensity
            val fm = hudInfoPaint.fontMetrics
            val padH = (10 * density).toInt(); val padV = (3 * density).toInt()
            val tw = kotlin.math.ceil(hudInfoPaint.measureText(text)).toInt()
            val th = kotlin.math.ceil(fm.descent - fm.ascent).toInt()
            val w = tw + padH * 2; val h = th + padV * 2
            if (w <= 0 || h <= 0) return
            // 尺寸没变就复用整套缓冲(位图/原生字节/掩码/Canvas)
            val old = hudMaskBmp
            val bmp: Bitmap
            if (old != null && old.width == w && old.height == h) {
                bmp = old
            } else {
                old?.recycle()
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                hudMaskBmp = bmp
                hudMaskCanvas = Canvas(bmp)
                val fresh = ByteArray(bmp.rowBytes * h)
                hudMaskRaw = fresh
                hudMaskRawBuf = java.nio.ByteBuffer.wrap(fresh)
                hudMask = ByteArray(w * h)
            }
            val canvas = hudMaskCanvas ?: return
            val raw = hudMaskRaw ?: return
            val mask = hudMask ?: return
            val buf = hudMaskRawBuf ?: return
            bmp.eraseColor(0) // 透明底:要的是纯覆盖率
            canvas.drawText(text, padH.toFloat(), padV - fm.ascent, hudInfoPaint)
            buf.clear()
            bmp.copyPixelsToBuffer(buf)
            // ARGB_8888 的原生字节序是 R,G,B,A —— 每像素第 4 个字节就是覆盖率
            val stride = bmp.rowBytes
            var di = 0
            for (y in 0 until h) {
                var si = y * stride + 3
                for (x in 0 until w) { mask[di++] = raw[si]; si += 4 }
            }
            client.hudTextMask(w, h, hudInfoFg, hudInfoBg, mask, w * h)
        } catch (e: Exception) {
            Log.e(TAG, "HUD text render failed: ${e.message}")
        }
    }

    /**
     * 首次启用自检(改进方案.md §6.1):HUD_ON 成功 ≠ 防捕获在
     * AUTO_MIRROR 采集路径上生效(demo 只验证过系统截屏)。
     *
     * 流程:下发 HUD_CHECK_ON → daemon 在 layer 上画洋红检查图案(中心
     * r=12 实心圆 + 半屏 1/4 圆环)→ 连续 3 个采集帧检索该颜色 →
     * 出现 = 没生效 → 立即退兜底;3 帧都没有 = 通过。洋红色游戏画面
     * 不会天然出现,首版"白像素"判据被游戏自己的中心准星打爆过。
     */
    private fun runHudSelfCheck(hb: android.hardware.HardwareBuffer) {
        val client = hud() ?: run { hudSelfCheckPending = false; return }
        if (hudCheckStage == 0) {
            client.hudCheck(true)
            hudCheckStage = 1
            hudCheckFrames = 0
            hudCheckMaxHits = 0
            hudCheckAttempts = 0
            return // 图案要等下一帧才出现在采集里
        }
        // 采样尝试要计数:下面 wrap/copy 失败时旧实现是裸 return,既不推进
        // hudCheckFrames 也不清 pending —— 该路径一旦持续失败,每个采集帧
        // 都会做一次全屏 HardwareBuffer→Bitmap 拷贝,永不退出。
        hudCheckAttempts++
        if (hudCheckAttempts > hudCheckMaxAttempts) {
            try { client.hudCheck(false) } catch (_: Exception) {}
            hudSelfCheckPending = false
            hudCheckStage = 0
            // 验不出来就按最坏情况处理:未经验证的防捕获一旦失效,HUD 会被
            // 烧进采集帧、污染推理输入(v1 就是这么静默失败的)。
            hudFallBack("self-check: ${hudCheckMaxAttempts} 帧都读不到采集帧,无法验证")
            return
        }
        val hw = try { Bitmap.wrapHardwareBuffer(hb, null) } catch (e: Exception) { null } ?: return
        val bmp = try { hw.copy(Bitmap.Config.ARGB_8888, false) } catch (e: Exception) { null }
        hw.recycle()
        if (bmp == null) return
        try {
            val cx = captureW / 2; val cy = captureH / 2
            val ringR = minOf(captureW, captureH) / 4
            fun magentaAt(x: Int, y: Int): Boolean {
                if (x < 0 || y < 0 || x >= captureW || y >= captureH) return false
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                return r > 200 && g < 90 && b > 200
            }
            var hits = 0
            // 中心实心圆 r=12:隔行采样避免整圆 ~450 次取像素
            for (dy in -12..12 step 2) for (dx in -12..12 step 2)
                if (dx * dx + dy * dy <= 144 && magentaAt(cx + dx, cy + dy)) hits++
            // 大圆环(4px 粗):16 个角度 × 径向 ±2
            for (i in 0 until 16) {
                val a = Math.PI * 2.0 * i / 16.0
                val ux = kotlin.math.cos(a).toFloat(); val uy = kotlin.math.sin(a).toFloat()
                for (d in -2..2)
                    if (magentaAt(cx + (ux * (ringR + d)).toInt(), cy + (uy * (ringR + d)).toInt())) hits++
            }
            if (hits > hudCheckMaxHits) hudCheckMaxHits = hits
            hudCheckFrames++
            if (hudCheckFrames >= 3) {
                client.hudCheck(false)
                if (hudCheckMaxHits >= 4) {
                    saveHudSelfCheckFrame(bmp, "fail")
                    Log.e(TAG, "HUD self-check FAILED: magenta pattern visible in capture (maxHits=$hudCheckMaxHits)")
                    hudFallBack("self-check: check pattern visible in capture frame")
                } else {
                    saveHudSelfCheckFrame(bmp, "pass")
                    Log.i(TAG, "HUD self-check passed: AUTO_MIRROR capture excludes HUD (maxHits=$hudCheckMaxHits)")
                }
                hudSelfCheckPending = false
                hudCheckStage = 0
            }
        } finally {
            bmp.recycle()
        }
    }

    /** 自检证据帧存到 dataset 目录,便于人工复核。 */
    private fun saveHudSelfCheckFrame(bmp: Bitmap, tag: String) {
        try {
            val f = java.io.File(datasetDir, "hud_selfcheck_$tag.jpg")
            java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        } catch (_: Exception) {}
    }

    private fun initTouchInjector() {
        // 刻意不走 executor：那是单线程的推理 executor，而 connect() 里的
        // su 握手在首次安装时要等用户点授权框（可能是几十秒）。压在同一条
        // 线程上会一起冻住两样东西：推理循环，以及 onStartCommand 里
        // loadModel() 的 executor.submit{}.get() 屏障（那是主线程）。
        // 结果就是「首装第一次完全没反应」。reconnectTouchInline 早就为了
        // 同样的理由绕开了 executor，这里补齐。
        Thread({
            touchService.connect(object : InjectorCallback {
                override fun onConnected() {
                    touchService.setDisplayRotation(displayRotation)
                    touchService.setResolution(captureW, captureH, deviceAbsMaxX, deviceAbsMaxY)
                    touchService.setInputMethod(ProjectionHolder.selectedTouchMethod)
                    updateTriggerZone()
                    updateFireZone()
                    updateJoystickZone()
                    // 防捕获 HUD:root daemon 在线才可能,激活后撤掉兜底渲染
                    setupHud()
                    Log.d(TAG, "TouchInjector connected, resolution=${deviceAbsMaxX}x${deviceAbsMaxY}, calling init...")
                    try {
                        val initOk = touchService.initRemote()
                        Log.d(TAG, "RemoteInjector init: $initOk")
                        if (!initOk) onInjectorInitFailed()
                        touchService.startGeteventListener()
                    } catch (e: Exception) {
                        Log.e(TAG, "initRemote error: ${e.message}")
                    }
                }
                override fun onDisconnected() {
                    Log.w(TAG, "TouchInjector disconnected")
                }
                override fun onError(msg: String) {
                    Log.e(TAG, "TouchInjector error: $msg")
                }
            })
        }, "touch-injector-connect").start()
    }

    /**
     * initRemote 失败的统一出口。目前只有 Stealth(KPM)路径会走到这里:
     * 无痕通道自检失败按既定策略【明确失败、不降级】—— 状态文本换成人话
     * 原因,主界面一眼能看到,不再只在 logcat 里躺着。
     */
    private fun onInjectorInitFailed() {
        val reason = touchService.lastStealthError()
        if (reason != null) {
            ProjectionHolder.touchStatusText = "无痕通道不可用: $reason"
            Log.e(TAG, "Stealth 通道不可用: $reason(不降级;检查 inputprobe.kpm 与两侧 touch_pairing.h)")
        }
    }

    /** 重新连接触摸注入器 — 直接在当前线程发起连接，不提交到 executor 队列 */
    private fun reconnectTouchInline() {
        val method = ProjectionHolder.selectedTouchMethod
        Log.d(TAG, "reconnectTouchInline: 连接 $method")
        touchService.connect(object : InjectorCallback {
            override fun onConnected() {
                Log.d(TAG, "RECONNECT: $method 已连接, 配置中...")
                touchService.setDisplayRotation(displayRotation)
                touchService.setResolution(captureW, captureH, deviceAbsMaxX, deviceAbsMaxY)
                touchService.setInputMethod(method)
                updateTriggerZone()
                updateFireZone()
                updateJoystickZone()
                // daemon 重启会丢 HUD 状态:重连后重放一次(开关+几何+当前值),
                // 与 zone 配置连接后重放的做法同构(改进方案.md §5.2)
                setupHud()
                try {
                    val initOk = touchService.initRemote()
                    Log.d(TAG, "RECONNECT: initRemote=$initOk")
                    if (!initOk) onInjectorInitFailed()
                    touchService.startGeteventListener()
                    Log.d(TAG, "RECONNECT: $method 切换完成")
                } catch (e: Exception) {
                    Log.e(TAG, "RECONNECT: initRemote error: ${e.message}", e)
                }
            }
            override fun onDisconnected() {
                Log.w(TAG, "RECONNECT: 新连接断开")
            }
            override fun onError(msg: String) {
                Log.e(TAG, "RECONNECT: 连接失败: $msg")
            }
        })
    }

    private fun toggleRecording(enabled: Boolean) {
        if (enabled) {
            if (mediaRecorder != null) return
            try {
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", java.util.Locale.US).format(java.util.Date())
                val random = (1000..9999).random()
                val dir = java.io.File("/storage/emulated/0/Pictures/Screenshots")
                if (!dir.exists()) dir.mkdirs()
                val outputFile = java.io.File(dir, "YOLOVAIM_${timestamp}_$random.mp4")
                val mr = android.media.MediaRecorder()
                mr.setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
                mr.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                mr.setOutputFile(outputFile.absolutePath)
                mr.setVideoEncodingBitRate(32_000_000)
                mr.setVideoFrameRate(60)
                mr.setVideoSize(captureW, captureH)
                mr.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.HEVC)
                mr.prepare()
                recordSurface = mr.surface
                mr.start()
                mediaRecorder = mr
                recordEnabled = true
                // 开录屏时自动启动推理循环
                if (!inferRunning.get()) startInferLoop()
                Log.d(TAG, "Recording started: ${outputFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Recording failed", e)
                try { mediaRecorder?.release() } catch (_: Exception) {}
                mediaRecorder = null
                recordSurface = null
            }
        } else {
            recordEnabled = false
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
            } catch (e: Exception) { Log.e(TAG, "Stop failed", e) }
            mediaRecorder = null
            recordSurface = null
            // 如果模型没在运行，停止推理循环
            if (!modelRunning) { inferRunning.set(false); wakeInferLoop(); broadcastState(1) }
            Log.d(TAG, "Recording stopped")
        }
    }

    private fun saveDatasetFrame(hwBuf: android.hardware.HardwareBuffer, result: FloatArray, count: Int) {
        try {
            // 首次保存时扫描目录，找到最大编号避免覆盖
            if (datasetCounter < 0) {
                datasetCounter = datasetDir.listFiles { f -> f.name.endsWith(".jpg") }
                    ?.mapNotNull { f -> f.nameWithoutExtension.toIntOrNull() }
                    ?.maxOrNull()?.let { it + 1 } ?: 0
            }
            val bmp = Bitmap.wrapHardwareBuffer(hwBuf, null) ?: return
            val idx = datasetCounter++
            val name = "%06d".format(idx)
            val imgFile = java.io.File(datasetDir, "$name.jpg")
            java.io.FileOutputStream(imgFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bmp.recycle()
            // 生成 YOLO 标注文件
            val txtFile = java.io.File(datasetDir, "$name.txt")
            java.io.BufferedWriter(java.io.FileWriter(txtFile)).use { w ->
                for (i in 0 until count) {
                    val classId = result[i * 6].toInt()
                    val x1 = result[i * 6 + 2]; val y1 = result[i * 6 + 3]
                    val x2 = result[i * 6 + 4]; val y2 = result[i * 6 + 5]
                    val cx = (x1 + x2) / 2f; val cy = (y1 + y2) / 2f
                    val bw = x2 - x1; val bh = y2 - y1
                    w.write("$classId $cx $cy $bw $bh\n")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dataset save failed", e)
        }
    }

    private fun setupTriggerOverlay() {
        if (triggerOverlayAdded) return
        triggerOverlay = TriggerOverlayView(this)
        ProjectionHolder.triggerOverlayView = triggerOverlay
        val size = dp(triggerTouchRange.coerceAtLeast(30))
        val p = makeParams(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        p.gravity = Gravity.TOP or Gravity.START
        p.x = screenWidth / 2 - size / 2; p.y = screenHeight / 2 - size / 2
        triggerAreaX = p.x; triggerAreaY = p.y
        triggerOverlay!!.areaSize = size
        triggerOverlay!!.onPositionChanged = { l, t -> triggerAreaX = l; triggerAreaY = t }
        wm.addView(triggerOverlay!!, p); triggerOverlayAdded = true
        triggerOverlay!!.alpha = 0f
        Log.d(TAG, "trigger overlay at ($triggerAreaX,$triggerAreaY) size=$size")
    }

    private fun updateTriggerOverlayVisibility() {
        val ov = triggerOverlay ?: return
        val p = ov.layoutParams as? WindowManager.LayoutParams ?: return
        if (triggerShowArea) {
            ov.alpha = 1f; p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            ov.alpha = 0f; p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try { wm.updateViewLayout(ov, p) } catch (_: Exception) {}
    }

    private fun updateTriggerOverlaySize() {
        val ov = triggerOverlay ?: return
        val p = ov.layoutParams as? WindowManager.LayoutParams ?: return
        val size = dp(triggerTouchRange.coerceAtLeast(30)); p.width = size; p.height = size
        ov.areaSize = size; try { wm.updateViewLayout(ov, p) } catch (_: Exception) {}
    }

    private fun setupTouchDisplayView() {
        if (touchDisplayAdded) return
        val size = dp(guiPanel.aimTouchSize) * 2
        touchDisplayView = TouchDisplayView(this)
        ProjectionHolder.touchDisplayView = touchDisplayView
        val p = makeParams(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        p.gravity = Gravity.TOP or Gravity.START
        p.x = screenWidth / 2 - size / 2; p.y = screenHeight / 2 - size / 2
        touchDisplayView!!.dotRadius = dp(guiPanel.aimTouchSize).toFloat()
        wm.addView(touchDisplayView, p); touchDisplayAdded = true
        touchDisplayView!!.alpha = 0f
    }

    private fun loadModel(filename: String) {
        val wasRunning = inferRunning.getAndSet(false)
        // The loop may be parked waiting for a frame; wake it so this
        // (main-thread) drain returns immediately instead of after the timeout.
        wakeInferLoop()
        try { executor.submit { }.get() } catch (_: Exception) {}
        JniCallBack.release()
        // 模型全部由用户经 ModelRepository 导入，ProjectionHolder 的快照里直接
        // 带绝对路径。条目不存在或文件被删了就放弃这次切换，没有 assets 兜底。
        val entry = ProjectionHolder.modelList.find { it.filename == filename }
        val modelFile = entry?.let { java.io.File(it.path) }
        if (entry == null || modelFile == null || !modelFile.exists()) {
            Log.e(TAG, "模型不可用（未导入或文件已被删除）: $filename")
            if (wasRunning) startInferLoop()
            return
        }
        try {
            // 保留 QNN 缓存：MainActivity 的预热已经把编译好的 HTP 图写进去了，
            // 清掉会让每次切模型都要重新编译。
            java.io.File(applicationContext.cacheDir, "qnn").mkdirs()
            val cfg = ConfigManager.getConfig()
            val useCpu = cfg.useCpuInference
            Log.d(TAG, "loadModel: useCpuInference=$useCpu, threads=${cfg.cpuThreadCount}")
            JniCallBack.setForceCpu(useCpu)
            JniCallBack.setCpuThreads(cfg.cpuThreadCount)
            // ncnn 需要显式告知输入尺寸；tflite 由引擎自己从模型里读
            if (entry.inputSize > 0) JniCallBack.setInputSize(entry.inputSize, entry.inputSize)
            if (JniCallBack.init(modelFile.absolutePath)) {
                val backend = JniCallBack.getBackend()
                Log.d(TAG, "模型切换成功: $filename, 后端=$backend")
                ProjectionHolder.currentModelName = backend
                currentClasses = entry.classes
                // 模型切换时更新类别选择：保留仍存在的类别，新增的自动选中
                if (currentClasses.isNotEmpty()) {
                    // NONE_CLASS 要留着：它代表用户显式关掉了全部类别，
                    // 被过滤掉的话下面的 isEmpty 分支会把全部类别又打开。
                    val validIds = currentClasses.keys
                    aimClasses = aimClasses.filter { it in validIds || it == GuiPanelView.NONE_CLASS }.toMutableSet()
                    if (aimClasses.isEmpty()) aimClasses = validIds.toMutableSet()
                    triggerClasses = triggerClasses.filter { it in validIds || it == GuiPanelView.NONE_CLASS }.toMutableSet()
                    if (triggerClasses.isEmpty()) triggerClasses = validIds.toMutableSet()
                }
                Log.d(TAG, "模型类别: $currentClasses, aimClasses=$aimClasses, triggerClasses=$triggerClasses")
                // Update GUI class list
                if (guiAdded) { guiPanel.classMap = currentClasses; guiPanel.aimClasses = aimClasses.toMutableSet(); guiPanel.triggerClasses = triggerClasses.toMutableSet(); guiPanel.buildUI() }
                broadcastState(ProjectionHolder.currentState)
            } else { Log.e(TAG, "模型切换失败: $filename") }
        } catch (e: Exception) { Log.e(TAG, "模型切换异常: ${e.message}") }
        if (wasRunning) startInferLoop()
    }

    private fun toggleGui() { if (guiVisible) hideGui() else showGui() }

    private fun showGui() {
        if (guiAdded) {
            // 复用已有面板，只更新状态并刷新UI
            guiPanel.aimbotEnabled = aimbotOn.get()
            guiPanel.modelRunning = modelRunning
            guiPanel.recordEnabled = recordEnabled
            guiPanel.autoSaveDataset = autoSaveDataset
            guiPanel.modelNames = ProjectionHolder.modelList.map { it.displayName }
            guiPanel.modelIndex = ProjectionHolder.selectedModelIndex
            guiPanel.classMap = currentClasses
            guiPanel.aimClasses = aimClasses.toMutableSet()
            guiPanel.priorityClass = priorityClass
            guiPanel.classAimOffsets = classAimOffsets.toMutableMap()
            guiPanel.boxAimRatio = boxAimRatio
            guiPanel.classBoxAimRatios = classBoxAimRatios.toMutableMap()
            guiPanel.classTriggerOffsets = classTriggerOffsets.toMutableMap()
            guiPanel.triggerClasses = triggerClasses.toMutableSet()
            guiPanel.maxFov = (min(screenWidth, screenHeight) / 2).coerceAtLeast(20)
            guiPanel.aimFov = aimFov.coerceIn(20, guiPanel.maxFov)
            guiPanel.showFov = showFov
            guiPanel.dynamicFov = dynamicFov
            guiPanel.fovZoomDelay = fovZoomDelay
            guiPanel.showInferInfo = showInferInfo
            guiPanel.buildUI()
            guiPanel.updateHudMode(hudDiagMode)
            guiPanel.visibility = View.VISIBLE; guiPanel.alpha = 0f; guiPanel.scaleX = 0.85f; guiPanel.scaleY = 0.85f
            guiPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start(); guiVisible = true; return
        }
        guiPanel = GuiPanelView(this)
        ProjectionHolder.guiPanelView = guiPanel
        val cfg = ConfigManager.getConfig()
        guiPanel.aimbotEnabled = aimbotOn.get()
        guiPanel.speed = cfg.speed
        guiPanel.range = overlayView.rangeRadius.coerceIn(50, 800)
        guiPanel.confidence = cfg.confidence
        guiPanel.triggerEnabled = cfg.triggerEnabled
        guiPanel.triggerReactionSpeed = cfg.triggerReactionSpeed
        guiPanel.triggerCooldown = cfg.triggerCooldown
        guiPanel.triggerUpFluctuation = cfg.triggerUpFluctuation
        guiPanel.triggerDownFluctuation = cfg.triggerDownFluctuation
        guiPanel.triggerTouchDuration = cfg.triggerTouchDuration
        guiPanel.triggerTouchRange = cfg.triggerTouchRange
        guiPanel.triggerRadiusPx = cfg.triggerRadiusPx
        guiPanel.triggerShowArea = cfg.triggerShowArea
        guiPanel.autoStopEnabled = cfg.autoStopEnabled
        guiPanel.aimHoldEnabled = cfg.aimHoldEnabled
        guiPanel.recoilEnabled = cfg.recoilEnabled
        guiPanel.recoilStrength = cfg.recoilStrength
        guiPanel.recoilSpeed = cfg.recoilSpeed
        guiPanel.recoilResetIntervalMs = cfg.recoilResetIntervalMs
        guiPanel.aimOffsetYRatio = cfg.aimOffsetYRatio
        guiPanel.aimSwayAmplitude = cfg.aimSwayAmplitude
        guiPanel.aimPrediction = cfg.aimPrediction
        guiPanel.triggerOffsetYRatio = cfg.triggerOffsetYRatio
        guiPanel.ki = cfg.ki; guiPanel.kf = cfg.kf
        guiPanel.aimDamping = cfg.aimDamping
        guiPanel.approachAssist = cfg.approachAssist
        guiPanel.aimMode = cfg.aimMode
        guiPanel.bezierDuration = cfg.bezierDuration
        guiPanel.bezierControlOffset = cfg.bezierControlOffset
        guiPanel.bezierRandomSpread = cfg.bezierRandomSpread
        guiPanel.convergeThresh = cfg.convergeThresh
        guiPanel.maxFov = (min(screenWidth, screenHeight) / 2).coerceAtLeast(20)
        guiPanel.aimFov = cfg.aimFov.coerceIn(20, guiPanel.maxFov)
        guiPanel.showFov = cfg.showFov
        guiPanel.dynamicFov = cfg.dynamicFov
        guiPanel.fovZoomDelay = cfg.fovZoomDelay.coerceIn(0, 100)
        guiPanel.showInferInfo = cfg.showInferInfo
        guiPanel.aimTouchDisplay = cfg.aimTouchDisplay
        guiPanel.aimTouchSize = 20
        guiPanel.modelRunning = modelRunning
        guiPanel.recordEnabled = recordEnabled
        guiPanel.autoSaveDataset = autoSaveDataset
        guiPanel.showCaptureRange = cfg.showCaptureRange
        guiPanel.showDetectionBox = cfg.showDetectionBox
        guiPanel.showCenterDot = cfg.showCenterDot
        guiPanel.activeTab = 0
        guiPanel.modelNames = ProjectionHolder.modelList.map { it.displayName }
        guiPanel.modelIndex = ProjectionHolder.selectedModelIndex
        guiPanel.onModelSelected = { idx ->
            val e = ProjectionHolder.modelList.getOrNull(idx)
            if (e != null) {
                ProjectionHolder.notifyModelIndexChanged(idx)
                ConfigManager.updateConfig { modelIndex = idx }
                lastModelIndex = idx
                loadModel(e.filename)
            }
        }
        guiPanel.classMap = currentClasses
        guiPanel.aimClasses = aimClasses.toMutableSet()
        guiPanel.priorityClass = priorityClass
        guiPanel.classAimOffsets = classAimOffsets.toMutableMap()
        guiPanel.boxAimRatio = boxAimRatio
        guiPanel.classBoxAimRatios = classBoxAimRatios.toMutableMap()
        guiPanel.classTriggerOffsets = classTriggerOffsets.toMutableMap()
        guiPanel.triggerClasses = triggerClasses.toMutableSet()
        guiPanel.buildUI()
        guiPanel.updateHudMode(hudDiagMode) // 面板后于连接建立,诊断值从这里补上
        val panelH = (screenHeight * 0.68f).toInt()
        guiParams = makeParams((280 * resources.displayMetrics.density).toInt(), panelH, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL).apply { gravity = Gravity.TOP or Gravity.START; x = 60; y = 200 }
        guiPanel.onClose = { hideGui() }
        guiPanel.onEnabledChanged = { on ->
            aimbotOn.set(on)
            overlayView.aimbotEnabled = on
            ConfigManager.updateConfig { aimbotEnabled = on }
            if (on) { aimingState.maxDragDist = (screenWidth.coerceAtMost(screenHeight) * 0.2f).coerceIn(100f, 600f) }
            Log.d("YolovaimInfer", "开关切换: $on")
        }
        guiPanel.onSpeedChanged = { kp = it; currentSpeed = it; aimController.kp = it; ConfigManager.updateConfig { speed = it } }
        guiPanel.onRangeChanged = { px ->
            overlayView.rangeRadius = px // 兜底渲染状态保持同步,随时可切换
            if (!hudNative) overlayView.postInvalidate() else hud()?.hudRange(px)
            ConfigManager.updateConfig { range = px }
        }
        guiPanel.onConfidenceChanged = { currentConfidence = it; JniCallBack.setConfidence(it); ConfigManager.updateConfig { confidence = it } }
        guiPanel.onTriggerEnabled = {
            triggerEnabled = it; triggerController.triggerEnabled = it
            ConfigManager.updateConfig { triggerEnabled = it }
            // 没配开火区就打开扳机，枪会开在屏幕中央的触发圆圈里而不是开火键上。
            // 这条提示是为了不让它再静默地"看起来完全没反应"。
            if (it && !areasConfigured) {
                android.widget.Toast.makeText(
                    this, "尚未设置开火区，扳机会点在屏幕中央的触发圆圈里\n请到「区域设置」里框出开火键",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        guiPanel.onTriggerReactionSpeed = { triggerReactionSpeed = it; triggerController.triggerReactionSpeed = it; ConfigManager.updateConfig { triggerReactionSpeed = it } }
        guiPanel.onTriggerCooldown = { triggerCooldown = it; triggerController.triggerCooldown = it; ConfigManager.updateConfig { triggerCooldown = it } }
        guiPanel.onTriggerUpFluctuation = { triggerUpFluct = it; triggerController.triggerUpFluct = it; ConfigManager.updateConfig { triggerUpFluctuation = it } }
        guiPanel.onTriggerDownFluctuation = { triggerDownFluct = it; triggerController.triggerDownFluct = it; ConfigManager.updateConfig { triggerDownFluctuation = it } }
        guiPanel.onTriggerTouchDuration = { triggerTouchDuration = it; triggerController.triggerTouchDuration = it; ConfigManager.updateConfig { triggerTouchDuration = it } }
        guiPanel.onTriggerRadiusPx = { triggerRadiusPx = it; triggerController.triggerRadiusPx = it; ConfigManager.updateConfig { triggerRadiusPx = it } }
        guiPanel.onTriggerTouchRange = { px -> triggerTouchRange = px; updateTriggerOverlaySize(); ConfigManager.updateConfig { triggerTouchRange = px } }
        guiPanel.onTriggerShowArea = { show -> triggerShowArea = show; if (show) setupTriggerOverlay(); updateTriggerOverlayVisibility(); ConfigManager.updateConfig { triggerShowArea = show } }
        guiPanel.onAutoStopEnabledChanged = { autoStopEnabled = it; triggerController.autoStopEnabled = it; ConfigManager.updateConfig { autoStopEnabled = it } }
        guiPanel.onAimOffsetYRatioChanged = { aimOffsetYRatio = it; aimController.aimOffsetYRatio = it; ConfigManager.updateConfig { aimOffsetYRatio = it } }
        guiPanel.onAimSwayAmplitudeChanged = { aimSwayAmplitude = it; aimController.aimSwayAmplitude = it; ConfigManager.updateConfig { aimSwayAmplitude = it } }
        guiPanel.onAimPredictionChanged = { aimPrediction = it; aimController.aimPrediction = it; ConfigManager.updateConfig { aimPrediction = it } }
        guiPanel.onTriggerOffsetYRatioChanged = { triggerOffsetYRatio = it; triggerController.triggerOffsetYRatio = it; ConfigManager.updateConfig { triggerOffsetYRatio = it } }
        guiPanel.onKiChanged = { ki = it; guiPanel.ki = it; aimController.ki = it; ConfigManager.updateConfig { ki = it } }
        guiPanel.onAimDampingChanged = { aimDamping = it; guiPanel.aimDamping = it; aimController.aimDamping = it; ConfigManager.updateConfig { aimDamping = it } }
        guiPanel.onApproachAssistChanged = { approachAssist = it; guiPanel.approachAssist = it; aimController.approachAssistEnabled = it; ConfigManager.updateConfig { approachAssist = it } }
        guiPanel.onKfChanged = { kf = it; guiPanel.kf = it; aimController.kf = it; ConfigManager.updateConfig { kf = it } }
        guiPanel.onAimModeChanged = { aimMode = it; aimController.aimMode = it; ConfigManager.updateConfig { aimMode = it } }
        guiPanel.onBezierDurationChanged = { bezierDuration = it; aimController.bezierDuration = it; ConfigManager.updateConfig { bezierDuration = it } }
        guiPanel.onBezierControlOffsetChanged = { bezierControlOffset = it; aimController.bezierControlOffset = it; ConfigManager.updateConfig { bezierControlOffset = it } }
        guiPanel.onBezierRandomSpreadChanged = { bezierRandomSpread = it; aimController.bezierRandomSpread = it; ConfigManager.updateConfig { bezierRandomSpread = it } }
        guiPanel.onConvergeThreshChanged = { convergeThresh = it.toFloat(); aimController.convergeThresh = it.toFloat(); ConfigManager.updateConfig { convergeThresh = it } }
        guiPanel.onAimbotFovChanged = { px ->
            val maxFov = (min(screenWidth, screenHeight) / 2).coerceAtLeast(20)
            val v = px.coerceIn(20, maxFov)
            aimFov = v
            aimController.aimFov = v
            overlayView.fovRadius = v
            // 同 publishFov:没发就不能推门闩(否则隐藏期间拖过的半径丢在半路)
            if (!hudNative) overlayView.postInvalidate() else if (showFov) { lastHudFov = v; hud()?.hudFov(v) }
            ConfigManager.updateConfig { aimFov = v }
        }
        guiPanel.onShowFovChanged = { on ->
            showFov = on
            overlayView.showFov = on
            if (!hudNative) overlayView.postInvalidate() else {
                // 先补半径再开开关:隐藏期间 publishFov 不发,daemon 存的还是
                // 隐藏前那个值,不补会先画一帧过期的圈。
                if (on) { lastHudFov = aimController.effectiveFov; hud()?.hudFov(lastHudFov) }
                hud()?.hudToggle("fov", on)
            }
            ConfigManager.updateConfig { showFov = on }
        }
        guiPanel.onDynamicFovChanged = { on ->
            dynamicFov = on
            aimController.dynamicFov = on
            if (on) aimController.resetDynamicFov()
            overlayView.fovRadius = aimController.effectiveFov
            if (!hudNative) overlayView.postInvalidate() else publishFov(aimController.effectiveFov)
            ConfigManager.updateConfig { dynamicFov = on }
        }
        guiPanel.onFovZoomDelayChanged = { ms ->
            val v = ms.coerceIn(0, 100)
            fovZoomDelay = v
            aimController.fovZoomDelay = v
            ConfigManager.updateConfig { fovZoomDelay = v }
        }
        guiPanel.onShowInferInfoChanged = { on ->
            showInferInfo = on
            // Clear stale text immediately when the user turns it off; the
            // inference loop won't post again once the toggle is off, and a
            // lazy clear would leave the previous frame's stats frozen on
            // screen.
            if (hudNative) {
                hud()?.hudToggle("inferInfo", on)
                if (!on) lastHudInfoText = null // 重新打开时第一条立即渲染
            } else {
                if (!on) overlayManager.setInferInfo("")
            }
            // Reset the EMA so the first sample after a re-enable is shown
            // verbatim instead of being averaged with a stale value from
            // before the toggle was flipped (models can warm up enough that
            // the new fps is wildly different from the old one).
            if (on) inferFps = 0f
            ConfigManager.updateConfig { showInferInfo = on }
        }
        guiPanel.onAimHoldEnabled = { aimHoldEnabled = it; aimController.aimHoldEnabled = it; ConfigManager.updateConfig { aimHoldEnabled = it } }
        guiPanel.onRecoilEnabledChanged = { recoilEnabled = it; aimController.recoilEnabled = it; ConfigManager.updateConfig { recoilEnabled = it } }
        guiPanel.onRecoilStrengthChanged = { recoilStrength = it; aimController.recoilStrength = it; ConfigManager.updateConfig { recoilStrength = it } }
        guiPanel.onRecoilSpeedChanged = { recoilSpeed = it; aimController.recoilSpeed = it; ConfigManager.updateConfig { recoilSpeed = it } }
        guiPanel.onRecoilResetIntervalChanged = { recoilResetIntervalMs = it; aimController.recoilResetIntervalMs = it; ConfigManager.updateConfig { recoilResetIntervalMs = it } }
        guiPanel.onAimTouchDisplay = { show ->
            touchDisplayEnabled = show
            ConfigManager.updateConfig { aimTouchDisplay = show }
            if (touchDisplayAdded) {
                val lp = touchDisplayView?.layoutParams as? WindowManager.LayoutParams
                if (lp != null) {
                    lp.flags = if (show) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    touchDisplayView?.alpha = if (show) 1f else 0f
                    try { wm.updateViewLayout(touchDisplayView, lp) } catch (_: Exception) {}
                }
            }
        }
        guiPanel.onAimTouchSize = { px ->
            ConfigManager.updateConfig { aimTouchSize = px }
            val p = dp(px)
            touchDisplayView?.dotRadius = p.toFloat()
            if (touchDisplayAdded) {
                val lp = touchDisplayView?.layoutParams as? WindowManager.LayoutParams
                if (lp != null) { lp.width = p * 2; lp.height = p * 2; wm.updateViewLayout(touchDisplayView, lp) }
            }
        }
        guiPanel.onShowCaptureRangeChanged = { on ->
            overlayView.showCaptureRange = on
            if (!hudNative) overlayView.postInvalidate() else hud()?.hudToggle("captureRange", on)
            ConfigManager.updateConfig { showCaptureRange = on }
        }
        guiPanel.onShowDetectionBoxChanged = { on ->
            showDetectionBox = on
            overlayView.showDetectionBox = on
            if (!hudNative) overlayView.postInvalidate() else hud()?.hudToggle("box", on)
            ConfigManager.updateConfig { showDetectionBox = on }
        }
        guiPanel.onShowCenterDotChanged = { on ->
            // 中心点现在真正是独立开关:旧实现的坑(嵌在 showCaptureRange
            // 的 if 里,单独开画不出来)在两条路径上都不再存在(方案 §5.3)
            overlayView.showCenterDot = on
            if (!hudNative) overlayView.postInvalidate() else hud()?.hudToggle("centerDot", on)
            ConfigManager.updateConfig { showCenterDot = on }
        }
        guiPanel.onRecordEnabledChanged = { on -> toggleRecording(on) }
        guiPanel.onAutoSaveDatasetChanged = { on -> autoSaveDataset = on }
        guiPanel.onAimClassesChanged = { classes -> aimClasses = classes.toMutableSet(); aimController.aimClasses = classes.toMutableSet(); ConfigManager.updateConfig { aimClasses = classes } }
        guiPanel.onPriorityClassChanged = { cls -> priorityClass = cls; aimController.priorityClass = cls; ConfigManager.updateConfig { priorityClass = cls } }
        guiPanel.onClassAimOffsetChanged = { id, value -> classAimOffsets = classAimOffsets.toMutableMap().apply { put(id, value) }; aimController.classAimOffsets = classAimOffsets; ConfigManager.updateConfig { classAimOffsets = this@FloatService.classAimOffsets } }
        guiPanel.onBoxAimRatioChanged = { boxAimRatio = it; aimController.boxAimRatio = it; ConfigManager.updateConfig { boxAimRatio = it } }
        guiPanel.onClassBoxAimRatioChanged = { id, value -> classBoxAimRatios = classBoxAimRatios.toMutableMap().apply { put(id, value) }; aimController.classBoxAimRatios = classBoxAimRatios; ConfigManager.updateConfig { classBoxAimRatios = this@FloatService.classBoxAimRatios } }
        guiPanel.onClassTriggerOffsetChanged = { id, value -> classTriggerOffsets = classTriggerOffsets.toMutableMap().apply { put(id, value) }; triggerController.classTriggerOffsets = classTriggerOffsets; ConfigManager.updateConfig { classTriggerOffsets = this@FloatService.classTriggerOffsets } }
        guiPanel.onTriggerClassesChanged = { classes -> triggerClasses = classes.toMutableSet(); triggerController.triggerClasses = classes.toMutableSet(); ConfigManager.updateConfig { triggerClasses = classes } }
        guiPanel.onToggleModel = { running ->
            modelRunning = running
            if (running && !inferRunning.get()) startInferLoop()
            else if (!running) {
                // 如果录屏还在运行，不要停止推理循环
                if (!recordEnabled) {
                    inferRunning.set(false); wakeInferLoop()
                    broadcastState(1)
                }
            }
        }
        guiPanel.onTestCircle = {
            mainHandler.post {
                Thread {
                    val cx = screenWidth / 4
                    val cy = screenHeight / 2
                    val radius = 200
                    val steps = 96
                    val startX = cx + radius
                    val startY = cy
                    touchService.swipe(startX, startY, startX, startY, 0)
                    Thread.sleep(50)
                    for (i in 1 until steps) {
                        val angle = (i * 360.0 / steps) * Math.PI / 180.0
                        val x = (cx + radius * Math.cos(angle)).toInt()
                        val y = (cy + radius * Math.sin(angle)).toInt()
                        touchService.moveTo(x, y)
                        Thread.sleep(50)
                    }
                    touchService.lift()
                }.start()
            }
        }
        guiPanel.onAreaSettingsToggle = { showAreaSettings() }

        overlayView.rangeRadius = guiPanel.range; JniCallBack.setConfidence(guiPanel.confidence)
        setupTriggerOverlay()
        setupTouchDisplayView()
        wm.addView(guiPanel, guiParams); guiAdded = true; guiVisible = true
        guiPanel.alpha = 0f; guiPanel.scaleX = 0.85f; guiPanel.scaleY = 0.85f
        guiPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
    }

    private fun hideGui() {
        if (guiAdded) guiPanel.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(150).withEndAction { guiPanel.visibility = View.GONE }.start()
        guiVisible = false
    }

    private fun setupAreaSettingsView() {
        areaSettingsView = AreaSettingsView(this)
        ProjectionHolder.areaSettingsView = areaSettingsView
        val params = makeParams(MATCH_PARENT, MATCH_PARENT, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        areaSettingsView?.apply {
            onConfirm = { areas ->
                savedAreas.clear()
                savedAreas.addAll(areas)
                areasConfigured = areas.isNotEmpty()
                ConfigManager.updateConfig { this.areas = areas.toList() }
                updateTriggerZone()
                updateFireZone()
                updateJoystickZone()
                removeAreaSettingsView()
                if (!guiVisible) showGui()
            }
            onCancel = {
                removeAreaSettingsView()
                if (!guiVisible) showGui()
            }
        }
        wm.addView(areaSettingsView!!, params)
        areaSettingsAdded = true
        areaSettingsView!!.visibility = View.GONE
    }

    private fun removeAreaSettingsView() {
        try { if (areaSettingsAdded) { wm.removeView(areaSettingsView); areaSettingsAdded = false; ProjectionHolder.areaSettingsView = null } } catch (_: Exception) {}
    }

    private fun showAreaSettings() {
        if (areaSettingsAdded) removeAreaSettingsView()
        setupAreaSettingsView()
        if (savedAreas.isNotEmpty()) areaSettingsView?.setAreas(savedAreas.toList())
        hideGui()
        areaSettingsView?.open()
    }

    private fun hideAreaSettings() {
        removeAreaSettingsView()
        if (!guiVisible) showGui()
    }

    // 推送触发区域到远程服务，用于物理手指检测
    private fun updateTriggerZone() {
        if (!areasConfigured) return   // 占位区域不是用户配的，推下去只会误判物理手指
        val zone = savedAreas.getOrNull(AREA_INDEX_TRIGGER) ?: return
        touchService.setTriggerZone(zone.x, zone.y, zone.right, zone.bottom)
        Log.d(TAG, "updateTriggerZone: (${zone.x},${zone.y})-(${zone.right},${zone.bottom})")
    }

    private fun updateFireZone() {
        if (!areasConfigured) return   // 占位区域不是用户配的，推下去只会误判物理手指
        val zone = savedAreas.getOrNull(AREA_INDEX_FIRE) ?: return
        touchService.setFireZone(zone.x, zone.y, zone.right, zone.bottom)
        Log.d(TAG, "updateFireZone: (${zone.x},${zone.y})-(${zone.right},${zone.bottom})")
    }

    private fun updateJoystickZone() {
        if (!areasConfigured) return   // 占位区域不是用户配的，推下去只会误判物理手指
        val zone = savedAreas.getOrNull(AREA_INDEX_JOYSTICK) ?: return
        touchService.setJoystickZone(zone.x, zone.y, zone.right, zone.bottom)
        Log.d(TAG, "updateJoystickZone: (${zone.x},${zone.y})-(${zone.right},${zone.bottom})")
    }

    // Dedicated looper for ImageReader callbacks. Must not be the main looper:
    // the callback fires at compositor rate, and waking the UI thread 60-120x/s
    // just to set a flag would show up as jank in every other overlay.
    private fun ensureReaderHandler(): Handler {
        readerHandler?.let { return it }
        val t = HandlerThread("YolovaimReader", android.os.Process.THREAD_PRIORITY_DISPLAY)
        t.start()
        readerThread = t
        val h = Handler(t.looper)
        readerHandler = h
        return h
    }

    // Wake the infer loop when a frame lands. Keeps no reference to the Image
    // itself — the loop still calls acquireLatestImage() and so still drops
    // stale frames exactly as before.
    private fun attachFrameListener(reader: ImageReader) {
        reader.setOnImageAvailableListener({ wakeInferLoop() }, ensureReaderHandler())
    }

    private fun setupImageReader() {
        captureW = screenWidth; captureH = screenHeight
        Log.d(TAG, "setupImageReader: w=${captureW} h=${captureH}")

        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        attachFrameListener(imageReader!!)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Log.d("YolovaimInfer", "MediaProjection 停止"); inferRunning.set(false); wakeInferLoop(); imageReader?.close() }
        }, Handler(Looper.getMainLooper()))
        captureVirtualDisplay = mediaProjection?.createVirtualDisplay("YolovaimCapture", captureW, captureH, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, null)
    }

    // Signals a new frame, and doubles as the way to unblock a loop parked in
    // awaitFrame() so it can re-read inferRunning and exit promptly.
    private fun wakeInferLoop() {
        frameLock.lock()
        try { frameReady = true; frameCond.signalAll() } finally { frameLock.unlock() }
    }

    private fun clearFrameFlag() {
        frameLock.lock()
        try { frameReady = false } finally { frameLock.unlock() }
    }

    // Park until the reader signals a frame. The bounded wait is a safety net,
    // not the normal path: a completely static screen produces no frames at all
    // (the old code span through that case doing nothing), and the timeout also
    // guarantees the loop notices inferRunning going false even if the listener
    // never fires again.
    private fun awaitFrame() {
        frameLock.lock()
        try {
            if (!frameReady) frameCond.await(20, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            frameLock.unlock()
        }
    }

    private fun startInferLoop() {
        if (inferRunning.getAndSet(true)) { Log.d(TAG, "infer loop already running"); return }
        broadcastState(2) // INFERENCING
        centerX = captureW / 2f; centerY = captureH / 2f
        aimController.recoilRefHeight = captureH.toFloat()
        Log.d(TAG, "infer loop started, center=($centerX,$centerY)")
        executor.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            var aliveCtr = 0
            var lastFrameNs = 0L
            var prevFingerOnFire = false
            // 排障用，只被下面那条每 30 帧一次的日志读取
            var dbgHeld = false; var dbgTaps = 0; var dbgRaw = -1
            // 链路仪表(TRACE_LAT)。窗口内累加与取峰，出一条日志后清零。
            // 分配在循环外：每帧新建数组会把仪表自己变成 GC 噪声源。
            val latSum = LongArray(LAT_N)
            val latMax = LongArray(LAT_N)
            var latFrames = 0
            var latDropped = 0      // 窗口内 detect() 返回 null 的帧数
            var tPrevFrameEnd = 0L
            while (inferRunning.get()) {
                if (++aliveCtr % 30 == 0) { Log.d(TAG, "alive trigger=$triggerEnabled connected=${touchService.isConnected()} detects=${hasDetects.get()}") }
                if (aliveCtr % 30 == 0) { Log.d(TAG, "recoil on=$recoilEnabled held=$dbgHeld taps=$dbgTaps offset=${aimController.recoilOffsetDebug} raw=$dbgRaw") }
                val currentRange = guiPanel.range
                val normRange = normalizeRangePx(currentRange)
                if (normRange != cachedRangePx) { cachedRangePx = normRange; cachedRange = normRange.toFloat() }

                // Clear the flag *before* acquiring: a frame that lands in the
                // window between here and acquireLatestImage() is picked up by
                // the acquire itself, and one that lands after leaves the flag
                // set so awaitFrame() returns immediately instead of parking on
                // an event that already happened.
                clearFrameFlag()
                val image = imageReader?.acquireLatestImage()
                if (image == null) { awaitFrame(); continue }

                // 扳机的反应速度从**本帧采集时刻**起算，不是从推理算完起算
                // (见 TriggerController 的「时间基」说明)：否则采集+推理这段
                // 延迟是叠加在反应速度之上的，而它每帧都在抖，出枪延迟就跟着抖。
                // Image.getTimestamp() 是 CLOCK_MONOTONIC 纳秒，与
                // System.nanoTime() 同一时基。OEM 的采集路径偶有不可信值
                // (0 / 未来 / 另一套时基)，所以只接受「刚过去 0~500ms」这个区间，
                // 区间外退回 nanoTime() —— 退化成「以拿到图的时刻起算」，仍然
                // 比「以推理算完起算」稳。
                val tsRaw = try { image.timestamp } catch (_: Exception) { 0L }
                val tsNow = System.nanoTime()
                val frameCaptureNs =
                    if (tsRaw > 0L && (tsNow - tsRaw) in 0L..500_000_000L) tsRaw else tsNow

                // 仪表：拿到图的时刻。idle 用上一帧结束时刻算，所以
                // acquire 失败 + awaitFrame 的那些空转迭代自然被算进空闲，
                // 不会被当成额外的帧。
                val tFrameGot = if (TRACE_LAT) System.nanoTime() else 0L
                var tHudNs = 0L     // 本帧 HUD 发布累计(两处相加)
                var tFireNs = 0L
                var tHoldNs = 0L
                var tJniNs = 0L
                var tAimNs = 0L
                var tTrigNs = 0L
                var tAcqNs = 0L
                var tTdNs = 0L
                // detect() 返回 null 的帧(引擎没就绪/推理失败)不该和正常帧
                // 混在一起看均值 —— 它们的 jni 段是失败路径的耗时。
                var frameHadResult = false

                // 防捕获 HUD 首次启用自检:在真实采集帧上找 HUD 像素,
                // 验证 AUTO_MIRROR 路径确实排除了该 layer(改进方案.md §6.1)
                if (hudSelfCheckPending) {
                    val hb = try { image.hardwareBuffer } catch (_: Exception) { null }
                    if (hb != null) {
                        try { runHudSelfCheck(hb) } catch (e: Exception) {
                            Log.e(TAG, "HUD self-check error: ${e.message}")
                            hudSelfCheckPending = false
                            hudCheckStage = 0
                            try { hud()?.hudCheck(false) } catch (_: Exception) {} // 图案不能留在屏上
                        } finally { hb.close() }
                    }
                }

                // Image.getHardwareBuffer() allocates a fresh HardwareBuffer
                // wrapper (plus two JNI transitions) on every call. Only the
                // recording and dataset paths need it, and both are off in
                // normal use, so fetching it unconditionally was per-frame waste.
                val needHwBuf = (recordEnabled && recordSurface != null) || autoSaveDataset
                // getHardwareBuffer() 会抛(image 已关闭等)。原先它在 try 之外,
                // 抛出来就跳过了 finally 的 image.close():ImageReader 只有 2 个
                // buffer,漏掉一个之后 acquireLatestImage() 迟早恒返回 null,推理
                // 循环退化成空转 awaitFrame() —— 不崩溃、无异常日志、完全没效果。
                val hwBuf = if (needHwBuf) {
                    try { image.hardwareBuffer } catch (e: Exception) {
                        Log.w(TAG, "hardwareBuffer 获取失败: ${e.message}"); null
                    }
                } else null
                try {
                    // 帧时基。压枪按时间累加，需要真实的墙钟帧间隔 —— 下面推理
                    // 信息里那个 inferFps 是从 pre+infer+post 算出来的"理论吞吐"
                    // (源码注释: not the actual capture rate)，不能拿来当 dt。
                    // 上限 0.1s：采集卡顿/应用切后台回来时 dt 可能是好几秒，
                    // 不夹一下会让压枪在一帧里跳掉几百 px。
                    val nowNs = System.nanoTime()
                    val dtSec = if (lastFrameNs == 0L) 0f
                                else ((nowNs - lastFrameNs) / 1e9f).coerceAtMost(0.1f)
                    lastFrameNs = nowNs

                    // 开火电平每帧只查一次 (IPC)，压枪与自动扳机共用这一个采样。
                    // 提到推理之前取：压枪要用本帧的开火状态，否则 executeAiming
                    // 读到的恒是上一帧末尾写入的值，判断永远滞后一帧。代价是
                    // processTrigger 拿到的样本比推理结果早约一个推理耗时，但它
                    // 只用来判断"用户是不是正在手动开火"，这点偏差无影响。
                    // 优先用注入层的上升沿计数(120-240Hz，短点击不会漏)。注入层不
                    // 支持时返回 -1，退回电平查询 + 应用侧数边沿 —— 后者采样率就是
                    // 推理帧率，短于一个帧间隔的点击仍会漏，但通路是已验证的。
                    val tFire0 = if (TRACE_LAT) System.nanoTime() else 0L
                    val packed = touchService.consumeFireState()
                    val fingerOnFire: Boolean
                    val fireTaps: Int
                    if (packed >= 0) {
                        fingerOnFire = (packed and 1) != 0
                        fireTaps = packed ushr 1
                    } else {
                        fingerOnFire = touchService.isFingerInFireZone()
                        fireTaps = if (fingerOnFire && !prevFingerOnFire) 1 else 0
                    }
                    // 回退分支里 isFingerInFireZone 也是一次阻塞往返，一起计入
                    // fire 段 —— 两条路走同一个「每帧问一次开火状态」的代价。
                    if (TRACE_LAT) tFireNs = System.nanoTime() - tFire0
                    prevFingerOnFire = fingerOnFire
                    val recoilHeld = fingerOnFire || triggerController.triggerFired
                    dbgHeld = recoilHeld; dbgTaps = fireTaps; dbgRaw = packed
                    // 压枪状态机每帧无条件推进，与有没有目标无关。挂在
                    // executeAiming 上(只在选到目标时调用)正是旧实现偏移冻结的根因。
                    aimController.updateRecoil(recoilHeld, fireTaps, dtSec, System.currentTimeMillis())

                    // 录屏: 把当前帧转发给 MediaRecorder
                    if (recordEnabled && recordSurface != null && hwBuf != null) {
                        try {
                            val canvas = recordSurface!!.lockHardwareCanvas()
                            try {
                                val bmp = Bitmap.wrapHardwareBuffer(hwBuf, null)
                                if (bmp != null) {
                                    canvas.drawBitmap(bmp, 0f, 0f, null)
                                    bmp.recycle()
                                }
                            } finally {
                                recordSurface!!.unlockCanvasAndPost(canvas)
                            }
                        } catch (_: Exception) {}
                    }
                    hasDetects.set(false)
                    val tAcq0 = if (TRACE_LAT) System.nanoTime() else 0L
                    val plane = image.planes[0]; val buffer = plane.buffer
                    // 裁剪区必须整块落在采集画面内。offsetX/offsetY 是 native 侧的
                    // 读取起点(litert 的 srcX/srcY_lut、ncnn 的 src_ptr),两边都不做
                    // 边界检查 —— 负的 offset 就是从 buffer 之前开始读。
                    // 滑条上限 800 → 边长 1600px,而横屏采集高度典型 1080,所以滑条
                    // 拖过 540 就已经越界(720p 宽的机器 360 就开始)。
                    // 夹成正方形而不是分别夹:整条链路(HUD 的截取范围圆、坐标回映)
                    // 都按方形裁剪区写的,只在这里按短边收一下最不意外。
                    val half = cachedRangePx.coerceAtMost(minOf(captureW, captureH) / 2)
                    val regionW = half * 2; val regionH = half * 2
                    val offsetX = (captureW - regionW) / 2; val offsetY = (captureH - regionH) / 2
                    if (TRACE_LAT) tAcqNs = System.nanoTime() - tAcq0

                    val tJni0 = if (TRACE_LAT) System.nanoTime() else 0L
                    val result = JniCallBack.detect(buffer, offsetX, offsetY, regionW, regionH, captureW, captureH, plane.rowStride, plane.pixelStride)
                    if (TRACE_LAT) tJniNs = System.nanoTime() - tJni0
                    if (TRACE_LAT && result != null) frameHadResult = true

                    // Per-stage inference-info overlay. Only fetched + posted
                    // when the user toggled "显示推理信息" on — when off, this
                    // entire block is a boolean read, so the inference loop
                    // pays nothing for the toggle being disabled (no extra JNI
                    // calls, no String formatting, no MainThread post).
                    if (showInferInfo) {
                        val tHud0 = if (TRACE_LAT) System.nanoTime() else 0L
                        val count = if (result != null) result.size / 6 else 0
                        val timings = JniCallBack.getInferTimings()
                        val pre = timings?.getOrNull(0) ?: 0f
                        val inferMs = timings?.getOrNull(1) ?: 0f
                        val post = timings?.getOrNull(2) ?: 0f
                        // fps = how many inferences COULD complete per second
                        // at the current per-frame budget, not the actual
                        // capture rate (which may be throttled by the
                        // ImageReader / projection capture pipeline). Clamp
                        // to a sane upper bound — on fast QNN HTP runs the
                        // per-frame time can dip below 1ms and produce a
                        // multi-thousand-fps number that just looks like noise.
                        val totalMs = pre + inferMs + post
                        val currentFps = if (totalMs > 0.001f) {
                            (1000f / totalMs).coerceAtMost(999.9f)
                        } else 999.9f
                        // EMA smoothing (alpha=0.3): ~5 frames to settle to
                        // within 10% of a step change. Field is reset to 0 by
                        // the toggle callback so the first displayed value
                        // after a re-enable is the fresh sample, not a stale
                        // average from hours ago.
                        inferFps = if (inferFps == 0f) currentFps
                                   else (0.7f * inferFps + 0.3f * currentFps)
                        val text = String.format(
                            java.util.Locale.US,
                            "推理 %.1ffps · 预处理 %.2fms · 后处理 %.2fms · 检测 %d",
                            inferFps, pre, post, count
                        )
                        pushInferInfo(text)
                        // 掩码渲染(w*h 逐像素取 alpha)+ 持 cmdLock 写 16-50KB
                        // 进管道全在这一段里 —— 这是 HUD 与注入命令抢通道的
                        // 那一处，单独可见才判断得出「推理信息开关贵不贵」。
                        if (TRACE_LAT) tHudNs += System.nanoTime() - tHud0
                    }

                    // 按住激发: 物理手指按在触发区时才能自瞄（提到外层，使 lift 条件也能读到）
                        val tHold0 = if (TRACE_LAT) System.nanoTime() else 0L
                        val holdToAimActive = if (aimHoldEnabled) touchService.isFingerInTriggerZone() else true
                        // 关掉按住激发时这一段恒为 0(没有 IPC)，正好用来对比
                        // 开启它多付的那次阻塞往返。
                        if (TRACE_LAT) tHoldNs = System.nanoTime() - tHold0

                        // target 提到分支外:下面的动态 FOV 每帧都要 tick,
                        // 包含 result == null(画面里一个目标都没有)的帧。
                        var target: DetectionInfo? = null
                        // 自瞄本帧的瞄点(含框内偏移 / Y偏移 / 压枪)。扳机判定要用
                        // **同一个点**，不能各自按框再算一遍 —— 那正是两套判据漂开的
                        // 老路。只在 target != null 时被写入，与 target 严格同生。
                        var aimPointX = 0f
                        var aimPointY = 0f
                        // 本帧的收敛容差(逐轴、按目标框缩放)。自瞄和扳机共用这两个数，
                        // 见 AimController.convergeTolerance。
                        var aimTolX = 0f
                        var aimTolY = 0f
                        if (result != null) {
                            val count = result.size / 6
                            if (TRACE && count > 0) {
                                val cid = result[0].toInt()
                                val sc = result[1]
                                val className = currentClasses[cid] ?: "unknown"
                                Log.d(TAG, "detect: count=$count, classId=$cid ($className) score=${"%.3f".format(sc)}")
                            }
                            var detCount = 0; var i = 0
                            while (i < count && detCount < detectionBuffer.size) {
                                val cid = result[i * 6].toInt()
                                val rect = RectF(result[i*6+2]*captureW, result[i*6+3]*captureH, result[i*6+4]*captureW, result[i*6+5]*captureH)
                                detectionBuffer[detCount] = DetectionInfo(rect, cid, currentClasses[cid] ?: "cls$cid")
                                detCount++; i++
                            }
                            lastDetections = detectionBuffer.take(detCount)
                            hasDetects.set(detCount > 0)
                            // updateDetections() is safe to call from any thread
                            // (it ends in postInvalidateOnAnimation), so the
                            // mainHandler.post hop was a lambda + Message + main
                            // looper wake-up per frame for nothing.
                            val tHudBox0 = if (TRACE_LAT) System.nanoTime() else 0L
                            publishDetections(lastDetections)
                            // native HUD 路径这是一条 HUD_BOXES(几百字节)，
                            // 兜底路径是一次 postInvalidateOnAnimation。两者
                            // 都算进 hud 段，和上面的推理信息掩码合并计。
                            if (TRACE_LAT) tHudNs += System.nanoTime() - tHudBox0

                            if (autoSaveDataset && detCount > 0 && hwBuf != null) {
                                saveDatasetFrame(hwBuf, result, count)
                            }

                            // Filter detections by aimClasses
                            val aimDets = if (aimClasses.isEmpty()) lastDetections
                                else lastDetections.filter { it.classId in aimClasses }

                            target = if (aimbotOn.get() && aimDets.isNotEmpty() && holdToAimActive) {
                                val tAim0 = if (TRACE_LAT) System.nanoTime() else 0L
                                val t = aimController.selectTarget(aimDets, centerX, centerY)
                                if (t != null) {
                                    val tcx = t.rect.centerX(); val tcy = t.rect.centerY()
                                    // boxH 就是目标框自己的高。旧版这里在 aimDets 里找
                                    // 离 t 中心最近的框 —— 但 t 自己就在集合里且距离恒为 0，
                                    // 结果恒等于 t.rect.height()，白扫一遍 O(n)。
                                    val boxH = t.rect.height()
                                    val classOffset = aimController.classAimOffsets[t.classId] ?: aimController.aimOffsetYRatio
                                    val classBoxRatio = aimController.classBoxAimRatios[t.classId] ?: aimController.boxAimRatio
                                    val aimX = tcx
                                    val aimY = (tcy - boxH * 0.5f) + boxH * (1f - classBoxRatio) - boxH * classOffset
                                    aimPointX = aimX
                                    // 压枪偏移是 executeAiming 内部加的，扳机必须看到
                                    // 加完之后的值，否则持续开火时两边又会差一个
                                    // recoilOffsetY(可累到上百 px)。
                                    aimPointY = aimController.effectiveAimY(aimY)
                                    // 死区按框缩放：绝对 10px 在远距离(框高 15~20px)
                                    // 是半个框，自瞄会系统性地停在框边缘。boxH 与算
                                    // 瞄点时用的是同一个，宽度直接取目标框的。
                                    aimTolX = aimController.convergeTolerance(t.rect.width())
                                    aimTolY = aimController.convergeTolerance(boxH)
                                    // 速度估计与 dt 都由 executeAiming 内部推进
                                    // (它是唯一知道本轮 dt 的地方)；这里只把
                                    // **未加瞄点偏移**的框中心传进去 —— 框高每帧
                                    // 在抖,拿 aimY 去差分等于把框高噪声算进目标速度。
                                    aimController.executeAiming(
                                        aimX, aimY, centerX, centerY, aimTolX, aimTolY,
                                        frameCaptureNs = frameCaptureNs,
                                        boxCenterX = tcx, boxCenterY = tcy
                                    )
                                }
                                // 含选靶 + 瞄点 + PID/Bezier + MOVE 管道写。
                                // MOVE 是 '!' 无回复，所以这一段**不含** daemon
                                // 的执行时间 —— 它只是「交出去」的代价。
                                if (TRACE_LAT) tAimNs = System.nanoTime() - tAim0
                                t
                            } else null
                        } else {
                            hasDetects.set(false); lastDetections = emptyList()
                            val tHudClr0 = if (TRACE_LAT) System.nanoTime() else 0L
                            publishDetections(lastDetections)
                            if (TRACE_LAT) tHudNs += System.nanoTime() - tHudClr0
                        }

                        // Dynamic FOV ticks every inference frame regardless of
                        // aimbot state — so the FOV can expand back to normal
                        // even after the target is lost or aimbot is toggled off.
                        //
                        // 「每帧」是字面意思。这两行原先在 result != null 分支里,
                        // 而画面里没有目标时 detect() 返回的就是 null(native 侧
                        // yolovaim.cpp: detections.empty() → nullptr)—— 也就是
                        // 「目标丢了」最常见的那条路恰好一次都不 tick:圈冻在收缩
                        // 后的半径上等人回来,fovZoomDelay 的回弹从来没生效过;
                        // 启动后没人的那段同理,配置里的半径要等画面里出现第一个
                        // 目标才会被发到 HUD。
                        aimController.updateDynamicFov(target, System.currentTimeMillis())
                        publishFov(aimController.effectiveFov)
                        if (target == null) aimController.clearFinishHistory()

                        // 抬起条件：按下虚拟触摸但瞄准条件任一不满足都应释放（修了按住激发中途松手后触摸点卡住的 bug）
                        if (aimController.aimingState.pointerDown &&
                            (!aimbotOn.get() || !hasDetects.get() || !holdToAimActive)) {
                            aimController.lift()
                        }

                        // detection-based trigger: 准星落在任一检测框内即在靶
                        // (按 triggerClasses 过滤，不是 aimClasses —— 两套类别集合独立)。
                        // 自瞄正在 steer 的那个框额外并上自瞄的收敛区，见 processTrigger。
                        // The fire-zone state is queried once per frame (at the
                        // top of the loop, where the recoil state machine needs
                        // it) and handed down here. It used to be re-queried over
                        // IPC; both reads happen in the same frame and describe
                        // the same physical finger, so the second round-trip
                        // could only ever return the same answer.
                        val tTrig0 = if (TRACE_LAT) System.nanoTime() else 0L
                        triggerController.processTrigger(
                            lastDetections, centerX, centerY, hasDetects.get(), fingerOnFire,
                            aimTarget = target,
                            aimPointX = aimPointX,
                            aimPointY = aimPointY,
                            aimToleranceX = aimTolX,
                            aimToleranceY = aimTolY,
                            frameCaptureNs = frameCaptureNs
                        )
                        // triggerTap 已经在 TouchService 里改成投到 tapExecutor，
                        // 所以这一段应当接近 0。如果它不是 0，说明那条异步化
                        // 回退了或者 tapInFlight 一直被占。
                        if (TRACE_LAT) tTrigNs = System.nanoTime() - tTrig0
                } catch (e: Exception) { Log.e(TAG, "推理帧异常: ${e.message}") }
                finally {
                    val tTd0 = if (TRACE_LAT) System.nanoTime() else 0L
                    hwBuf?.close(); image.close()
                    if (TRACE_LAT) {
                        tTdNs = System.nanoTime() - tTd0
                        // buffer 释放也算进 total —— 它是每帧都要付的真实开销。
                        val tEnd = System.nanoTime()
                        if (tPrevFrameEnd != 0L) {
                            val idle = tFrameGot - tPrevFrameEnd
                            latSum[LAT_IDLE] += idle
                            if (idle > latMax[LAT_IDLE]) latMax[LAT_IDLE] = idle
                        }
                        tPrevFrameEnd = tEnd
                        // 逐段直接累加，不建临时数组 —— 仪表本身不该在热路径上
                        // 每帧多一个短命对象。
                        fun acc(k: Int, v: Long) {
                            latSum[k] += v
                            if (v > latMax[k]) latMax[k] = v
                        }
                        acc(LAT_FIRE, tFireNs); acc(LAT_HOLD, tHoldNs)
                        acc(LAT_JNI, tJniNs);   acc(LAT_HUD, tHudNs)
                        acc(LAT_AIM, tAimNs);   acc(LAT_TRIG, tTrigNs)
                        acc(LAT_ACQ, tAcqNs);   acc(LAT_TD, tTdNs)
                        acc(LAT_TOTAL, tEnd - tFrameGot)
                        if (!frameHadResult) latDropped++
                        if (++latFrames >= LAT_WINDOW) {
                            latEmit(latSum, latMax, latFrames, latDropped, lastDetections.size)
                            java.util.Arrays.fill(latSum, 0L)
                            java.util.Arrays.fill(latMax, 0L)
                            latFrames = 0; latDropped = 0
                        }
                    }
                }
            }
            inferRunning.set(false)
        }
    }

    /**
     * 输出一个窗口的链路延迟聚合。每 [LAT_WINDOW] 帧一条，只在 [TRACE_LAT] 下调用。
     *
     * 每段两个数：avg/max(ms)。**看的时候先看 max。** avg 只说明「平时贵不贵」，
     * 而要判断的几件事都是尾部现象：
     *   fire/hold 的 max 远大于 avg → 阻塞往返被调度掐住(推理线程是
     *     URGENT_DISPLAY，readerExec 是默认优先级，典型优先级反转)。
     *     max 逼近 500 就是 execCmd 的 CMD_TIMEOUT_MS，那一帧注入通道会被判死。
     *   hud 的 max 大 → 推理信息掩码那块 16-50KB 的写在 cmdLock 上把注入挤住了。
     *   idle 趋零 → 推理已经吃满采集，提不动了；idle 大 → 瓶颈在采集侧。
     *   total ≈ 各段之和 → 没有漏计的大头；差很多说明开销在没插桩的地方。
     *   acq/td 是每帧必付的采集侧开销(取 plane、还缓冲)，不是可省的浪费 ——
     *     加桩是为了让 total 的账能对上，之前那 1.64ms 的差额就落在这里。
     */
    private fun latEmit(sum: LongArray, max: LongArray, frames: Int, dropped: Int, dets: Int) {
        val n = frames.coerceAtLeast(1)
        fun a(i: Int) = sum[i] / n / 1e6
        fun m(i: Int) = max[i] / 1e6
        Log.d(LAT_TAG, String.format(
            java.util.Locale.US,
            "n=%d drop=%d d=%d | idle %.2f/%.2f | fire %.2f/%.2f | hold %.2f/%.2f | " +
            "jni %.2f/%.2f | hud %.2f/%.2f | aim %.2f/%.2f | trig %.2f/%.2f | " +
            "acq %.2f/%.2f | td %.2f/%.2f | total %.2f/%.2f (avg/max ms)",
            frames, dropped, dets,
            a(LAT_IDLE), m(LAT_IDLE), a(LAT_FIRE), m(LAT_FIRE), a(LAT_HOLD), m(LAT_HOLD),
            a(LAT_JNI), m(LAT_JNI), a(LAT_HUD), m(LAT_HUD), a(LAT_AIM), m(LAT_AIM),
            a(LAT_TRIG), m(LAT_TRIG), a(LAT_ACQ), m(LAT_ACQ), a(LAT_TD), m(LAT_TD),
            a(LAT_TOTAL), m(LAT_TOTAL)
        ))
    }

    private fun makeParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT).allowDisplayCutout()

    /**
     * 「截取范围」滑条值 → 实际用的裁剪半边长(px)。
     *
     * 夹紧到滑条自己的区间(见 GuiPanelView 的 48f..800f)再对齐到 16 的倍数。
     * 抽成一处是因为原来有两条路:配置载入时夹紧+对齐,而运行时改滑条直接用
     * 原值 —— 同一个滑条位置在启动后和拖动后算出的裁剪区大小能差最多 8px。
     *
     * 注意这里**不**按采集尺寸夹:采集尺寸会随旋转变,而这个值是用户设置的
     * 意图值,要原样存进配置。跟画面边界的夹紧发生在用它算 offset 的地方。
     */
    private fun normalizeRangePx(v: Int) = ((v.coerceIn(48, 800) + 8) / 16) * 16

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val ch = NotificationChannel(CH_ID, "YOLOVAIM", NotificationManager.IMPORTANCE_LOW); (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch) } }
    private fun buildNotification() = NotificationCompat.Builder(this, CH_ID).setContentTitle("YOLOVAIM").setContentText("运行中").setSmallIcon(android.R.drawable.ic_menu_view).build()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "orientation changed: display=${screenWidth}x${screenHeight} capture=${captureW}x${captureH}")
        // Use current display dimensions for orientation and resolution
        touchService.setDisplayRotation(displayRotation)
        touchService.setResolution(screenWidth, screenHeight, deviceAbsMaxX, deviceAbsMaxY)
        centerX = captureW / 2f; centerY = captureH / 2f

        // Update overlay positions (UI uses current display metrics)
        val ov = triggerOverlay
        if (triggerOverlayAdded && ov != null) {
            val size = dp(triggerTouchRange.coerceAtLeast(30))
            (ov.layoutParams as? WindowManager.LayoutParams)?.let { p ->
                p.width = size; p.height = size
                p.x = screenWidth / 2 - size / 2; p.y = screenHeight / 2 - size / 2
                triggerAreaX = p.x; triggerAreaY = p.y
                wm.updateViewLayout(ov, p)
            }
        }
        if (overlayAdded) {
            (overlayView.layoutParams as? WindowManager.LayoutParams)?.let { p ->
                p.width = screenWidth; p.height = screenHeight
                p.gravity = Gravity.TOP or Gravity.START
                p.x = 0; p.y = 0
                p.flags = p.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                wm.updateViewLayout(overlayView, p)
            }
            // Re-anchor the draw frame to the (possibly new) capture dimensions.
            overlayView.setGeometry(screenWidth, screenHeight)
        }
        if (guiAdded && guiVisible) {
            wm.removeView(guiPanel); guiAdded = false; showGui()
        }

        // Resize capture without recreating VirtualDisplay (avoids SecurityException)
        restartCapture()
    }

    private fun restartCapture() {
        val curW = screenWidth; val curH = screenHeight
        if (captureW == curW && captureH == curH) return  // no change

        val wasRunning = inferRunning.getAndSet(false)
        wakeInferLoop()
        Log.d(TAG, "restartCapture: wasRunning=$wasRunning newSize=${curW}x${curH}")
        executor.execute {
            try { Thread.sleep(200) } catch (_: Exception) {}
            // Close old reader
            val oldReader = imageReader
            imageReader = null
            try { oldReader?.close() } catch (_: Exception) {}
            // Create new reader at new size
            imageReader = ImageReader.newInstance(curW, curH, PixelFormat.RGBA_8888, 2)
            attachFrameListener(imageReader!!)
            // Resize VirtualDisplay and attach new surface
            try {
                captureVirtualDisplay?.resize(curW, curH, screenDensity)
                captureVirtualDisplay?.setSurface(imageReader!!.surface)
                Log.d(TAG, "restartCapture: resized to ${curW}x${curH}")
            } catch (e: Exception) {
                Log.w(TAG, "VirtualDisplay resize failed: ${e.message}")
            }
            captureW = curW; captureH = curH
            touchService.setDisplayRotation(displayRotation)
            touchService.setResolution(curW, curH, deviceAbsMaxX, deviceAbsMaxY)
            centerX = captureW / 2f; centerY = captureH / 2f
            // native HUD 不在 WindowManager 里,旋转不会自动适配:重发采集
            // 几何,daemon 端顺带刷新 SurfaceFlinger 方向并重算坐标变换
            // (WM 兜底路径由 onConfigurationChanged 里的 updateViewLayout 覆盖)
            if (hudNative) touchService.hud()?.hudGeo(curW, curH)
            if (wasRunning) startInferLoop()
        }
    }

    override fun onDestroy() {
        try {
            (getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
                .unregisterDisplayListener(displayListener)
        } catch (_: Exception) {}
        if (mediaRecorder != null) toggleRecording(false)
        inferRunning.set(false); wakeInferLoop(); executor.shutdown()
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        readerThread?.quitSafely(); readerThread = null; readerHandler = null
        triggerController.shutdown()
        touchService.stopGeteventListener()
        touchService.destroyRemote()
        touchService.disconnect()
        mediaProjection?.stop()
        cleanupViews()
        try { stopForeground(true) } catch (_: Exception) {}
        super.onDestroy()
    }


    override fun onBind(intent: Intent?) = null
}
