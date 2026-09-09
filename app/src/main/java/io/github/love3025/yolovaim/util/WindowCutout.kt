package io.github.love3025.yolovaim.util

import android.view.WindowManager

/**
 * Let an overlay window draw into the display-cutout area.
 *
 * Without this, LayoutParams default to LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT:
 * the window may render into the cutout only when the cutout sits on a short
 * (top/bottom) edge. In landscape the punch-hole moves to a long edge, so the
 * whole window gets letterboxed inward and the overlay can never reach the
 * camera strip — the floating ball can't be dragged there, and the full-screen
 * canvas is short by the cutout inset, shifting every drawn box.
 *
 * ALWAYS (API 30+, minSdk here is 31) removes that letterbox in every
 * orientation. Coordinates then match Display.getRealSize(), which is what the
 * capture path and the touch injector already use.
 */
fun WindowManager.LayoutParams.allowDisplayCutout(): WindowManager.LayoutParams = apply {
    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
}
