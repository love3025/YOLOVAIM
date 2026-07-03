package team.maodie.aimbot.inference

import android.util.Log
import java.nio.ByteBuffer

object JniCallBack {
    private const val TAG = "JniCallBack"

    init {
        try {
            System.loadLibrary("LiteRt")
            Log.i(TAG, "libLiteRt.so loaded")
        } catch (t: Throwable) {
            Log.e(TAG, "libLiteRt.so failed to load: ${t.message}")
        }
        // Load MediaTek LiteRT plugins (must be after LiteRt, before aimbot)
        try {
            System.loadLibrary("LiteRtDispatch_MediaTek")
            Log.i(TAG, "libLiteRtDispatch_MediaTek.so loaded")
        } catch (t: Throwable) {
            Log.d(TAG, "libLiteRtDispatch_MediaTek.so not available: ${t.message}")
        }
        try {
            System.loadLibrary("LiteRtCompilerPlugin_MediaTek")
            Log.i(TAG, "libLiteRtCompilerPlugin_MediaTek.so loaded")
        } catch (t: Throwable) {
            Log.d(TAG, "libLiteRtCompilerPlugin_MediaTek.so not available: ${t.message}")
        }
        System.loadLibrary("aimbot")
    }

    external fun init(modelPath: String): Boolean

    // 传入图像数据，返回坐标数组 [id, score, x1, y1, x2, y2, ...]
    // regionWidth/regionHeight: 要检测的区域大小（像素）
    // screenWidth/screenHeight: 原始屏幕尺寸，用于坐标转换
    external fun detect(
        buffer: ByteBuffer,
        offsetX: Int, offsetY: Int,
        regionWidth: Int, regionHeight: Int,
        screenWidth: Int, screenHeight: Int,
        rowStride: Int, pixelStride: Int
    ): FloatArray?

    external fun getBackend(): String

    external fun setConfidence(threshold: Float)

    external fun setForceCpu(useCpu: Boolean)

    external fun setCpuThreads(threads: Int)

    external fun setInputSize(width: Int, height: Int)

    /** bbox output layout: 0 = cxcywh (Dawan / valorant / local NPU), 1 = xyxy (ultralytics / website export) */
    external fun setOutputFormat(format: Int)

    external fun release()
}