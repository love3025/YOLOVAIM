package team.maodie.aimbot

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/**
 * MD3 风格悬浮按钮
 *
 * 蓝色圆形 + 白色中心点（类似 FAB），
 * 使用 View 自带 elevation 渲染阴影。
 */
class FloatBallView(context: Context) : View(context) {

    var onClickCallback: (() -> Unit)? = null
    var onMoveCallback: ((dx: Int, dy: Int) -> Unit)? = null

    private val primary = Color.parseColor("#1976D2")

    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primary
        style = Paint.Style.FILL
    }
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private var downX = 0f
    private var downY = 0f
    private var moved = false

    init {
        elevation = dp(6).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r  = width / 2f

        canvas.drawCircle(cx, cy, r, paintBg)
        canvas.drawCircle(cx, cy, r * 0.30f, paintDot)
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
