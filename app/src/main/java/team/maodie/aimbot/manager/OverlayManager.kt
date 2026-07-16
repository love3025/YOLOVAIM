package team.maodie.aimbot.manager

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import team.maodie.aimbot.view.TouchDisplayView
import team.maodie.aimbot.view.AreaSettingsView
import team.maodie.aimbot.view.GuiPanelView
import team.maodie.aimbot.model.AreaConfig
import team.maodie.aimbot.util.ProjectionHolder

class OverlayManager(
    private val context: Context,
    private val wm: WindowManager,
    private val screenWidth: () -> Int,
    private val screenHeight: () -> Int,
    private val dp: (Int) -> Int
) {
    companion object {
        private const val TAG = "OverlayManager"
    }

    // Touch display overlay
    private var touchDisplayView: TouchDisplayView? = null
    private var touchDisplayAdded = false

    // Area settings overlay
    private var areaSettingsView: AreaSettingsView? = null
    private var areaSettingsAdded = false

    // Inference info overlay — small top-center TextView that shows
    // preprocess/infer/postprocess ms + detection count. Independent of the
    // full-screen OverlayCanvasView so canvas-onDraw timing never gates it.
    private var inferInfoView: TextView? = null
    private var inferInfoAdded = false
    private var inferInfoCurrent: String = ""
    // Set once cleanup() runs. After that, any late setInferInfo() calls from
    // already-queued mainHandler.post() messages (inference loop's last frame
    // before inferRunning flips to false) are dropped — otherwise they'd see
    // inferInfoAdded=false and rebuild the TextView, defeating cleanupViews().
    private var released = false

    // Callbacks
    var onAreaSettingsConfirm: ((List<AreaConfig>) -> Unit)? = null
    var onAreaSettingsCancel: (() -> Unit)? = null

    fun setupTouchDisplayView(guiPanel: GuiPanelView) {
        if (touchDisplayAdded) return
        val size = dp(guiPanel.aimTouchSize) * 2
        touchDisplayView = TouchDisplayView(context)
        ProjectionHolder.touchDisplayView = touchDisplayView
        val p = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = screenWidth() / 2 - size / 2
        p.y = screenHeight() / 2 - size / 2
        touchDisplayView!!.dotRadius = dp(guiPanel.aimTouchSize).toFloat()
        wm.addView(touchDisplayView, p)
        touchDisplayAdded = true
        touchDisplayView!!.alpha = 0f
    }

    fun updateTouchDisplayVisibility(show: Boolean) {
        if (touchDisplayAdded) {
            val lp = touchDisplayView?.layoutParams as? WindowManager.LayoutParams
            if (lp != null) {
                lp.flags = if (show) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                touchDisplayView?.alpha = if (show) 1f else 0f
                try { wm.updateViewLayout(touchDisplayView, lp) } catch (_: Exception) {}
            }
        }
    }

    fun updateTouchDisplaySize(px: Int) {
        val p = dp(px)
        touchDisplayView?.dotRadius = p.toFloat()
        if (touchDisplayAdded) {
            val lp = touchDisplayView?.layoutParams as? WindowManager.LayoutParams
            if (lp != null) {
                lp.width = p * 2
                lp.height = p * 2
                wm.updateViewLayout(touchDisplayView, lp)
            }
        }
    }

    fun setupAreaSettingsView(savedAreas: List<AreaConfig>) {
        areaSettingsView = AreaSettingsView(context)
        ProjectionHolder.areaSettingsView = areaSettingsView
        val params = WindowManager.LayoutParams(
            MATCH_PARENT,
            MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        areaSettingsView?.apply {
            onConfirm = { areas ->
                onAreaSettingsConfirm?.invoke(areas)
                removeAreaSettingsView()
            }
            onCancel = {
                onAreaSettingsCancel?.invoke()
                removeAreaSettingsView()
            }
        }
        wm.addView(areaSettingsView!!, params)
        areaSettingsAdded = true
        areaSettingsView!!.visibility = View.GONE
    }

    fun removeAreaSettingsView() {
        try {
            if (areaSettingsAdded) {
                wm.removeView(areaSettingsView)
                areaSettingsAdded = false
                ProjectionHolder.areaSettingsView = null
            }
        } catch (_: Exception) {}
    }

    fun showAreaSettings(savedAreas: List<AreaConfig>) {
        if (areaSettingsAdded) removeAreaSettingsView()
        setupAreaSettingsView(savedAreas)
        if (savedAreas.isNotEmpty()) areaSettingsView?.setAreas(savedAreas)
        areaSettingsView?.open()
    }

    // ========== Inference info overlay ==========
    /**
     * Creates the inference-info TextView overlay (idempotent). Hides it
     * until [setInferInfo] is called with non-empty text. Safe to call
     * eagerly at startup — the view is GONE until needed and never blocks
     * touches (FLAG_NOT_TOUCHABLE).
     */
    fun setupInferInfoView() {
        if (inferInfoAdded) return
        val tv = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#FFFFEB3B"))  // light yellow
            typeface = Typeface.DEFAULT_BOLD
            // Semi-opaque black pill keeps the line legible against bright
            // game scenes without obscuring the player's view.
            setBackgroundColor(Color.argb(0xCC, 0x10, 0x10, 0x10))
            setPadding(dp(10), dp(3), dp(10), dp(3))
            visibility = View.GONE
        }
        val p = WindowManager.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        // Small inset from the top so the line doesn't collide with the
        // status-bar pill on devices that keep it visible (e.g. ColorOS).
        p.y = dp(12)
        try {
            wm.addView(tv, p)
            inferInfoView = tv
            inferInfoAdded = true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "setupInferInfoView: addView failed: ${e.message}")
        }
    }

    /**
     * Update the inference-info line. Empty text hides the view; non-empty
     * shows it with the given label. No-op when the text matches what's
     * already shown — inference loop posts this every frame, so the guard
     * keeps TextView.setText (and any relayout) off the hot path.
     *
     * Drops the call entirely once [cleanup] has run: stale posts from the
     * inference loop's last frame can otherwise slip past cleanupViews() and
     * rebuild the TextView after the service has stopped.
     */
    fun setInferInfo(text: String) {
        if (released) return
        if (!inferInfoAdded) {
            if (text.isEmpty()) return
            setupInferInfoView()
        }
        val tv = inferInfoView ?: return
        if (text == inferInfoCurrent) return
        inferInfoCurrent = text
        if (text.isEmpty()) {
            if (tv.visibility != View.GONE) tv.visibility = View.GONE
        } else {
            tv.text = text
            if (tv.visibility != View.VISIBLE) tv.visibility = View.VISIBLE
        }
    }

    fun cleanup() {
        try {
            if (touchDisplayAdded) {
                wm.removeView(touchDisplayView)
                touchDisplayAdded = false
            }
        } catch (_: Exception) {}
        removeAreaSettingsView()
        try {
            if (inferInfoAdded) {
                wm.removeView(inferInfoView)
                inferInfoView = null
                inferInfoAdded = false
                inferInfoCurrent = ""
            }
        } catch (_: Exception) {}
        // After this point, no fresh setInferInfo call may rebuild the view.
        // Stale mainHandler.post() messages from the inference loop's last
        // frame can still arrive — see setInferInfo().
        released = true
    }
}
