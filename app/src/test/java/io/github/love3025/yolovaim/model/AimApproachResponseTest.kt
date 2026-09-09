package io.github.love3025.yolovaim.model

import org.junit.Assert.*
import org.junit.Test

/** Regression for "fast at first, then crawling toward the box". */
class AimApproachResponseTest {
    // 生产侧 Y 轴上限 = MAX_ASSIST_KP × kpYRatio，kpYRatio 默认 0.6。
    private val yCeiling = AimFinishController.MAX_ASSIST_KP * 0.6f

    private fun observed(x: Float, y: Float = 0f, tolerance: Float = 2f): AimFinishController {
        val controller = AimFinishController()
        repeat(6) { frame ->
            val capture = 1_000_000_000L + frame * 25_000_000L
            controller.observe(x, y, tolerance, tolerance, 10f, capture, capture + 10_000_000L)
        }
        return controller
    }

    @Test fun assistanceStartsBeforeTheLastTenPixels() {
        for (error in listOf(16f, 32f, 64f, 96f)) {
            val controller = observed(error)
            val proportional = error * 0.07f
            assertTrue("No approach assistance at error=$error",
                controller.shapeX(proportional, 0.07f) > proportional)
        }
    }

    @Test fun largerBoxesAlsoGetAFasterApproachWithoutChangingTheirTolerance() {
        val controller = observed(32f, 32f, tolerance = 10f)
        assertTrue(controller.shapeX(1f, 0.07f) > 1f)
        assertTrue(controller.shapeY(1f, 0.042f, yCeiling) > 1f)
        val atRest = observed(9f, 9f, tolerance = 10f)
        assertEquals(0.1f, atRest.shapeX(0.1f, 0.07f), 0f)
        assertEquals(0.1f, atRest.shapeY(0.1f, 0.042f, yCeiling), 0f)
    }

    @Test fun oneAxisDoesNotWaitForTheOtherToReachTheOldStopRegion() {
        val controller = observed(4f, 200f)
        assertTrue(controller.shapeX(0.2f, 0.07f) > 0.2f)
        assertEquals(8f, controller.shapeY(8f, 0.042f, yCeiling), 0f)
    }
}
