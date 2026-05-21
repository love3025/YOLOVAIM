package team.maodie.aimbot

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
    var maxDragDist: Float = 400f
) {
    fun reset() {
        pointerDown = false
        lockedTarget = null
        prevErrorX = 0f; prevErrorY = 0f
        integralX = 0f; integralY = 0f
    }
}