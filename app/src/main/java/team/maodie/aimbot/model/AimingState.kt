package team.maodie.aimbot.model

import android.graphics.RectF

data class AimingState(
    var pointerDown: Boolean = false,
    var centerX: Float = 0f,
    var centerY: Float = 0f,
    var startX: Float = 0f,
    var startY: Float = 0f,
    var prevErrorX: Float = 0f,
    var prevErrorY: Float = 0f,
    var integralX: Float = 0f,
    var integralY: Float = 0f,
    var lockedTarget: RectF? = null,
    var maxDragDist: Float = 400f,
    var swayTimer: Int = 0,
    var swayPulse: Int = 0,
    var swayDuration: Int = 10,
    var swayDir: Float = 0f,
    var prevTargetX: Float = Float.NaN,
    var prevTargetY: Float = Float.NaN,
    var smoothVelX: Float = 0f,
    var smoothVelY: Float = 0f,
    var derivFilteredX: Float = 0f,
    var derivFilteredY: Float = 0f,
    var prevFrameX: Float = 0f,
    var prevFrameY: Float = 0f
) {
    fun updateVelocity(cx: Float, cy: Float) {
        if (!prevTargetX.isNaN()) {
            val rawVx = cx - prevTargetX
            val rawVy = cy - prevTargetY
            smoothVelX = smoothVelX * 0.7f + rawVx * 0.3f
            smoothVelY = smoothVelY * 0.7f + rawVy * 0.3f
        }
        prevTargetX = cx; prevTargetY = cy
    }

    fun reset() {
        pointerDown = false
        lockedTarget = null
        prevErrorX = 0f; prevErrorY = 0f
        integralX = 0f; integralY = 0f
        prevTargetX = Float.NaN; prevTargetY = Float.NaN
        smoothVelX = 0f; smoothVelY = 0f
        derivFilteredX = 0f; derivFilteredY = 0f
        prevFrameX = 0f; prevFrameY = 0f
        swayTimer = (30..90).random(); swayPulse = 0; swayDir = 0f
    }
}
