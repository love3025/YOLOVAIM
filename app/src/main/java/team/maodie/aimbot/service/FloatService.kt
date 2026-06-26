package team.maodie.aimbot.service
import team.maodie.aimbot.model.DetectionInfo

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
import team.maodie.aimbot.controller.AimController
import team.maodie.aimbot.controller.TriggerController
import team.maodie.aimbot.manager.InferenceManager
import team.maodie.aimbot.manager.OverlayManager
import team.maodie.aimbot.manager.ConfigManager
import team.maodie.aimbot.view.FloatBallView
import team.maodie.aimbot.view.OverlayCanvasView
import team.maodie.aimbot.view.GuiPanelView
import team.maodie.aimbot.view.TriggerOverlayView
import team.maodie.aimbot.view.TouchDisplayView
import team.maodie.aimbot.view.AreaSettingsView
import team.maodie.aimbot.model.AimingState
import team.maodie.aimbot.model.AreaConfig
import team.maodie.aimbot.model.BezierMover
import team.maodie.aimbot.injector.InjectorCallback
import team.maodie.aimbot.inference.JniCallBack
import team.maodie.aimbot.util.ProjectionHolder

class FloatService : Service() {

    companion object { const val TAG = "FloatService"; const val CH_ID = "aimbot_ch" }

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
    private var kp = 0.07f; private var ki = 0.001f; private var kd = 0.05f; private var kf = 0.05f
    private var aimHoldEnabled = false
    private var recoilEnabled = false; private var recoilStrength = 0.5f
    private val aimingState = AimingState()

    // Bezier aim state
    private var aimMode = 0 // 0=PID, 1=Bezier
    private var bezierDuration = 30; private var bezierControlOffset = 0.3f; private var bezierRandomSpread = 0.1f
    private val bezierMover = BezierMover()
    private var convergeThresh = 10f

    // Hold-to-fire (按住激发) state — uses trigger slot, separate from aim slot

    // Touch display overlay
    private var touchDisplayEnabled = false
    private var touchDisplayView: TouchDisplayView? = null
    private var touchDisplayAdded = false

    // Area settings overlay
    private var areaSettingsView: AreaSettingsView? = null
    private var areaSettingsAdded = false
    private val savedAreas = mutableListOf<AreaConfig>()

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

    override fun onCreate() {
        super.onCreate(); wm = getSystemService(WINDOW_SERVICE) as WindowManager
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
            context = this,
            wm = wm,
            touchClient = { touchService },
            savedAreas = { savedAreas },
            screenWidth = { screenWidth },
            screenHeight = { screenHeight },
            dp = { dp(it) }
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

        // Load config to controllers
        loadConfigToControllers()
    }

    private fun loadConfigToControllers() {
        // AimController
        aimController.kp = kp
        aimController.ki = ki
        aimController.kd = kd
        aimController.kf = kf
        aimController.aimMode = aimMode
        aimController.bezierDuration = bezierDuration
        aimController.bezierControlOffset = bezierControlOffset
        aimController.bezierRandomSpread = bezierRandomSpread
        aimController.convergeThresh = convergeThresh
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

        // TriggerController
        triggerController.triggerEnabled = triggerEnabled
        triggerController.triggerReactionSpeed = triggerReactionSpeed
        triggerController.triggerCooldown = triggerCooldown
        triggerController.triggerUpFluct = triggerUpFluct
        triggerController.triggerDownFluct = triggerDownFluct
        triggerController.triggerTouchDuration = triggerTouchDuration
        triggerController.triggerTouchRange = triggerTouchRange
        triggerController.triggerShowArea = triggerShowArea
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
        triggerShowArea = cfg.triggerShowArea
        autoStopEnabled = cfg.autoStopEnabled
        aimHoldEnabled = cfg.aimHoldEnabled
        aimOffsetYRatio = cfg.aimOffsetYRatio
        aimSwayAmplitude = cfg.aimSwayAmplitude
        aimPrediction = cfg.aimPrediction
        triggerOffsetYRatio = cfg.triggerOffsetYRatio
        recoilEnabled = cfg.recoilEnabled
        recoilStrength = cfg.recoilStrength
        ki = cfg.ki; kd = cfg.kd; kf = cfg.kf
        aimMode = cfg.aimMode
        bezierDuration = cfg.bezierDuration
        bezierControlOffset = cfg.bezierControlOffset
        bezierRandomSpread = cfg.bezierRandomSpread
        convergeThresh = cfg.convergeThresh.toFloat()
        touchDisplayEnabled = cfg.aimTouchDisplay
        cachedRangePx = cfg.range.coerceIn(50, 800)
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
        // 确保有4个区域（兼容旧配置）
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
            inferRunning.set(false)
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
        ProjectionHolder.updateState(1, JniCallBack.getBackend())
        return START_NOT_STICKY
    }

    private fun broadcastState(state: Int, modelName: String? = null) {
        ProjectionHolder.updateState(state, modelName ?: ProjectionHolder.currentModelName)
    }

    private fun cleanupViews() {
        try { if (ballAdded) { wm.removeView(ballView); ballAdded = false } } catch (_: Exception) {}
        try { if (overlayAdded) { wm.removeView(overlayView); overlayAdded = false } } catch (_: Exception) {}
        try { if (guiAdded) { wm.removeView(guiPanel); guiAdded = false; guiVisible = false } } catch (_: Exception) {}
        try { if (triggerOverlayAdded) { wm.removeView(triggerOverlay); triggerOverlayAdded = false } } catch (_: Exception) {}
        try { if (touchDisplayAdded) { wm.removeView(touchDisplayView); touchDisplayAdded = false } } catch (_: Exception) {}
        try { if (areaSettingsAdded) { wm.removeView(areaSettingsView); areaSettingsAdded = false } } catch (_: Exception) {}
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
        overlayView.rangeRadius = cfg.range.coerceIn(50, 800)
        overlayParams = makeParams(MATCH_PARENT, MATCH_PARENT, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        wm.addView(overlayView, overlayParams); overlayAdded = true
    }

    private fun initTouchInjector() {
        executor.execute {
            touchService.connect(object : InjectorCallback {
                override fun onConnected() {
                    touchService.setOrientationConfig(captureW > captureH)
                    touchService.setResolution(captureW, captureH, deviceAbsMaxX, deviceAbsMaxY)
                    touchService.setInputMethod(ProjectionHolder.selectedTouchMethod)
                    updateTriggerZone()
                    updateFireZone()
                    updateJoystickZone()
                    Log.d(TAG, "TouchInjector connected, resolution=${deviceAbsMaxX}x${deviceAbsMaxY}, calling init...")
                    try {
                        val initOk = touchService.initRemote()
                        Log.d(TAG, "RemoteInjector init: $initOk")
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
        }
    }

    /** 重新连接触摸注入器 — 直接在当前线程发起连接，不提交到 executor 队列 */
    private fun reconnectTouchInline() {
        val method = ProjectionHolder.selectedTouchMethod
        Log.d(TAG, "reconnectTouchInline: 连接 $method")
        touchService.connect(object : InjectorCallback {
            override fun onConnected() {
                Log.d(TAG, "RECONNECT: $method 已连接, 配置中...")
                touchService.setOrientationConfig(captureW > captureH)
                touchService.setResolution(captureW, captureH, deviceAbsMaxX, deviceAbsMaxY)
                touchService.setInputMethod(method)
                updateTriggerZone()
                updateFireZone()
                updateJoystickZone()
                try {
                    val initOk = touchService.initRemote()
                    Log.d(TAG, "RECONNECT: initRemote=$initOk")
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
                val outputFile = java.io.File(dir, "Aimbot_${timestamp}_$random.mp4")
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
            if (!modelRunning) { inferRunning.set(false); broadcastState(1) }
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
        try { executor.submit { }.get() } catch (_: Exception) {}
        JniCallBack.release()
        val modelFile = java.io.File(applicationContext.filesDir, filename)
        try {
            val qnnCache = java.io.File(applicationContext.cacheDir, "qnn")
            if (qnnCache.exists()) qnnCache.deleteRecursively()
            qnnCache.mkdirs()
            if (!modelFile.exists()) {
                modelFile.parentFile?.mkdirs()
                assets.open(filename).use { i -> java.io.FileOutputStream(modelFile).use { o -> i.copyTo(o) } }
            }
            // For NCNN models, also copy the .bin file
            if (filename.endsWith(".param")) {
                val binFilename = filename.replace(".param", ".bin")
                val binFile = java.io.File(applicationContext.filesDir, binFilename)
                if (!binFile.exists()) {
                    assets.open(binFilename).use { i -> java.io.FileOutputStream(binFile).use { o -> i.copyTo(o) } }
                }
            }
            val cfg = ConfigManager.getConfig()
            val useCpu = cfg.useCpuInference
            Log.d(TAG, "loadModel: useCpuInference=$useCpu, threads=${cfg.cpuThreadCount}")
            JniCallBack.setForceCpu(useCpu)
            JniCallBack.setCpuThreads(cfg.cpuThreadCount)
            // Set input size for NCNN models (TFLite reads from model automatically)
            val entry = ProjectionHolder.modelList.find { it.filename == filename }
            if (entry != null) {
                JniCallBack.setInputSize(entry.inputSize, entry.inputSize)
            }
            if (JniCallBack.init(modelFile.absolutePath)) {
                val backend = JniCallBack.getBackend()
                Log.d(TAG, "模型切换成功: $filename, 后端=$backend")
                ProjectionHolder.currentModelName = backend
                // Load classes map from ProjectionHolder
                val entry = ProjectionHolder.modelList.find { it.filename == filename }
                currentClasses = entry?.classes ?: emptyMap()
                // 模型切换时更新类别选择：保留仍存在的类别，新增的自动选中
                if (currentClasses.isNotEmpty()) {
                    val validIds = currentClasses.keys
                    aimClasses = aimClasses.filter { it in validIds }.toMutableSet()
                    if (aimClasses.isEmpty()) aimClasses = validIds.toMutableSet()
                    triggerClasses = triggerClasses.filter { it in validIds }.toMutableSet()
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
            guiPanel.buildUI()
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
        guiPanel.triggerShowArea = cfg.triggerShowArea
        guiPanel.autoStopEnabled = cfg.autoStopEnabled
        guiPanel.aimHoldEnabled = cfg.aimHoldEnabled
        guiPanel.recoilEnabled = cfg.recoilEnabled
        guiPanel.recoilStrength = cfg.recoilStrength
        guiPanel.aimOffsetYRatio = cfg.aimOffsetYRatio
        guiPanel.aimSwayAmplitude = cfg.aimSwayAmplitude
        guiPanel.aimPrediction = cfg.aimPrediction
        guiPanel.triggerOffsetYRatio = cfg.triggerOffsetYRatio
        guiPanel.ki = cfg.ki; guiPanel.kd = cfg.kd; guiPanel.kf = cfg.kf
        guiPanel.aimMode = cfg.aimMode
        guiPanel.bezierDuration = cfg.bezierDuration
        guiPanel.bezierControlOffset = cfg.bezierControlOffset
        guiPanel.bezierRandomSpread = cfg.bezierRandomSpread
        guiPanel.convergeThresh = cfg.convergeThresh
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
            if (e != null) { ProjectionHolder.notifyModelIndexChanged(idx); lastModelIndex = idx; loadModel(e.filename) }
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
        val panelH = (screenHeight * 0.68f).toInt()
        guiParams = makeParams((280 * resources.displayMetrics.density).toInt(), panelH, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL).apply { gravity = Gravity.TOP or Gravity.START; x = 60; y = 200 }
        guiPanel.onClose = { hideGui() }
        guiPanel.onEnabledChanged = { on ->
            aimbotOn.set(on)
            overlayView.aimbotEnabled = on
            ConfigManager.updateConfig { aimbotEnabled = on }
            if (on) { aimingState.maxDragDist = (screenWidth.coerceAtMost(screenHeight) * 0.2f).coerceIn(100f, 600f) }
            Log.d("AimbotInfer", "开关切换: $on")
        }
        guiPanel.onSpeedChanged = { kp = it; currentSpeed = it; aimController.kp = it; ConfigManager.updateConfig { speed = it } }
        guiPanel.onRangeChanged = { px -> overlayView.rangeRadius = px; overlayView.postInvalidate(); ConfigManager.updateConfig { range = px } }
        guiPanel.onConfidenceChanged = { currentConfidence = it; JniCallBack.setConfidence(it); ConfigManager.updateConfig { confidence = it } }
        guiPanel.onTriggerEnabled = { triggerEnabled = it; triggerController.triggerEnabled = it; ConfigManager.updateConfig { triggerEnabled = it } }
        guiPanel.onTriggerReactionSpeed = { triggerReactionSpeed = it; triggerController.triggerReactionSpeed = it; ConfigManager.updateConfig { triggerReactionSpeed = it } }
        guiPanel.onTriggerCooldown = { triggerCooldown = it; triggerController.triggerCooldown = it; ConfigManager.updateConfig { triggerCooldown = it } }
        guiPanel.onTriggerUpFluctuation = { triggerUpFluct = it; triggerController.triggerUpFluct = it; ConfigManager.updateConfig { triggerUpFluctuation = it } }
        guiPanel.onTriggerDownFluctuation = { triggerDownFluct = it; triggerController.triggerDownFluct = it; ConfigManager.updateConfig { triggerDownFluctuation = it } }
        guiPanel.onTriggerTouchDuration = { triggerTouchDuration = it; triggerController.triggerTouchDuration = it; ConfigManager.updateConfig { triggerTouchDuration = it } }
        guiPanel.onTriggerTouchRange = { px -> triggerTouchRange = px; triggerController.triggerTouchRange = px; updateTriggerOverlaySize(); triggerController.updateTriggerOverlaySize(); ConfigManager.updateConfig { triggerTouchRange = px } }
        guiPanel.onTriggerShowArea = { show -> triggerShowArea = show; triggerController.triggerShowArea = show; if (show) setupTriggerOverlay(); updateTriggerOverlayVisibility(); triggerController.updateTriggerOverlayVisibility(); ConfigManager.updateConfig { triggerShowArea = show } }
        guiPanel.onAutoStopEnabledChanged = { autoStopEnabled = it; triggerController.autoStopEnabled = it; ConfigManager.updateConfig { autoStopEnabled = it } }
        guiPanel.onAimOffsetYRatioChanged = { aimOffsetYRatio = it; aimController.aimOffsetYRatio = it; ConfigManager.updateConfig { aimOffsetYRatio = it } }
        guiPanel.onAimSwayAmplitudeChanged = { aimSwayAmplitude = it; aimController.aimSwayAmplitude = it; ConfigManager.updateConfig { aimSwayAmplitude = it } }
        guiPanel.onAimPredictionChanged = { aimPrediction = it; aimController.aimPrediction = it; ConfigManager.updateConfig { aimPrediction = it } }
        guiPanel.onTriggerOffsetYRatioChanged = { triggerOffsetYRatio = it; triggerController.triggerOffsetYRatio = it; ConfigManager.updateConfig { triggerOffsetYRatio = it } }
        guiPanel.onKiChanged = { ki = it; guiPanel.ki = it; aimController.ki = it; ConfigManager.updateConfig { ki = it } }
        guiPanel.onKdChanged = { kd = it; guiPanel.kd = it; aimController.kd = it; ConfigManager.updateConfig { kd = it } }
        guiPanel.onKfChanged = { kf = it; guiPanel.kf = it; aimController.kf = it; ConfigManager.updateConfig { kf = it } }
        guiPanel.onAimModeChanged = { aimMode = it; aimController.aimMode = it; ConfigManager.updateConfig { aimMode = it } }
        guiPanel.onBezierDurationChanged = { bezierDuration = it; aimController.bezierDuration = it; ConfigManager.updateConfig { bezierDuration = it } }
        guiPanel.onBezierControlOffsetChanged = { bezierControlOffset = it; aimController.bezierControlOffset = it; ConfigManager.updateConfig { bezierControlOffset = it } }
        guiPanel.onBezierRandomSpreadChanged = { bezierRandomSpread = it; aimController.bezierRandomSpread = it; ConfigManager.updateConfig { bezierRandomSpread = it } }
        guiPanel.onConvergeThreshChanged = { convergeThresh = it.toFloat(); aimController.convergeThresh = it.toFloat(); ConfigManager.updateConfig { convergeThresh = it } }
        guiPanel.onAimHoldEnabled = { aimHoldEnabled = it; aimController.aimHoldEnabled = it; ConfigManager.updateConfig { aimHoldEnabled = it } }
        guiPanel.onRecoilEnabledChanged = { recoilEnabled = it; aimController.recoilEnabled = it; ConfigManager.updateConfig { recoilEnabled = it } }
        guiPanel.onRecoilStrengthChanged = { recoilStrength = it; aimController.recoilStrength = it; ConfigManager.updateConfig { recoilStrength = it } }
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
            overlayView.postInvalidate()
            ConfigManager.updateConfig { showCaptureRange = on }
        }
        guiPanel.onShowDetectionBoxChanged = { on ->
            overlayView.showDetectionBox = on
            overlayView.postInvalidate()
            ConfigManager.updateConfig { showDetectionBox = on }
        }
        guiPanel.onShowCenterDotChanged = { on ->
            overlayView.showCenterDot = on
            overlayView.postInvalidate()
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
                    inferRunning.set(false)
                    broadcastState(1)
                }
            }
        }
        guiPanel.onTestCircle = {
            mainHandler.post {
                Thread {
                    val cx = screenWidth / 2; val cy = screenHeight / 2
                    val radius = 200; val steps = 72
                    val aspect = screenWidth.toFloat() / screenHeight.toFloat()
                    touchService.swipe(cx, cy, cx, cy, 0)
                    Thread.sleep(50)
                    for (i in 1 until steps) {
                        val angle = (i * 360.0 / steps) * Math.PI / 180.0
                        val x = (cx + radius * aspect * Math.cos(angle)).toInt()
                        val y = (cy + radius * Math.sin(angle)).toInt()
                        touchService.moveTo(x, y)
                        Thread.sleep(20)
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
        val zone = savedAreas.getOrNull(AREA_INDEX_TRIGGER) ?: return
        touchService.setTriggerZone(zone.x, zone.y, zone.right, zone.bottom)
        Log.d(TAG, "updateTriggerZone: (${zone.x},${zone.y})-(${zone.right},${zone.bottom})")
    }

    private fun updateFireZone() {
        val zone = savedAreas.getOrNull(AREA_INDEX_FIRE) ?: return
        touchService.setFireZone(zone.x, zone.y, zone.right, zone.bottom)
        Log.d(TAG, "updateFireZone: (${zone.x},${zone.y})-(${zone.right},${zone.bottom})")
    }

    private fun updateJoystickZone() {
        val zone = savedAreas.getOrNull(AREA_INDEX_JOYSTICK) ?: return
        touchService.setJoystickZone(zone.x, zone.y, zone.right, zone.bottom)
        Log.d(TAG, "updateJoystickZone: (${zone.x},${zone.y})-(${zone.right},${zone.bottom})")
    }

    private fun setupImageReader() {
        captureW = screenWidth; captureH = screenHeight
        Log.d(TAG, "setupImageReader: w=${captureW} h=${captureH}")

        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Log.d("AimbotInfer", "MediaProjection 停止"); inferRunning.set(false); imageReader?.close() }
        }, Handler(Looper.getMainLooper()))
        captureVirtualDisplay = mediaProjection?.createVirtualDisplay("AimbotCapture", captureW, captureH, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, null)
    }

    private fun startInferLoop() {
        if (inferRunning.getAndSet(true)) { Log.d(TAG, "infer loop already running"); return }
        broadcastState(2) // INFERENCING
        centerX = captureW / 2f; centerY = captureH / 2f
        Log.d(TAG, "infer loop started, center=($centerX,$centerY)")
        executor.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            var aliveCtr = 0
            while (inferRunning.get()) {
                if (++aliveCtr % 30 == 0) { Log.d(TAG, "alive trigger=$triggerEnabled connected=${touchService.isConnected()} detects=${hasDetects.get()}") }
                val currentRange = guiPanel.range
                if (currentRange != cachedRangePx) { cachedRangePx = currentRange; cachedRange = currentRange.toFloat() }
                val image = imageReader?.acquireLatestImage()
                if (image == null) { Thread.yield(); continue }
                val hwBuf = image.hardwareBuffer
                try {
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
                    val plane = image.planes[0]; val buffer = plane.buffer
                    val regionW = cachedRangePx * 2; val regionH = cachedRangePx * 2
                    val offsetX = (captureW - regionW) / 2; val offsetY = (captureH - regionH) / 2

                    val result = JniCallBack.detect(buffer, offsetX, offsetY, regionW, regionH, captureW, captureH, plane.rowStride, plane.pixelStride)

                    if (result != null) {
                        val count = result.size / 6
                        if (count > 0) {
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
                        mainHandler.post { overlayView.updateDetections(lastDetections) }

                        if (autoSaveDataset && detCount > 0 && hwBuf != null) {
                            saveDatasetFrame(hwBuf, result, count)
                        }

                        // 按住激发: 物理手指按在触发区时才能自瞄
                        val holdToAimActive = if (aimHoldEnabled) touchService.isFingerInTriggerZone() else true

                        // Filter detections by aimClasses
                        val aimDets = if (aimClasses.isEmpty()) lastDetections
                            else lastDetections.filter { it.classId in aimClasses }

                        if (aimbotOn.get() && aimDets.isNotEmpty() && holdToAimActive) {
                            val target = aimController.selectTarget(aimDets, centerX, centerY)
                            if (target != null) {
                                val tcx = target.rect.centerX(); val tcy = target.rect.centerY()
                                var boxH = 0f; var minD = Float.MAX_VALUE
                                for (det in aimDets) {
                                    val r = det.rect
                                    val d = (r.centerX() - tcx).let { it * it } + (r.centerY() - tcy).let { it * it }
                                    if (d < minD) { minD = d; boxH = r.height() }
                                }
                                val classOffset = aimController.classAimOffsets[target.classId] ?: aimController.aimOffsetYRatio
                                val classBoxRatio = aimController.classBoxAimRatios[target.classId] ?: aimController.boxAimRatio
                                val aimX = tcx
                                val aimY = (tcy - boxH * 0.5f) + boxH * (1f - classBoxRatio) - boxH * classOffset
                                aimController.aimingState.updateVelocity(tcx, tcy)
                                aimController.executeAiming(aimX, aimY, centerX, centerY)
                            }
                        }
                    } else if (aimController.aimingState.pointerDown) {
                        aimController.lift()
                    }

                    // detection-based trigger: center in any detection box (filtered by aimClasses)
                    triggerController.processTrigger(lastDetections, centerX, centerY, hasDetects.get())
                    // 压枪：每帧更新扳机状态（支持手动开火 + 自动扳机）
                    aimController.triggerHeld = touchService.isFingerInFireZone() || triggerController.triggerFired

                    if (result == null) { hasDetects.set(false); lastDetections = emptyList(); mainHandler.post { overlayView.updateDetections(lastDetections) } }
                } catch (e: Exception) { Log.e(TAG, "推理帧异常: ${e.message}") }
                finally { hwBuf?.close(); image.close() }
            }
            inferRunning.set(false)
        }
    }

    private fun makeParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val ch = NotificationChannel(CH_ID, "Aimbot", NotificationManager.IMPORTANCE_LOW); (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch) } }
    private fun buildNotification() = NotificationCompat.Builder(this, CH_ID).setContentTitle("Aimbot").setContentText("运行中").setSmallIcon(android.R.drawable.ic_menu_view).build()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "orientation changed: display=${screenWidth}x${screenHeight} capture=${captureW}x${captureH}")
        // Use current display dimensions for orientation and resolution
        touchService.setOrientationConfig(screenWidth > screenHeight)
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
                p.flags = p.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                wm.updateViewLayout(overlayView, p)
            }
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
        Log.d(TAG, "restartCapture: wasRunning=$wasRunning newSize=${curW}x${curH}")
        executor.execute {
            try { Thread.sleep(200) } catch (_: Exception) {}
            // Close old reader
            val oldReader = imageReader
            imageReader = null
            try { oldReader?.close() } catch (_: Exception) {}
            // Create new reader at new size
            imageReader = ImageReader.newInstance(curW, curH, PixelFormat.RGBA_8888, 2)
            // Resize VirtualDisplay and attach new surface
            try {
                captureVirtualDisplay?.resize(curW, curH, screenDensity)
                captureVirtualDisplay?.setSurface(imageReader!!.surface)
                Log.d(TAG, "restartCapture: resized to ${curW}x${curH}")
            } catch (e: Exception) {
                Log.w(TAG, "VirtualDisplay resize failed: ${e.message}")
            }
            captureW = curW; captureH = curH
            touchService.setOrientationConfig(curW > curH)
            touchService.setResolution(curW, curH, deviceAbsMaxX, deviceAbsMaxY)
            centerX = captureW / 2f; centerY = captureH / 2f
            if (wasRunning) startInferLoop()
        }
    }

    override fun onDestroy() {
        if (mediaRecorder != null) toggleRecording(false)
        inferRunning.set(false); executor.shutdown()
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
