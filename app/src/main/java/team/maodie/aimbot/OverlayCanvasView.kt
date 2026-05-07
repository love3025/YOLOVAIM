package team.maodie.aimbot

import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.View

/**
 * 全屏透明覆盖层
 * 1. 屏幕中央四角方框（range 控制大小）
 * 2. 所有推理结果框
 */
class OverlayCanvasView(context: Context) : View(context) {

    // 由 FloatService 更新
    var rangeRadius: Int = 300
    var detections: List<RectF> = emptyList()   // 归一化坐标已转成像素
    var aimbotEnabled: Boolean = false

    // ── 四角框画笔 ──────────────────────────────
    private val paintCorner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    // ── 检测框画笔（范围内） ─────────────────────
    private val paintBoxIn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4444")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // ── 检测框画笔（范围外，暗色） ───────────────
    private val paintBoxOut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#884444")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // ── 中心点画笔 ──────────────────────────────
    private val paintCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFFFFFF")
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        if (!aimbotEnabled) {
            Log.d("OverlayCanvas", "onDraw: aimbotEnabled=false, skip")
            return
        }

        Log.d("OverlayCanvas", "onDraw: detections=${detections.size}, rangeRadius=$rangeRadius, w=${width}, h=${height}")
        if (detections.isNotEmpty()) {
            val r = detections[0]
            Log.d("OverlayCanvas", "first box pixel: left=${r.left.toInt()}, top=${r.top.toInt()}, right=${r.right.toInt()}, bottom=${r.bottom.toInt()}")
        }

        val cx = width  / 2f
        val cy = height / 2f
        val half = rangeRadius.toFloat()

        // ── 绘制四角框 ────────────────────────────
        drawCornerBox(canvas, cx - half, cy - half, cx + half, cy + half)

        // ── 中心小点 ─────────────────────────────
        canvas.drawCircle(cx, cy, 4f, paintCenter)

        // ── 绘制所有检测框 ─────────────────────────
        for (rect in detections) {
            val boxCx = (rect.left + rect.right)  / 2f
            val boxCy = (rect.top  + rect.bottom) / 2f
            val dist  = Math.hypot((boxCx - cx).toDouble(), (boxCy - cy).toDouble())

            val paint = if (dist <= rangeRadius) paintBoxIn else paintBoxOut
            canvas.drawRect(rect, paint)
        }
    }

    /**
     * 只画四个角，带圆角
     * cornerLen = 角线长度
     * r         = 圆角半径
     */
    private fun drawCornerBox(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val cornerLen = 36f
        val cr        = 10f  // 圆角半径
        val p         = paintCorner

        // 框太小不画（避免渲染bug）
        val boxW = r - l
        val boxH = b - t
        val minDim = minOf(boxW, boxH)
        if (minDim < cornerLen * 2 + cr * 2) return

        // 左上角
        canvas.drawLine(l, t + cr, l, t + cornerLen, p)
        canvas.drawLine(l + cr, t, l + cornerLen, t, p)

        // 右上角
        canvas.drawLine(r - cr, t, r - cornerLen, t, p)
        canvas.drawLine(r, t + cr, r, t + cornerLen, p)

        // 左下角
        canvas.drawLine(l, b - cr, l, b - cornerLen, p)
        canvas.drawLine(l + cr, b, l + cornerLen, b, p)

        // 右下角
        canvas.drawLine(r - cr, b, r - cornerLen, b, p)
        canvas.drawLine(r, b - cr, r, b - cornerLen, p)
    }

    /** 外部调用：更新检测结果并重绘 */
    fun updateDetections(rects: List<RectF>) {
        Log.d("OverlayCanvas", "updateDetections called: rects=${rects.size}")
        detections = rects
        postInvalidate()
    }
}
