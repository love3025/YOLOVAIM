package team.maodie.aimbot

import android.content.Context
import android.graphics.*
import android.view.View

/**
 * 显示自瞄PID追踪时的触摸点位置
 * 画一个圆点表示当前追踪目标的触摸位置
 */
class TouchDisplayView(context: Context) : View(context) {

    var touchX: Float = -1f
        set(v) { field = v; invalidate() }
    var touchY: Float = -1f
        set(v) { field = v; invalidate() }
    var dotRadius: Float = 20f
        set(v) { field = v; invalidate() }
    var visible: Boolean = false
        set(v) { field = v; invalidate() }

    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4444")
        style = Paint.Style.FILL
    }
    private val paintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF4444")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    override fun onDraw(canvas: Canvas) {
        if (!visible || touchX < 0 || touchY < 0) return
        canvas.drawCircle(touchX, touchY, dotRadius, paintDot)
        canvas.drawCircle(touchX, touchY, dotRadius * 1.5f, paintRing)
    }
}