package team.maodie.aimbot

import android.content.Context
import android.graphics.*
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

    // ── 预计算的绘制数据 ──────────────────────
    private val paintCorner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private val paintBoxIn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4444")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val paintBoxOut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#884444")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val paintCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFFFFFF")
        style = Paint.Style.FILL
    }

    // ── 复用变量避免GC ───────────────────────
    private var lastDetections: List<RectF>? = null
    private var lastRange = 0

    override fun onDraw(canvas: Canvas) {
        if (!aimbotEnabled) return

        // 缓存检测结果避免每次重绘都触发列表创建
        val dets = detections
        if (dets.isEmpty()) {
            // 只画四角框
            val cx = width / 2f
            val cy = height / 2f
            val half = rangeRadius.toFloat()
            drawCornerBox(canvas, cx - half, cy - half, cx + half, cy + half)
            canvas.drawCircle(cx, cy, 4f, paintCenter)
            return
        }

        val cx = width / 2f
        val cy = height / 2f
        val half = rangeRadius.toFloat()
        val rangeSq = (rangeRadius * rangeRadius).toFloat()

        // ── 绘制四角框 ────────────────────────────
        drawCornerBox(canvas, cx - half, cy - half, cx + half, cy + half)

        // ── 中心小点 ─────────────────────────────
        canvas.drawCircle(cx, cy, 4f, paintCenter)

        // ── 绘制所有检测框（优化版） ─────────────────
        for (rect in dets) {
            val boxCx = (rect.left + rect.right) * 0.5f
            val boxCy = (rect.top + rect.bottom) * 0.5f
            val dx = boxCx - cx
            val dy = boxCy - cy
            // 用平方比较避免开方
            val paint = if (dx * dx + dy * dy <= rangeSq) paintBoxIn else paintBoxOut
            canvas.drawRect(rect, paint)
        }
    }

    /**
     * 只画四个角，带圆角
     */
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

    /** 外部调用：更新检测结果并重绘 */
    fun updateDetections(rects: List<RectF>) {
        detections = rects
        postInvalidateOnAnimation()
    }
}
