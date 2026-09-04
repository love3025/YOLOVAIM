package io.github.love3025.yolovaim.controller

import android.util.Log
import io.github.love3025.yolovaim.injector.TouchInjectorInterface
import io.github.love3025.yolovaim.model.AreaConfig
import io.github.love3025.yolovaim.model.DetectionInfo

/**
 * @param fireArea 开火区。**用户没在「区域设置」里配过时必须返回 null** —— 早期版本
 *   在配置为空时补的是 `AreaConfig()` 默认值(150x150 @ 0,0)，于是每一枪都点在屏幕
 *   左上角，扳机看起来完全失效但日志里一切正常。
 * @param fallbackTapCircle 开火区未配置时的兜底落点：(圆心x, 圆心y, 半径)，取自
 *   FloatService 持有的那个真实触发圆圈。这里不再自己维护一份 overlay ——
 *   曾经有过，但 setupTriggerOverlay() 从未被调用，坐标恒为 0，兜底路径同样点左上角。
 */
class TriggerController(
    private val touchClient: () -> TouchInjectorInterface?,
    private val fireArea: () -> AreaConfig?,
    private val fallbackTapCircle: () -> Triple<Int, Int, Int>
) {
    companion object {
        private const val TAG = "TriggerController"
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
    var triggerClasses: MutableSet<Int> = mutableSetOf()
    var classTriggerOffsets: Map<Int, Float> = emptyMap()

    private var lastTriggerMs = 0L
    private var autoStopDone = false  // 本轮急停是否已执行
    var triggerFired = false

    fun fireTriggerTap() {
        val area = fireArea()
        val x: Int
        val y: Int
        if (area != null) {
            x = area.x + (Math.random() * area.width).toInt()
            y = area.y + (Math.random() * area.height).toInt()
            Log.d(TAG, "trigger fire! area=(${area.x},${area.y} ${area.width}x${area.height}) tap=($x,$y)")
        } else {
            val (cx, cy, r) = fallbackTapCircle()
            x = cx + ((Math.random() - 0.5) * 2 * r).toInt()
            y = cy + ((Math.random() - 0.5) * 2 * r).toInt()
            Log.d(TAG, "trigger fire (未配置开火区，落在触发圆圈)! center=($cx,$cy) r=$r tap=($x,$y)")
        }
        touchClient()?.triggerTap(x, y, triggerTouchDuration.coerceIn(1, 50))
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
                maybeAutoStop(elapsed, reaction)
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
                maybeAutoStop(elapsed, cd)
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

    /** 急停：在开枪前 60ms 松开摇杆，给游戏时间注册停止；窗口本身不足 60ms 就立刻松开。 */
    private fun maybeAutoStop(elapsed: Long, windowMs: Int) {
        if (!autoStopEnabled || autoStopDone) return
        if (windowMs >= 60) {
            if (elapsed < windowMs - 60) return
            val lifted = touchClient()?.liftJoystickFinger() ?: false
            autoStopDone = true
            Log.d(TAG, "autoStop (early): liftJoystickFinger=$lifted elapsed=${elapsed}ms")
        } else {
            val lifted = touchClient()?.liftJoystickFinger() ?: false
            autoStopDone = true
            Log.d(TAG, "autoStop (immediate): liftJoystickFinger=$lifted")
        }
    }
}
