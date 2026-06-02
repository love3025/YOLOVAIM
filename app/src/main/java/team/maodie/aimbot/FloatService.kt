package team.maodie.aimbot

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

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var captureVirtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var mediaRecorder: android.media.MediaRecorder? = null
    private var recordEnabled = false
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
    private val aimbotOn = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val rectBuffer = Array(20) { RectF() }
    private var lastDetections: List<RectF> = emptyList()
    private var centerX = 0f; private var centerY = 0f
    private var cachedRange = 0f; private var cachedRangePx = 0

    private var shizukuClient: ShizukuInjectorClient? = null
    private var currentSpeed = 0.3f; private var currentConfidence = 0.50f
    private var modelRunning = false

    // PID auto-aim state
    private var aimOffsetYRatio = 0f; private var aimSwayAmplitude = 0; private var aimPrediction = 0; private var triggerOffsetYRatio = 0f
    private var kp = 0.30f; private var ki = 0.02f; private var kd = 0.08f
    private var aimHoldEnabled = false
    private val aimingState = AimingState()

    // Bezier aim state
    private var aimMode = 0 // 0=PID, 1=Bezier
    private var bezierDuration = 30; private var bezierControlOffset = 0.3f; private var bezierRandomSpread = 0.1f
    private val bezierMover = BezierMover()

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

    // Device resolution for uinput — auto-detected by detect_touch_device() in native code.
    // Hardcoded defaults are NOT used; pass placeholder 0 values.
    private var deviceAbsMaxX = 0
    private var deviceAbsMaxY = 0

    private var triggerEnabled = false; private var triggerReactionSpeed = 100; private var triggerCooldown = 200
    private var triggerUpFluct = 3; private var triggerDownFluct = 3
    private var triggerTouchDuration = 10; private var triggerTouchRange = 100
    private var triggerShowArea = false
    private var triggerOverlay: TriggerOverlayView? = null
    private var triggerOverlayAdded = false
    private var triggerAreaX = 0; private var triggerAreaY = 0; private var lastTriggerMs = 0L
    private var triggerFired = false  // 扳机是否已射过第一发（第二发起用冷却时间）
    private val hasDetects = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate(); wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ConfigManager.init(this)
        loadConfigToService()
        createNotificationChannel(); startForeground(1, buildNotification())
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
        aimHoldEnabled = cfg.aimHoldEnabled
        aimOffsetYRatio = cfg.aimOffsetYRatio
        aimSwayAmplitude = cfg.aimSwayAmplitude
        aimPrediction = cfg.aimPrediction
        triggerOffsetYRatio = cfg.triggerOffsetYRatio
        ki = cfg.ki; kd = cfg.kd
        aimMode = cfg.aimMode
        bezierDuration = cfg.bezierDuration
        bezierControlOffset = cfg.bezierControlOffset
        bezierRandomSpread = cfg.bezierRandomSpread
        touchDisplayEnabled = cfg.aimTouchDisplay
        cachedRangePx = cfg.range.coerceIn(50, 800)
        aimbotOn.set(cfg.aimbotEnabled)
        savedAreas.clear()
        savedAreas.addAll(cfg.areas)
        JniCallBack.setConfidence(cfg.confidence)
        ProjectionHolder.selectedModelIndex = cfg.modelIndex
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            // 同步清理所有视图，避免残影
            inferRunning.set(false)
            executor.shutdown()
            cleanupViews()
            shizukuClient?.stopGeteventListener()
            shizukuClient?.destroyRemote()
            shizukuClient?.disconnect()
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
            // Try Shizuku client first (runs in root helper process with proper uinput access)
            try {
                val client = ShizukuInjectorClient(this@FloatService)
                client.connect(object : ShizukuInjectorClient.InjectorCallback {
                    override fun onConnected() {
                        shizukuClient = client
                        client.setOrientationConfig(captureW > captureH)
                        client.setResolution(captureW, captureH, deviceAbsMaxX, deviceAbsMaxY)
                        client.setInputMethod(ProjectionHolder.selectedTouchMethod)
                        updateTriggerZone()
                        Log.d(TAG, "ShizukuInjectorClient connected, resolution=${deviceAbsMaxX}x${deviceAbsMaxY}, calling init...")

                        try {
                            val initOk = client.initRemote()
                            Log.d(TAG, "RemoteInjector init: " + initOk)
                            client.startGeteventListener()
                        } catch (e: Exception) {
                            Log.e(TAG, "initRemote error: " + e.message)
                        }
                    }
                    override fun onDisconnected() {
                        shizukuClient = null
                        Log.w(TAG, "ShizukuInjectorClient disconnected")
                    }
                    override fun onError(msg: String) {
                        Log.e(TAG, "ShizukuInjectorClient error: $msg")
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "ShizukuInjectorClient init failed: ${e.message}")
            }
        }
    }

    private fun toggleRecording(enabled: Boolean) {
        if (enabled) {
            if (mediaRecorder != null) return
            try {
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                val dcimDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM)
                val outputFile = java.io.File(dcimDir, "aimbot_$timestamp.mp4")
                val mr = android.media.MediaRecorder()
                mr.setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
                mr.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                mr.setOutputFile(outputFile.absolutePath)
                mr.setVideoEncodingBitRate(20_000_000)
                mr.setVideoFrameRate(60)
                mr.setVideoSize(captureW, captureH)
                mr.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
                mr.prepare()
                val vd = mediaProjection?.createVirtualDisplay(
                    "AimbotRecord", captureW, captureH, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mr.surface, null, null
                )
                mr.start()
                mediaRecorder = mr
                recordEnabled = true
                Log.d(TAG, "Recording started: ${outputFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Recording failed", e)
                try { mediaRecorder?.release() } catch (_: Exception) {}
                mediaRecorder = null
            }
        } else {
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
            } catch (e: Exception) { Log.e(TAG, "Stop failed", e) }
            mediaRecorder = null
            recordEnabled = false
            Log.d(TAG, "Recording stopped")
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
            if (!modelFile.exists()) { assets.open(filename).use { i -> java.io.FileOutputStream(modelFile).use { o -> i.copyTo(o) } } }
            if (JniCallBack.init(modelFile.absolutePath)) {
                Log.d(TAG, "模型切换成功: $filename")
                ProjectionHolder.currentModelName = JniCallBack.getBackend()
                broadcastState(ProjectionHolder.currentState)
            } else { Log.e(TAG, "模型切换失败: $filename") }
        } catch (e: Exception) { Log.e(TAG, "模型切换异常: ${e.message}") }
        if (wasRunning) startInferLoop()
    }

    private fun toggleGui() { if (guiVisible) hideGui() else showGui() }

    private fun showGui() {
        if (guiAdded && guiVisible) {
            guiPanel.visibility = View.VISIBLE; guiPanel.alpha = 0f; guiPanel.scaleX = 0.85f; guiPanel.scaleY = 0.85f
            guiPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start(); guiVisible = true; return
        }
        // Save state from old panel before destroying
        val savedTab = if (guiAdded) guiPanel.activeTab else 0
        val savedTouchSize = if (guiAdded) guiPanel.aimTouchSize else 20
        if (guiAdded) { try { wm.removeView(guiPanel) } catch (_: Exception) {} }
        guiAdded = false
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
        guiPanel.aimHoldEnabled = cfg.aimHoldEnabled
        guiPanel.aimOffsetYRatio = cfg.aimOffsetYRatio
        guiPanel.aimSwayAmplitude = cfg.aimSwayAmplitude
        guiPanel.aimPrediction = cfg.aimPrediction
        guiPanel.triggerOffsetYRatio = cfg.triggerOffsetYRatio
        guiPanel.ki = cfg.ki; guiPanel.kd = cfg.kd
        guiPanel.aimMode = cfg.aimMode
        guiPanel.bezierDuration = cfg.bezierDuration
        guiPanel.bezierControlOffset = cfg.bezierControlOffset
        guiPanel.bezierRandomSpread = cfg.bezierRandomSpread
        guiPanel.aimTouchDisplay = cfg.aimTouchDisplay
        guiPanel.aimTouchSize = savedTouchSize
        guiPanel.modelRunning = modelRunning
        guiPanel.showCaptureRange = cfg.showCaptureRange
        guiPanel.showDetectionBox = cfg.showDetectionBox
        guiPanel.showCenterDot = cfg.showCenterDot
        guiPanel.activeTab = savedTab
        guiPanel.modelNames = ProjectionHolder.modelList.map { it.displayName }
        guiPanel.modelIndex = ProjectionHolder.selectedModelIndex
        guiPanel.onModelSelected = { idx ->
            val e = ProjectionHolder.modelList.getOrNull(idx)
            if (e != null) { ProjectionHolder.selectedModelIndex = idx; loadModel(e.filename) }
        }
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
        guiPanel.onSpeedChanged = { kp = it; currentSpeed = it; ConfigManager.updateConfig { speed = it } }
        guiPanel.onRangeChanged = { px -> overlayView.rangeRadius = px; overlayView.postInvalidate(); ConfigManager.updateConfig { range = px } }
        guiPanel.onConfidenceChanged = { currentConfidence = it; JniCallBack.setConfidence(it); ConfigManager.updateConfig { confidence = it } }
        guiPanel.onTriggerEnabled = { triggerEnabled = it; ConfigManager.updateConfig { triggerEnabled = it } }
        guiPanel.onTriggerReactionSpeed = { triggerReactionSpeed = it; ConfigManager.updateConfig { triggerReactionSpeed = it } }
        guiPanel.onTriggerCooldown = { triggerCooldown = it; ConfigManager.updateConfig { triggerCooldown = it } }
        guiPanel.onTriggerUpFluctuation = { triggerUpFluct = it; ConfigManager.updateConfig { triggerUpFluctuation = it } }
        guiPanel.onTriggerDownFluctuation = { triggerDownFluct = it; ConfigManager.updateConfig { triggerDownFluctuation = it } }
        guiPanel.onTriggerTouchDuration = { triggerTouchDuration = it; ConfigManager.updateConfig { triggerTouchDuration = it } }
        guiPanel.onTriggerTouchRange = { px -> triggerTouchRange = px; updateTriggerOverlaySize(); ConfigManager.updateConfig { triggerTouchRange = px } }
        guiPanel.onTriggerShowArea = { show -> triggerShowArea = show; if (show) setupTriggerOverlay(); updateTriggerOverlayVisibility(); ConfigManager.updateConfig { triggerShowArea = show } }
        guiPanel.onAimOffsetYRatioChanged = { aimOffsetYRatio = it; ConfigManager.updateConfig { aimOffsetYRatio = it } }
        guiPanel.onAimSwayAmplitudeChanged = { aimSwayAmplitude = it; ConfigManager.updateConfig { aimSwayAmplitude = it } }
        guiPanel.onAimPredictionChanged = { aimPrediction = it; ConfigManager.updateConfig { aimPrediction = it } }
        guiPanel.onTriggerOffsetYRatioChanged = { triggerOffsetYRatio = it; ConfigManager.updateConfig { triggerOffsetYRatio = it } }
        guiPanel.onKiChanged = { ki = it; guiPanel.ki = it; ConfigManager.updateConfig { ki = it } }
        guiPanel.onKdChanged = { kd = it; guiPanel.kd = it; ConfigManager.updateConfig { kd = it } }
        guiPanel.onAimModeChanged = { aimMode = it; ConfigManager.updateConfig { aimMode = it } }
        guiPanel.onBezierDurationChanged = { bezierDuration = it; ConfigManager.updateConfig { bezierDuration = it } }
        guiPanel.onBezierControlOffsetChanged = { bezierControlOffset = it; ConfigManager.updateConfig { bezierControlOffset = it } }
        guiPanel.onBezierRandomSpreadChanged = { bezierRandomSpread = it; ConfigManager.updateConfig { bezierRandomSpread = it } }
        guiPanel.onAimHoldEnabled = { aimHoldEnabled = it; ConfigManager.updateConfig { aimHoldEnabled = it } }
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
        guiPanel.onToggleModel = { running -> modelRunning = running; if (running && !inferRunning.get()) startInferLoop() else if (!running) { inferRunning.set(false); broadcastState(1) } }
        guiPanel.onTestCircle = {
            mainHandler.post {
                Thread {
                    val cx = screenWidth / 2; val cy = screenHeight / 2
                    val radius = 200; val steps = 72
                    val aspect = screenWidth.toFloat() / screenHeight.toFloat()
                    shizukuClient?.swipe(cx, cy, cx, cy, 0)
                    Thread.sleep(50)
                    for (i in 1 until steps) {
                        val angle = (i * 360.0 / steps) * Math.PI / 180.0
                        val x = (cx + radius * aspect * Math.cos(angle)).toInt()
                        val y = (cy + radius * Math.sin(angle)).toInt()
                        shizukuClient?.moveTo(x, y)
                        Thread.sleep(20)
                    }
                    shizukuClient?.lift()
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
        shizukuClient?.setTriggerZone(zone.x, zone.y, zone.right, zone.bottom)
        Log.d(TAG, "updateTriggerZone: (${zone.x},${zone.y})-(${zone.right},${zone.bottom})")
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
                if (++aliveCtr % 30 == 0) { Log.d(TAG, "alive trigger=$triggerEnabled shizuku=${shizukuClient?.isConnected()} detects=${hasDetects.get()}") }
                val currentRange = guiPanel.range
                if (currentRange != cachedRangePx) { cachedRangePx = currentRange; cachedRange = currentRange.toFloat() }
                val image = imageReader?.acquireLatestImage()
                if (image == null) { Thread.yield(); continue }
                try {
                    hasDetects.set(false)
                    val plane = image.planes[0]; val buffer = plane.buffer
                    val regionW = cachedRangePx * 2; val regionH = cachedRangePx * 2
                    val offsetX = (captureW - regionW) / 2; val offsetY = (captureH - regionH) / 2

                    val result = JniCallBack.detect(buffer, offsetX, offsetY, regionW, regionH, captureW, captureH, plane.rowStride, plane.pixelStride)

                    if (result != null) {
                        val count = result.size / 6; var rectCount = 0; var i = 0
                        while (i < count && rectCount < rectBuffer.size) {
                            rectBuffer[rectCount].set(result[i*6+2]*captureW, result[i*6+3]*captureH, result[i*6+4]*captureW, result[i*6+5]*captureH)
                            rectCount++; i++
                        }
                        hasDetects.set(rectCount > 0)
                        lastDetections = rectBuffer.take(rectCount)
                        mainHandler.post { overlayView.updateDetections(lastDetections) }

                        // 按住激发: 物理手指按在触发区时才能自瞄
                        val holdToAimActive = if (aimHoldEnabled) shizukuClient?.isFingerInTriggerZone() ?: false else true

                        if (aimbotOn.get() && rectCount > 0 && holdToAimActive) {
                            val target = selectTarget(rectBuffer, rectCount, centerX, centerY)
                            if (target != null) {
                                val tcx = target.centerX(); val tcy = target.centerY()
                                var boxH = 0f; var minD = Float.MAX_VALUE
                                for (j in 0 until rectCount) {
                                    val r = rectBuffer[j]
                                    val d = (r.centerX() - tcx).let { it * it } + (r.centerY() - tcy).let { it * it }
                                    if (d < minD) { minD = d; boxH = r.height() }
                                }
                                aimingState.updateVelocity(tcx, tcy)
                                var aimX = tcx
                                var aimY = tcy - boxH * aimOffsetYRatio
                                if (aimPrediction > 0) {
                                    aimX += aimingState.smoothVelX * aimPrediction
                                    aimY += aimingState.smoothVelY * aimPrediction
                                }
                                executeAiming(aimX, aimY, centerX, centerY)
                            }
                        }
                    } else if (aimingState.pointerDown) {
                        shizukuClient?.lift()
                        aimingState.pointerDown = false
                        aimingState.lockedTarget = null
                    }

                    // detection-based trigger: center in any detection box
                    val triggerAvailable = shizukuClient?.isConnected() == true
                    if (triggerEnabled && hasDetects.get() && triggerAvailable) {
                        val cx = centerX.toInt(); val cy = centerY.toInt()
                        var onTarget = false
                        for (r in lastDetections) {
                            val extendY = r.height() * (-triggerOffsetYRatio)
                            if (cx >= r.left && cx <= r.right && cy >= r.top && cy <= r.bottom + extendY) { onTarget = true; break }
                        }
                        if (onTarget) {
                            val now = System.currentTimeMillis()
                            if (!triggerFired) {
                                // 第一发：准心进入目标时开始计时，反应速度后开枪
                                if (lastTriggerMs == 0L) lastTriggerMs = now
                                if (now - lastTriggerMs >= triggerReactionSpeed.coerceIn(10, 500)) {
                                    triggerFired = true
                                    lastTriggerMs = now
                                    fireTriggerTap()
                                }
                            } else {
                                // 第二发起：使用冷却时间
                                val cd = triggerCooldown.coerceIn(10, 1000)
                                if (now - lastTriggerMs >= cd) {
                                    lastTriggerMs = now
                                    fireTriggerTap()
                                }
                            }
                        } else {
                            // 准心离开目标，重置扳机状态
                            triggerFired = false
                            lastTriggerMs = 0L
                        }
                    }


                    if (result == null) { hasDetects.set(false); lastDetections = emptyList(); mainHandler.post { overlayView.updateDetections(lastDetections) } }
                } catch (e: Exception) { Log.e(TAG, "推理帧异常: ${e.message}") }
                finally { image.close() }
            }
            inferRunning.set(false)
        }
    }

    private fun fireTriggerTap() {
        val fireArea = savedAreas.getOrNull(AREA_INDEX_FIRE)
        if (fireArea != null) {
            val rndX = fireArea.x + (Math.random() * fireArea.width).toInt()
            val rndY = fireArea.y + (Math.random() * fireArea.height).toInt()
            Log.d(TAG, "trigger fire! area=(${fireArea.x},${fireArea.y} ${fireArea.width}x${fireArea.height}) tap=($rndX,$rndY)")
            shizukuClient?.triggerTap(rndX, rndY, triggerTouchDuration.coerceIn(1, 50))
        } else {
            val size = dp(triggerTouchRange.coerceAtLeast(30))
            val px = size / 2
            val cx = triggerAreaX + size / 2
            val cy = triggerAreaY + size / 2
            val rndX = cx + ((Math.random() - 0.5) * 2 * px).toInt()
            val rndY = cy + ((Math.random() - 0.5) * 2 * px).toInt()
            Log.d(TAG, "trigger fire (legacy)! tap=($rndX,$rndY)")
            shizukuClient?.triggerTap(rndX, rndY, triggerTouchDuration.coerceIn(1, 50))
        }
    }

    // SelectTarget: finds locked target with hysteresis, or picks closest to crosshair
    private fun selectTarget(rects: Array<RectF>, rectCount: Int, cx: Float, cy: Float): RectF? {
        val lock = aimingState.lockedTarget
        if (lock != null) {
            val lockCx = lock.centerX(); val lockCy = lock.centerY()
            var minDist = Float.MAX_VALUE; var bx = 0f; var by = 0f
            for (i in 0 until rectCount) {
                val r = rects[i]
                val bcx = (r.left + r.right) * 0.5f
                val bcy = (r.top + r.bottom) * 0.5f
                val d = (bcx - lockCx) * (bcx - lockCx) + (bcy - lockCy) * (bcy - lockCy)
                if (d < minDist) { minDist = d; bx = bcx; by = bcy }
            }
            if (minDist < 22500f) {
                lock.set(bx, by, bx, by)
                return lock
            }
            aimingState.lockedTarget = null
        }
        // Pick closest to crosshair
        var bestDistSq = Float.MAX_VALUE; var bestX = cx; var bestY = cy
        for (i in 0 until rectCount) {
            val r = rects[i]
            val bcx = (r.left + r.right) * 0.5f
            val bcy = (r.top + r.bottom) * 0.5f
            val d = (bcx - cx) * (bcx - cx) + (bcy - cy) * (bcy - cy)
            if (d < bestDistSq) { bestDistSq = d; bestX = bcx; bestY = bcy }
        }
        aimingState.lockedTarget = android.graphics.RectF(bestX, bestY, bestX, bestY)
        return aimingState.lockedTarget
    }

    // ExecuteAiming: PID or Bezier controller that drags virtual finger to target
    private fun executeAiming(targetX: Float, targetY: Float, cx: Float, cy: Float) {
        if (aimMode == 1) {
            executeAimingBezier(targetX, targetY, cx, cy)
        } else {
            executeAimingPid(targetX, targetY, cx, cy)
        }
    }

    private fun executeAimingBezier(targetX: Float, targetY: Float, cx: Float, cy: Float) {
        val errorX = targetX - cx
        val errorY = targetY - cy
        val convergeThresh = 10f

        if (!aimingState.pointerDown) {
            if (Math.abs(errorX) < convergeThresh && Math.abs(errorY) < convergeThresh) return

            val aimArea = savedAreas.getOrNull(AREA_INDEX_AIM)
            if (aimArea != null) {
                aimingState.centerX = aimArea.x + (Math.random() * aimArea.width).toFloat()
                aimingState.centerY = aimArea.y + (Math.random() * aimArea.height).toFloat()
            } else {
                aimingState.centerX = cx; aimingState.centerY = cy
            }
            aimingState.startX = aimingState.centerX; aimingState.startY = aimingState.centerY

            shizukuClient?.swipe(aimingState.centerX.toInt(), aimingState.centerY.toInt(), aimingState.centerX.toInt(), aimingState.centerY.toInt(), 0)
            aimingState.pointerDown = true
            val now = System.currentTimeMillis()
            val dist = Math.sqrt((errorX * errorX + errorY * errorY).toDouble()).toFloat()
            val duration = (bezierDuration * 5 + dist * 0.3f).toInt().coerceIn(200, 800)
            bezierMover.start(now, now + duration)
        } else {
            if (Math.abs(errorX) < convergeThresh && Math.abs(errorY) < convergeThresh) {
                shizukuClient?.lift()
                aimingState.pointerDown = false
                aimingState.lockedTarget = null
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
            aimingState.centerX += moveX; aimingState.centerY += moveY
            if (applyDragSafety()) return
            shizukuClient?.moveTo(aimingState.centerX.toInt(), aimingState.centerY.toInt())
        }
    }

    private fun executeAimingPid(targetX: Float, targetY: Float, cx: Float, cy: Float) {
        val errorX = targetX - cx
        val errorY = targetY - cy
        val convergeThresh = 10f
        if (!aimingState.pointerDown) {
            if (Math.abs(errorX) < convergeThresh && Math.abs(errorY) < convergeThresh) return
            val aimArea = savedAreas.getOrNull(AREA_INDEX_AIM)
            if (aimArea != null) {
                aimingState.centerX = aimArea.x + (Math.random() * aimArea.width).toFloat()
                aimingState.centerY = aimArea.y + (Math.random() * aimArea.height).toFloat()
            } else {
                aimingState.centerX = cx; aimingState.centerY = cy
            }
            aimingState.startX = aimingState.centerX; aimingState.startY = aimingState.centerY
            aimingState.prevErrorX = 0f; aimingState.prevErrorY = 0f
            aimingState.integralX = 0f; aimingState.integralY = 0f
            shizukuClient?.swipe(aimingState.centerX.toInt(), aimingState.centerY.toInt(), aimingState.centerX.toInt(), aimingState.centerY.toInt(), 0)
            aimingState.pointerDown = true
            Log.d(TAG, "aim DOWN at (${aimingState.centerX}, ${aimingState.centerY}) target=($targetX, $targetY)")
        } else {
            if (Math.abs(errorX) < convergeThresh && Math.abs(errorY) < convergeThresh) {
                shizukuClient?.lift()
                aimingState.pointerDown = false
                aimingState.lockedTarget = null
                Log.d(TAG, "aim converged error=($errorX, $errorY)")
                return
            }
            if (errorX * aimingState.prevErrorX <= 0) aimingState.integralX = 0f
            if (errorY * aimingState.prevErrorY <= 0) aimingState.integralY = 0f
            aimingState.integralX += errorX; aimingState.integralY += errorY
            val integralLimit = 100f
            aimingState.integralX = aimingState.integralX.coerceIn(-integralLimit, integralLimit)
            aimingState.integralY = aimingState.integralY.coerceIn(-integralLimit, integralLimit)
            val derivX = errorX - aimingState.prevErrorX
            val derivY = errorY - aimingState.prevErrorY
            var moveX = errorX * kp + aimingState.integralX * ki + derivX * kd
            var moveY = errorY * kp + aimingState.integralY * ki + derivY * kd
            if (aimSwayAmplitude > 0) moveY += computeSway()
            aimingState.prevErrorX = errorX; aimingState.prevErrorY = errorY
            val maxPerFrame = 600f
            val moveDist = Math.sqrt((moveX * moveX + moveY * moveY).toDouble()).toFloat()
            if (moveDist > maxPerFrame) {
                moveX = moveX / moveDist * maxPerFrame
                moveY = moveY / moveDist * maxPerFrame
            }
            aimingState.centerX += moveX; aimingState.centerY += moveY
            if (applyDragSafety()) return
            shizukuClient?.moveTo(aimingState.centerX.toInt(), aimingState.centerY.toInt())
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
            shizukuClient?.lift()
            aimingState.pointerDown = false
            aimingState.lockedTarget = null
            bezierMover.cancel()
            Log.d(TAG, "aim edge lift at (${aimingState.centerX}, ${aimingState.centerY}) drag=$dragDist")
            return true
        }
        return false
    }

    private fun makeParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val ch = NotificationChannel(CH_ID, "Aimbot", NotificationManager.IMPORTANCE_LOW); (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch) } }
    private fun buildNotification() = NotificationCompat.Builder(this, CH_ID).setContentTitle("Aimbot").setContentText("运行中").setSmallIcon(android.R.drawable.ic_menu_view).build()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "orientation changed: display=${screenWidth}x${screenHeight} capture=${captureW}x${captureH}")
        // Use current display dimensions for orientation (captureW/h not yet updated)
        shizukuClient?.setOrientationConfig(screenWidth > screenHeight)
        // Touch resolution always uses natural orientation (captureW/captureH)
        shizukuClient?.setResolution(captureW, captureH, deviceAbsMaxX, deviceAbsMaxY)
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
            shizukuClient?.setOrientationConfig(captureW > captureH)
            shizukuClient?.setResolution(captureW, captureH, deviceAbsMaxX, deviceAbsMaxY)
            centerX = captureW / 2f; centerY = captureH / 2f
            if (wasRunning) startInferLoop()
        }
    }

    override fun onDestroy() {
        inferRunning.set(false); executor.shutdown()
        shizukuClient?.stopGeteventListener()
        shizukuClient?.destroyRemote()
        shizukuClient?.disconnect()
        mediaProjection?.stop()
        cleanupViews()
        try { stopForeground(true) } catch (_: Exception) {}
        super.onDestroy()
    }

    
    override fun onBind(intent: Intent?) = null
}
