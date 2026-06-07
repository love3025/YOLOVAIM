package team.maodie.aimbot

import android.content.Context
import android.graphics.*
import android.view.View

/** Single detection with class info */
data class DetectionInfo(val rect: RectF, val classId: Int, val className: String)

/**
 * Full-screen transparent overlay
 * 1. Capture range corners
 * 2. Detection boxes with class labels
 */
class OverlayCanvasView(context: Context) : View(context) {

    var rangeRadius: Int = 300
    var detections: List<DetectionInfo> = emptyList()
    var aimbotEnabled: Boolean = false
    var showCaptureRange: Boolean = false
    var showDetectionBox: Boolean = false
    var showCenterDot: Boolean = false

    // Color palette per class
    private val classColors = intArrayOf(
        Color.parseColor("#00FF00"),  // 0 green
        Color.parseColor("#FF0000"),  // 1 red
        Color.parseColor("#0088FF"),  // 2 blue
        Color.parseColor("#FFFF00"),  // 3 yellow
        Color.parseColor("#FF00FF"),  // 4 magenta
        Color.parseColor("#00FFFF"),  // 5 cyan
        Color.parseColor("#FF8800"),  // 6 orange
        Color.parseColor("#FF69B4"),  // 7 pink
        Color.parseColor("#88FF88"),  // 8 light green
        Color.parseColor("#AAAAFF"),  // 9 light blue
    )

    private fun colorForClass(classId: Int): Int =
        classColors[classId % classColors.size]

    private val paintCorner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private val paintBox = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val paintBoxDim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val paintCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFFFFFF")
        style = Paint.Style.FILL
    }

    private val paintLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        val dets = detections

        if (showCaptureRange) {
            val cx = width / 2f
            val cy = height / 2f
            val half = rangeRadius.toFloat()
            drawCornerBox(canvas, cx - half, cy - half, cx + half, cy + half)
            if (showCenterDot) canvas.drawCircle(cx, cy, 4f, paintCenter)
        }

        if (showDetectionBox && dets.isNotEmpty()) {
            val cx = width / 2f
            val cy = height / 2f
            val rangeSq = (rangeRadius * rangeRadius).toFloat()

            for (det in dets) {
                val rect = det.rect
                val boxCx = (rect.left + rect.right) * 0.5f
                val boxCy = (rect.top + rect.bottom) * 0.5f
                val dx = boxCx - cx
                val dy = boxCy - cy
                val inRange = dx * dx + dy * dy <= rangeSq
                val color = colorForClass(det.classId)

                // Draw rect
                val p = if (inRange) paintBox else paintBoxDim
                p.color = if (inRange) color else (color and 0x88FFFFFF.toInt())
                canvas.drawRect(rect, p)

                // Draw class label above box
                val label = det.className
                val textW = paintLabel.measureText(label)
                val textH = paintLabel.textSize
                val pad = 4f
                val lx = rect.left
                val ly = rect.top - textH - pad * 2
                paintLabelBg.color = color and 0xCC000000.toInt() or 0x00FFFFFF.toInt()
                canvas.drawRect(lx, ly, lx + textW + pad * 2, ly + textH + pad * 2, paintLabelBg)
                canvas.drawText(label, lx + pad, ly + textH + pad, paintLabel)
            }
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
