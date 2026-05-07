package team.maodie.aimbot

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/**
 * 悬浮球 View
 * 外黑内白，35dp，可拖拽，点击回调
 */
class FloatBallView(context: Context) : View(context) {

    var onClickCallback: (() -> Unit)? = null
    var onMoveCallback: ((dx: Int, dy: Int) -> Unit)? = null

    private val paintOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val paintInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    // 外圈细描边
    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#444444")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private var downX = 0f
    private var downY = 0f
    private var moved = false

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r  = width / 2f

        // 外黑圆
        canvas.drawCircle(cx, cy, r, paintOuter)
        // 内白圆（白色占 60%）
        canvas.drawCircle(cx, cy, r * 0.60f, paintInner)
        // 描边
        canvas.drawCircle(cx, cy, r - 1f, paintStroke)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downX).toInt()
                val dy = (event.rawY - downY).toInt()
                if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                    moved = true
                    onMoveCallback?.invoke(dx, dy)
                    downX = event.rawX
                    downY = event.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) onClickCallback?.invoke()
            }
        }
        return true
    }
}
