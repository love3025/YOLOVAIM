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

    /**
     * Pre-compile a TFLite model on QNN HTP without running inference.
     *
     * Creates and releases a throwaway LiteRtEngine so QNN's HTP graph cache file
     * gets written (or hit if it already exists) before the user opens the app's
     * main view. Safe to call concurrently across models from a background
     * ExecutorService — QNN serializes per-device internally.
     *
     * Returns true if init() succeeded (= cache file is on disk for next load).
     * Returns false on load error; caller should treat as best-effort.
     */
    external fun prewarmQnn(modelPath: String): Boolean

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

    external fun release()

    /**
     * Returns the per-stage timings (ms) of the most recent [detect] call as
     * a `float[3]` of `[preprocessMs, inferMs, postprocessMs]`, or `null` when
     * no engine is loaded. Underlying values come from the native engine and
     * are only meaningful after at least one [detect] has completed.
     */
    external fun getInferTimings(): FloatArray?
}