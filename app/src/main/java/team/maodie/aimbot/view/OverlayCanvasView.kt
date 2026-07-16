package team.maodie.aimbot.view

import android.content.Context
import android.graphics.*
import android.view.View
import team.maodie.aimbot.model.DetectionInfo

/**
 * Full-screen transparent overlay
 * 1. Capture range corners
 * 2. Detection boxes with class labels
 */
class OverlayCanvasView(context: Context) : View(context) {

    // Explicit capture-space dimensions. Mirrors MediaProjection's coordinate
    // frame. The center point and capture range are derived from these so the
    // overlay stays anchored to the exact capture regardless of how the View's
    // own measured size happens to map under window flags / status-bar shifts.
    var captureWidth: Int = 0
    var captureHeight: Int = 0

    var rangeRadius: Int = 300
    var detections: List<DetectionInfo> = emptyList()
    var aimbotEnabled: Boolean = false
    var showCaptureRange: Boolean = false
    var showDetectionBox: Boolean = false
    var showCenterDot: Boolean = false
    var showFov: Boolean = false
    var fovRadius: Int = 50

    fun setGeometry(w: Int, h: Int) {
        if (captureWidth == w && captureHeight == h) return
        captureWidth = w
        captureHeight = h
        invalidate()
    }

    private val paintCorner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private val paintBox = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0x99, 0xFF, 0xFF, 0xFF)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val paintCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFFFFFF")
        style = Paint.Style.FILL
    }

    private val paintFov = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0xAA, 0xFF, 0xFF, 0xFF)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    override fun onDraw(canvas: Canvas) {
        val dets = detections

        if (showCaptureRange) {
            val cx = captureWidth / 2f
            val cy = captureHeight / 2f
            val half = rangeRadius.toFloat()
            drawCornerBox(canvas, cx - half, cy - half, cx + half, cy + half)
            if (showCenterDot) canvas.drawCircle(cx, cy, 4f, paintCenter)
        }

        if (showFov && fovRadius > 0) {
            canvas.drawCircle(captureWidth / 2f, captureHeight / 2f, fovRadius.toFloat(), paintFov)
        }

        if (showDetectionBox && dets.isNotEmpty()) {
            for (det in dets) canvas.drawRect(det.rect, paintBox)
        }
    }

    private fun drawCornerBox(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val cornerLen = 36f
        val cr = 10f
        val p = paintCorner

        val boxW = r - l
        val boxH = b - t
        if (boxW < cornerLen * 2 + cr * 2 || boxH < cornerLen * 2 + cr * 2) return

        canvas.drawLine(l, t + cr, l, t + cornerLen, p)
        canvas.drawLine(l + cr, t, l + cornerLen, t, p)
        canvas.drawLine(r - cr, t, r - cornerLen, t, p)
        canvas.drawLine(r, t + cr, r, t + cornerLen, p)
        canvas.drawLine(l, b - cr, l, b - cornerLen, p)
        canvas.drawLine(l + cr, b, l + cornerLen, b, p)
        canvas.drawLine(r - cr, b, r - cornerLen, b, p)
        canvas.drawLine(r, b - cr, r, b - cornerLen, p)
    }

    fun updateDetections(dets: List<DetectionInfo>) {
        detections = dets
        postInvalidateOnAnimation()
    }
}
