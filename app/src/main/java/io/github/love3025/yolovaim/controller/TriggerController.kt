package io.github.love3025.yolovaim.controller
import io.github.love3025.yolovaim.model.DetectionInfo

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import io.github.love3025.yolovaim.model.AreaConfig
import io.github.love3025.yolovaim.injector.TouchInjectorInterface
import io.github.love3025.yolovaim.view.TriggerOverlayView
import io.github.love3025.yolovaim.util.ProjectionHolder
import io.github.love3025.yolovaim.util.allowDisplayCutout

class TriggerController(
    private val context: Context,
    private val wm: WindowManager,
    private val touchClient: () -> TouchInjectorInterface?,
    private val savedAreas: () -> List<AreaConfig>,
    private val screenWidth: () -> Int,
    private val screenHeight: () -> Int,
    private val dp: (Int) -> Int
) {
    companion object {
        private const val TAG = "TriggerController"
        private const val AREA_INDEX_FIRE = 0
        private const val AREA_INDEX_TRIGGER = 1
    }

    // Trigger settings
    var triggerEnabled = false
    var triggerReactionSpeed = 100
    var triggerCooldown = 200
    var triggerUpFluct = 3
    var triggerDownFluct = 3
    var triggerTouchDuration = 10
    var triggerTouchRange = 100
    var triggerShowArea = false
    var autoStopEnabled = false
    var triggerOffsetYRatio = 0f
    var triggerClasses: MutableSet<Int> = mutableSetOf()
    var classTriggerOffsets: Map<Int, Float> = emptyMap()

    // Trigger overlay
    private var triggerOverlay: TriggerOverlayView? = null
    private var triggerOverlayAdded = false
    private var triggerAreaX = 0
    private var triggerAreaY = 0
    private var lastTriggerMs = 0L
    private var lastAutoStopMs = 0L   // 上次急停时间
    private var autoStopDone = false  // 本轮急停是否已执行
    var triggerFired = false

    val mainHandler = Handler(Looper.getMainLooper())

    fun setupTriggerOverlay() {
        if (triggerOverlayAdded) return
        triggerOverlay = TriggerOverlayView(context)
        ProjectionHolder.triggerOverlayView = triggerOverlay
        val size = dp(triggerTouchRange.coerceAtLeast(30))
        val p = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).allowDisplayCutout()
        p.gravity = Gravity.TOP or Gravity.START
        p.x = screenWidth() / 2 - size / 2
        p.y = screenHeight() / 2 - size / 2
        triggerAreaX = p.x
        triggerAreaY = p.y
        triggerOverlay!!.areaSize = size
        triggerOverlay!!.onPositionChanged = { l, t -> triggerAreaX = l; triggerAreaY = t }
        wm.addView(triggerOverlay!!, p)
        triggerOverlayAdded = true
        triggerOverlay!!.alpha = 0f
        Log.d(TAG, "trigger overlay at ($triggerAreaX,$triggerAreaY) size=$size")
    }

    fun updateTriggerOverlayVisibility() {
        val ov = triggerOverlay ?: return
        val p = ov.layoutParams as? WindowManager.LayoutParams ?: return
        if (triggerShowArea) {
            ov.alpha = 1f
            p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            ov.alpha = 0f
            p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try { wm.updateViewLayout(ov, p) } catch (_: Exception) {}
    }

    fun updateTriggerOverlaySize() {
        val ov = triggerOverlay ?: return
        val p = ov.layoutParams as? WindowManager.LayoutParams ?: return
        val size = dp(triggerTouchRange.coerceAtLeast(30))
        p.width = size
        p.height = size
        ov.areaSize = size
        try { wm.updateViewLayout(ov, p) } catch (_: Exception) {}
    }

    fun fireTriggerTap() {
        val fireArea = savedAreas().getOrNull(AREA_INDEX_FIRE)
        if (fireArea != null) {
            val rndX = fireArea.x + (Math.random() * fireArea.width).toInt()
            val rndY = fireArea.y + (Math.random() * fireArea.height).toInt()
            Log.d(TAG, "trigger fire! area=(${fireArea.x},${fireArea.y} ${fireArea.width}x${fireArea.height}) tap=($rndX,$rndY)")
            touchClient()?.triggerTap(rndX, rndY, triggerTouchDuration.coerceIn(1, 50))
        } else {
            val size = dp(triggerTouchRange.coerceAtLeast(30))
            val px = size / 2
            val cx = triggerAreaX + size / 2
            val cy = triggerAreaY + size / 2
            val rndX = cx + ((Math.random() - 0.5) * 2 * px).toInt()
            val rndY = cy + ((Math.random() - 0.5) * 2 * px).toInt()
            Log.d(TAG, "trigger fire (legacy)! tap=($rndX,$rndY)")
            touchClient()?.triggerTap(rndX, rndY, triggerTouchDuration.coerceIn(1, 50))
        }
    }

    /**
     * @param fireZoneState pre-sampled result of [TouchInjectorInterface.isFingerInFireZone].
     *   The caller already needs this value in the same frame (to drive recoil
     *   control), and it describes a physical finger that injected touches are
     *   explicitly excluded from, so re-querying it here would cost a second
     *   blocking IPC round-trip to return the same answer. Pass null to have
     *   this function query it itself.
     */
    fun processTrigger(
        lastDetections: List<DetectionInfo>,
        centerX: Float,
        centerY: Float,
        hasDetects: Boolean,
        fireZoneState: Boolean? = null
    ) {
        if (!triggerEnabled || !hasDetects || touchClient()?.isConnected() != true) return

        val fingerOnFire = fireZoneState ?: (touchClient()?.isFingerInFireZone() ?: false)
        if (fingerOnFire) return

        val triggerDets = if (triggerClasses.isEmpty()) lastDetections
            else lastDetections.filter { it.classId in triggerClasses }

        if (triggerDets.isEmpty()) return

        val cx = centerX.toInt()
        val cy = centerY.toInt()
        var onTarget = false
        for (det in triggerDets) {
            val r = det.rect
            val classOff = classTriggerOffsets[det.classId] ?: triggerOffsetYRatio
            val extendY = r.height() * (-classOff)
            if (cx >= r.left && cx <= r.right && cy >= r.top && cy <= r.bottom + extendY) {
                onTarget = true
                break
            }
        }

        if (onTarget) {
            val now = System.currentTimeMillis()
            if (!triggerFired) {
                // 第一发：准心进入目标时开始计时，反应速度后开枪
                if (lastTriggerMs == 0L) { lastTriggerMs = now; autoStopDone = false }
                val elapsed = now - lastTriggerMs
                val reaction = triggerReactionSpeed.coerceIn(10, 500)

                // 急停：在开枪前 60ms 松开摇杆，给游戏时间注册停止
                if (autoStopEnabled && !autoStopDone) {
                    val stopEarly = reaction >= 60
                    if (stopEarly) {
                        if (elapsed >= reaction - 60) {
                            val lifted = touchClient()?.liftJoystickFinger() ?: false
                            autoStopDone = true
                            Log.d(TAG, "autoStop (early): liftJoystickFinger=$lifted elapsed=${elapsed}ms")
                        }
                    } else {
                        // 反应速度 < 10ms，直接急停
                        val lifted = touchClient()?.liftJoystickFinger() ?: false
                        autoStopDone = true
                        Log.d(TAG, "autoStop (immediate): liftJoystickFinger=$lifted")
                    }
                }

                if (elapsed >= reaction) {
                    triggerFired = true
                    lastTriggerMs = now
                    autoStopDone = false
                    fireTriggerTap()
                }
            } else {
                // 第二发起：使用冷却时间
                val cd = triggerCooldown.coerceIn(10, 1000)
                val elapsed = now - lastTriggerMs

                // 急停：在开枪前 60ms 松开摇杆
                if (autoStopEnabled && !autoStopDone) {
                    val stopEarly = cd >= 60
                    if (stopEarly) {
                        if (elapsed >= cd - 60) {
                            val lifted = touchClient()?.liftJoystickFinger() ?: false
                            autoStopDone = true
                            Log.d(TAG, "autoStop (early): liftJoystickFinger=$lifted elapsed=${elapsed}ms")
                        }
                    } else {
                        val lifted = touchClient()?.liftJoystickFinger() ?: false
                        autoStopDone = true
                        Log.d(TAG, "autoStop (immediate): liftJoystickFinger=$lifted")
                    }
                }

                if (elapsed >= cd) {
                    lastTriggerMs = now
                    autoStopDone = false
                    fireTriggerTap()
                }
            }
        } else {
            // 准心离开目标，重置扳机状态
            triggerFired = false
            lastTriggerMs = 0L
            autoStopDone = false
        }
    }

    fun cleanup() {
        try {
            if (triggerOverlayAdded) {
                wm.removeView(triggerOverlay)
                triggerOverlayAdded = false
            }
        } catch (_: Exception) {}
    }
}
