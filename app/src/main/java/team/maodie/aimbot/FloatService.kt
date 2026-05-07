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
import androidx.core.app.NotificationCompat
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class FloatService : Service() {

    companion object {
        const val TAG = "FloatService"
        const val CH_ID = "aimbot_ch"
    }

    // ── WindowManager ────────────────────────
    private lateinit var wm: WindowManager

    // ── Views ────────────────────────────────
    private lateinit var ballView:    FloatBallView
    private lateinit var overlayView: OverlayCanvasView
    private lateinit var guiPanel:    GuiPanelView

    private var ballParams:    WindowManager.LayoutParams? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var guiParams:     WindowManager.LayoutParams? = null

    private var guiVisible  = false
    private var ballAdded   = false
    private var overlayAdded = false
    private var guiAdded    = false

    // ── MediaProjection ──────────────────────
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private val screenWidth  get() = resources.displayMetrics.widthPixels
    private val screenHeight get() = resources.displayMetrics.heightPixels
    private val screenDensity get() = resources.displayMetrics.densityDpi

    // ── 推理线程 ─────────────────────────────
    private val executor = Executors.newSingleThreadExecutor()
    private val inferRunning = AtomicBoolean(false)
    private val aimbotOn     = AtomicBoolean(false)

    // ── 主线程 Handler ───────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())

    // ─────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = ProjectionHolder.resultCode
        val data = ProjectionHolder.resultData
        Log.d("AimbotInfer", "Service收到 code=$code data=$data")

        if (data != null) {
            try {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = manager.getMediaProjection(code, data)
                Log.d("AimbotInfer", "projection创建成功: $mediaProjection")
                setupImageReader()
            } catch (e: Exception) {
                Log.e("AimbotInfer", "projection创建失败: ${e.message}")
                Log.e("AimbotInfer", "堆栈: ${e.stackTraceToString()}")
            }
        }
        setupBall()
        setupOverlay()
        return START_NOT_STICKY
    }
    // ─────────────────────────────────────────
    //  悬浮球
    // ─────────────────────────────────────────
    private fun setupBall() {
        val size = dp(35)
        ballView = FloatBallView(this)

        ballParams = makeParams(size, size,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50; y = 200
        }

        ballView.onMoveCallback = { dx, dy ->
            ballParams?.let {
                it.x += dx; it.y += dy
                wm.updateViewLayout(ballView, it)
            }
        }

        ballView.onClickCallback = { toggleGui() }

        wm.addView(ballView, ballParams)
        ballAdded = true
    }

    // ─────────────────────────────────────────
    //  全屏覆盖层
    // ─────────────────────────────────────────
    private fun setupOverlay() {
        overlayView = OverlayCanvasView(this)

        overlayParams = makeParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )

        wm.addView(overlayView, overlayParams)
        overlayAdded = true
    }

    // ─────────────────────────────────────────
    //  GUI 面板
    // ─────────────────────────────────────────
    private fun toggleGui() {
        if (guiVisible) {
            hideGui()
        } else {
            showGui()
        }
    }

    private fun showGui() {
        if (guiAdded) {
            guiPanel.visibility = View.VISIBLE
            guiVisible = true
            return
        }

        guiPanel = GuiPanelView(this)

        guiParams = makeParams(dp(280), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60; y = 280
        }

        guiPanel.onClose = { hideGui() }

        guiPanel.onEnabledChanged = { on ->
            aimbotOn.set(on)
            overlayView.aimbotEnabled = on
            Log.d("AimbotInfer", "开关切换: $on")
            if (on) startInferLoop() else overlayView.postInvalidate()
        }

        guiPanel.onSpeedChanged = { /* 供触摸注入模块使用 */ }

        guiPanel.onRangeChanged = { px ->
            overlayView.rangeRadius = px
            overlayView.postInvalidate()
        }

        // 初始同步
        overlayView.rangeRadius = guiPanel.range

        wm.addView(guiPanel, guiParams)
        guiAdded   = true
        guiVisible = true
    }

    private fun hideGui() {
        if (guiAdded) guiPanel.visibility = View.GONE
        guiVisible = false
    }

    // ─────────────────────────────────────────
    //  MediaProjection / ImageReader
    // ─────────────────────────────────────────
    private fun setupImageReader() {
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 2
        )

        // 安卓15必须先注册 callback 再 createVirtualDisplay
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d("AimbotInfer", "MediaProjection 停止")
                inferRunning.set(false)
                imageReader?.close()
            }
        }, Handler(Looper.getMainLooper()))

        mediaProjection?.createVirtualDisplay(
            "AimbotCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        Log.d("AimbotInfer", "imageReader 创建成功")
    }

    // ─────────────────────────────────────────
//  推理循环（while循环 + acquire）
// ─────────────────────────────────────────
    private fun startInferLoop() {
        if (inferRunning.getAndSet(true)) return
        Log.d("AimbotInfer", "executor 开始跑")

        executor.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            Log.d("AimbotInfer", "线程内部启动")

            while (inferRunning.get() && aimbotOn.get()) {
                val image = imageReader?.acquireLatestImage()
                if (image == null) {
                    Thread.sleep(2)
                    continue
                }

                try {
                    val plane = image.planes[0]

                    val rangePx = guiPanel.range
                        val regionW = rangePx * 2
                        val regionH = rangePx * 2
                        val offsetX = (screenWidth - regionW) / 2
                        val offsetY = (screenHeight - regionH) / 2

                        val result = JniCallBack.detect(
                            plane.buffer,
                            offsetX, offsetY,
                            regionW, regionH,
                            screenWidth, screenHeight,
                            plane.rowStride, plane.pixelStride
                        )

                    if (result != null) {
                        val rects = mutableListOf<RectF>()
                        val count = result.size / 6
                        val range = guiPanel.range.toFloat()
                        val cx = screenWidth  / 2f
                        val cy = screenHeight / 2f

                        for (i in 0 until count) {
                            val x1 = result[i * 6 + 2] * screenWidth
                            val y1 = result[i * 6 + 3] * screenHeight
                            val x2 = result[i * 6 + 4] * screenWidth
                            val y2 = result[i * 6 + 5] * screenHeight
                            rects.add(RectF(x1, y1, x2, y2))
                        }

                        if (count > 0) {
                            val r = rects[0]
                            Log.d("AimbotInfer", "检测框像素: count=$count, first=${r.left.toInt()},${r.top.toInt()},${r.right.toInt()},${r.bottom.toInt()}")
                        }

                        mainHandler.post { overlayView.updateDetections(rects) }

                        if (aimbotOn.get()) {
                            var bestDist = Float.MAX_VALUE
                            var bestX = cx
                            var bestY = cy
                            for (rect in rects) {
                                val bcx = (rect.left + rect.right) / 2f
                                val bcy = rect.top + (rect.bottom - rect.top) * 0.2f
                                val dist = Math.hypot(
                                    (bcx - cx).toDouble(),
                                    (bcy - cy).toDouble()
                                ).toFloat()
                                if (dist < range && dist < bestDist) {
                                    bestDist = dist
                                    bestX = bcx
                                    bestY = bcy
                                }
                            }
                            // TODO: TouchInjector.moveTo(bestX, bestY, guiPanel.speed)
                        }
                    } else {
                        mainHandler.post { overlayView.updateDetections(emptyList()) }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "推理帧异常: ${e.message}")
                } finally {
                    image.close()
                }
            }
            inferRunning.set(false)
        }
    }

    // ─────────────────────────────────────────
    //  工具
    // ─────────────────────────────────────────
    private fun makeParams(w: Int, h: Int, flags: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ─────────────────────────────────────────
    //  通知
    // ─────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "Aimbot", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("Aimbot")
            .setContentText("运行中")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    // ─────────────────────────────────────────
    override fun onDestroy() {
        inferRunning.set(false)
        executor.shutdown()
        mediaProjection?.stop()
        if (ballAdded)    wm.removeView(ballView)
        if (overlayAdded) wm.removeView(overlayView)
        if (guiAdded)     wm.removeView(guiPanel)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
