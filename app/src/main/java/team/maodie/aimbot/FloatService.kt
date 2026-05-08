package team.maodie.aimbot

import android.app.*
import android.content.*
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
    private val screenWidth get() = resources.displayMetrics.widthPixels
    private val screenHeight get() = resources.displayMetrics.heightPixels
    private val screenDensity get() = resources.displayMetrics.densityDpi

    private val executor = Executors.newSingleThreadExecutor()
    private val inferRunning = AtomicBoolean(false)
    private val aimbotOn = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val rectBuffer = Array(20) { RectF() }
    private var lastDetections: List<RectF> = emptyList()
    private var centerX = 0f; private var centerY = 0f
    private var cachedRange = 0f; private var cachedRangePx = 0

    private var touchInjector: TouchInjector? = null
    private var shizukuClient: ShizukuInjectorClient? = null
    private var uinputInjector: UinputInjector? = null
    private var currentSpeed = 0.3f; private var currentConfidence = 0.50f

    // Device resolution for uinput (queried from real touchpanel)
    private var deviceAbsMaxX = 21199
    private var deviceAbsMaxY = 29999

    private fun queryDeviceResolution(): Pair<Int, Int> {
        try {
            val process = Runtime.getRuntime().exec("getevent -p")
            val reader = process.inputStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                if (l.contains("touchpanel") && !l.contains("Aimbot")) {
                    // Found real touchpanel, get ABS_X (0x35) and ABS_Y (0x36) ranges
                    while (reader.readLine().also { line = it } != null) {
                        val cur = line!!
                        if (!cur.contains("ABS")) break
                        // ABS_X is 0035 in hex, ABS_Y is 0036
                        if (cur.contains("0035")) {
                            val maxMatch = Regex("max\\s*(\\d+)").find(cur)
                            if (maxMatch != null) {
                                deviceAbsMaxX = maxMatch.groupValues[1].toInt()
                                Log.d(TAG, "Found ABS_X max=$deviceAbsMaxX from: $cur")
                            }
                        }
                        if (cur.contains("0036")) {
                            val maxMatch = Regex("max\\s*(\\d+)").find(cur)
                            if (maxMatch != null) {
                                deviceAbsMaxY = maxMatch.groupValues[1].toInt()
                                Log.d(TAG, "Found ABS_Y max=$deviceAbsMaxY from: $cur")
                            }
                        }
                    }
                    reader.close()
                    Log.d(TAG, "Detected device ABS: X max=$deviceAbsMaxX, Y max=$deviceAbsMaxY")
                    return Pair(deviceAbsMaxX, deviceAbsMaxY)
                }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e(TAG, "queryDeviceResolution error: ${e.message}")
        }
        return Pair(21199, 29999) // fallback
    }

    private var triggerEnabled = false; private var triggerReactionSpeed = 100f
    private var triggerUpFluct = 3; private var triggerDownFluct = 3
    private var triggerTouchDuration = 10; private var triggerTouchRange = 100
    private var triggerShowArea = false
    private var triggerOverlay: TriggerOverlayView? = null
    private var triggerOverlayAdded = false
    private var triggerAreaX = 0; private var triggerAreaY = 0; private var lastTriggerMs = 0L
    private var hasDetects = false

    override fun onCreate() {
        super.onCreate(); wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel(); startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = ProjectionHolder.resultCode; val data = ProjectionHolder.resultData
        if (data != null) {
            try {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = manager.getMediaProjection(code, data); setupImageReader()
            } catch (e: Exception) { Log.e(TAG, "projection创建失败: ${e.message}") }
        }
        setupBall(); setupOverlay(); initTouchInjector()
        return START_NOT_STICKY
    }

    private fun setupBall() {
        val size = dp(35)
        ballView = FloatBallView(this)
        ballParams = makeParams(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).apply { gravity = Gravity.TOP or Gravity.START; x = 50; y = 200 }
        ballView.onMoveCallback = { dx, dy -> ballParams?.let { it.x += dx; it.y += dy; wm.updateViewLayout(ballView, it) } }
        ballView.onClickCallback = { toggleGui() }; wm.addView(ballView, ballParams); ballAdded = true
    }

    private fun setupOverlay() {
        overlayView = OverlayCanvasView(this)
        overlayParams = makeParams(MATCH_PARENT, MATCH_PARENT, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        wm.addView(overlayView, overlayParams); overlayAdded = true
    }

    private fun initTouchInjector() {
        executor.execute {
            // Query real device resolution first
            val (devW, devH) = queryDeviceResolution()

            // Try Shizuku client first (runs in root helper process with proper uinput access)
            try {
                val client = ShizukuInjectorClient(this@FloatService)
                client.connect(object : ShizukuInjectorClient.InjectorCallback {
                    override fun onConnected() {
                        shizukuClient = client
                        // Call setResolution BEFORE init so C++ globals are set before uinput opens
                        client.setResolution(screenWidth, screenHeight, devW, devH)
                        Log.d(TAG, "ShizukuInjectorClient connected, resolution set, calling init...")

                        // Now call init to open uinput
                        try {
                            val initOk = client.initRemote()
                            Log.d(TAG, "RemoteInjector init: " + initOk)
                        } catch (e: Exception) {
                            Log.e(TAG, "initRemote error: " + e.message)
                        }
                    }
                    override fun onDisconnected() {
                        shizukuClient = null
                        Log.w(TAG, "ShizukuInjectorClient disconnected")
                    }
                    override fun onError(msg: String) {
                        Log.e(TAG, "ShizukuInjectorClient error: $msg, falling back to other methods")
                        fallbackToUinputOrInline(devW, devH)
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "ShizukuInjectorClient init failed: ${e.message}")
                fallbackToUinputOrInline(devW, devH)
            }
        }
    }

    private fun fallbackToUinputOrInline(devW: Int = 21199, devH: Int = 29999) {
        // Try UinputInjector next (direct uinput from app process)
        try {
            val uinput = UinputInjector.getInstance()
            if (uinput.init()) {
                uinputInjector = uinput
                uinput.setResolution(screenWidth, screenHeight, devW, devH)
                Log.d(TAG, "UinputInjector ready")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "UinputInjector init failed: ${e.message}")
        }

        // Fallback to inline TouchInjector
        val injector = TouchInjector()
        if (injector.init()) { touchInjector = injector; Log.d(TAG, "TouchInjector ready (inline)") }
        else { Log.w(TAG, "TouchInjector unavailable") }
    }

    private fun setupTriggerOverlay() {
        if (triggerOverlayAdded) return
        triggerOverlay = TriggerOverlayView(this)
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

    private fun loadModel(filename: String) {
        val modelFile = java.io.File(applicationContext.filesDir, filename)
        try {
            if (!modelFile.exists()) { assets.open(filename).use { i -> java.io.FileOutputStream(modelFile).use { o -> i.copyTo(o) } } }
            if (JniCallBack.init(modelFile.absolutePath)) Log.d(TAG, "模型切换成功: $filename")
            else Log.e(TAG, "模型切换失败: $filename")
        } catch (e: Exception) { Log.e(TAG, "模型切换异常: ${e.message}") }
    }

    private fun toggleGui() { if (guiVisible) hideGui() else showGui() }

    private fun showGui() {
        if (guiAdded) {
            guiPanel.visibility = View.VISIBLE; guiPanel.alpha = 0f; guiPanel.scaleX = 0.85f; guiPanel.scaleY = 0.85f
            guiPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start(); guiVisible = true; return
        }
        guiPanel = GuiPanelView(this)
        val panelH = (screenHeight * 0.68f).toInt()
        guiParams = makeParams(dp(280), panelH, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL).apply { gravity = Gravity.TOP or Gravity.START; x = 60; y = 200 }
        guiPanel.onClose = { hideGui() }
        guiPanel.onEnabledChanged = { on -> aimbotOn.set(on); overlayView.aimbotEnabled = on; Log.d("AimbotInfer", "开关切换: $on") }
        guiPanel.onSpeedChanged = { currentSpeed = it }
        guiPanel.onRangeChanged = { px -> overlayView.rangeRadius = px; overlayView.postInvalidate() }
        guiPanel.onConfidenceChanged = { currentConfidence = it; JniCallBack.setConfidence(it) }
        guiPanel.modelNames = ProjectionHolder.modelList.map { it.displayName }
        guiPanel.modelIndex = ProjectionHolder.selectedModelIndex
        guiPanel.onModelSelected = { idx ->
            val e = ProjectionHolder.modelList.getOrNull(idx)
            if (e != null) { ProjectionHolder.selectedModelIndex = idx; loadModel(e.filename) }
        }
        guiPanel.onTriggerEnabled = { triggerEnabled = it }
        guiPanel.onTriggerReactionSpeed = { triggerReactionSpeed = it }
        guiPanel.onTriggerUpFluctuation = { triggerUpFluct = it }
        guiPanel.onTriggerDownFluctuation = { triggerDownFluct = it }
        guiPanel.onTriggerTouchDuration = { triggerTouchDuration = it }
        guiPanel.onTriggerTouchRange = { px -> triggerTouchRange = px; updateTriggerOverlaySize() }
        guiPanel.onTriggerShowArea = { show -> triggerShowArea = show; if (show) setupTriggerOverlay(); updateTriggerOverlayVisibility() }
        guiPanel.onToggleModel = { running -> if (running && !inferRunning.get()) startInferLoop() else if (!running) inferRunning.set(false) }

        overlayView.rangeRadius = guiPanel.range; JniCallBack.setConfidence(guiPanel.confidence)
        setupTriggerOverlay()
        wm.addView(guiPanel, guiParams); guiAdded = true; guiVisible = true
        guiPanel.alpha = 0f; guiPanel.scaleX = 0.85f; guiPanel.scaleY = 0.85f
        guiPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
    }

    private fun hideGui() {
        if (guiAdded) guiPanel.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(150).withEndAction { guiPanel.visibility = View.GONE }.start()
        guiVisible = false
    }

    private fun setupImageReader() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Log.d("AimbotInfer", "MediaProjection 停止"); inferRunning.set(false); imageReader?.close() }
        }, Handler(Looper.getMainLooper()))
        mediaProjection?.createVirtualDisplay("AimbotCapture", screenWidth, screenHeight, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, null)
        Log.d("AimbotInfer", "imageReader 创建成功")
    }

    private fun startInferLoop() {
        if (inferRunning.getAndSet(true)) { Log.d(TAG, "infer loop already running"); return }
        centerX = screenWidth / 2f; centerY = screenHeight / 2f
        Log.d(TAG, "infer loop started, center=($centerX,$centerY)")
        executor.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            var aliveCtr = 0
            while (inferRunning.get()) {
                if (++aliveCtr % 30 == 0) { touchInjector?.keepAlive(); Log.d(TAG, "alive trigger=$triggerEnabled uinput=${uinputInjector?.isAvailable()} shizuku=${shizukuClient?.isConnected()} detects=$hasDetects") }
                val currentRange = guiPanel.range
                if (currentRange != cachedRangePx) { cachedRangePx = currentRange; cachedRange = currentRange.toFloat() }
                val image = imageReader?.acquireLatestImage()
                if (image == null) { Thread.yield(); continue }
                try {
                    hasDetects = false
                    val plane = image.planes[0]; val buffer = plane.buffer
                    val regionW = cachedRangePx * 2; val regionH = cachedRangePx * 2
                    val offsetX = (screenWidth - regionW) / 2; val offsetY = (screenHeight - regionH) / 2

                    val result = JniCallBack.detect(buffer, offsetX, offsetY, regionW, regionH, screenWidth, screenHeight, plane.rowStride, plane.pixelStride)

                    if (result != null) {
                        val count = result.size / 6; var rectCount = 0; var i = 0
                        while (i < count && rectCount < rectBuffer.size) {
                            rectBuffer[rectCount].set(result[i*6+2]*screenWidth, result[i*6+3]*screenHeight, result[i*6+4]*screenWidth, result[i*6+5]*screenHeight)
                            rectCount++; i++
                        }
                        hasDetects = rectCount > 0
                        lastDetections = rectBuffer.take(rectCount)
                        mainHandler.post { overlayView.updateDetections(lastDetections) }

                        if (aimbotOn.get() && rectCount > 0) {
                            var bestDistSq = Float.MAX_VALUE; var bestX = centerX; var bestY = centerY
                            val rangeVal = cachedRange; val cx = centerX; val cy = centerY
                            for (idx in 0 until rectCount) {
                                val r = rectBuffer[idx]; val bcx = (r.left+r.right)*0.5f; val bcy = r.top+(r.bottom-r.top)*0.2f
                                val dx = bcx-cx; val dy = bcy-cy; val d = dx*dx+dy*dy
                                if (d < bestDistSq && d < rangeVal*rangeVal) { bestDistSq = d; bestX = bcx; bestY = bcy }
                            }
                        }
                    }

                    // detection-based trigger: center in any detection box
                    val triggerAvailable = touchInjector?.available == true || shizukuClient?.isConnected() == true
                    if (triggerEnabled && hasDetects && triggerAvailable) {
                        val cx = centerX.toInt(); val cy = centerY.toInt()
                        var onTarget = false
                        for (r in lastDetections) {
                            if (cx >= r.left && cx <= r.right && cy >= r.top && cy <= r.bottom) { onTarget = true; break }
                        }
                        if (onTarget) {
                            val now = System.currentTimeMillis()
                            val cd = triggerReactionSpeed.toInt().coerceIn(10, 500)
                            if (now - lastTriggerMs >= cd) {
                                lastTriggerMs = now
                                val px = (triggerTouchRange * resources.displayMetrics.density).toInt()
                                val rndX = triggerAreaX + (Math.random() * px).toInt()
                                val rndY = triggerAreaY + (Math.random() * px).toInt()
                                Log.d(TAG, "trigger fire! tap=($rndX,$rndY)")
                                // Prefer uinput > shizuku > inline injector
                                if (uinputInjector?.isAvailable() == true) {
                                    uinputInjector?.tap(rndX, rndY)
                                } else if (shizukuClient?.isConnected() == true) {
                                    shizukuClient?.tap(rndX, rndY)
                                } else {
                                    touchInjector?.tap(rndX, rndY)
                                }
                            }
                        }
                    }

                    if (result == null) { hasDetects = false; lastDetections = emptyList(); mainHandler.post { overlayView.updateDetections(lastDetections) } }
                } catch (e: Exception) { Log.e(TAG, "推理帧异常: ${e.message}") }
                finally { image.close() }
            }
            inferRunning.set(false)
        }
    }

    private fun makeParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val ch = NotificationChannel(CH_ID, "Aimbot", NotificationManager.IMPORTANCE_LOW); (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch) } }
    private fun buildNotification() = NotificationCompat.Builder(this, CH_ID).setContentTitle("Aimbot").setContentText("运行中").setSmallIcon(android.R.drawable.ic_menu_view).build()

    override fun onDestroy() {
        inferRunning.set(false); executor.shutdown()
        touchInjector?.destroy(); shizukuClient?.disconnect(); uinputInjector?.destroy()
        mediaProjection?.stop()
        if (ballAdded) wm.removeView(ballView); if (overlayAdded) wm.removeView(overlayView); if (guiAdded) wm.removeView(guiPanel)
        if (triggerOverlayAdded) { try { wm.removeView(triggerOverlay) } catch (_: Exception) {} }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
