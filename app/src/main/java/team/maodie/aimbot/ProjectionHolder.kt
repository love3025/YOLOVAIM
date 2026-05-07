package team.maodie.aimbot

import android.content.Intent
import android.media.projection.MediaProjection

object ProjectionHolder {
    var resultCode: Int = -1
    var resultData: Intent? = null
    var mediaProjection: MediaProjection? = null

    // 模型列表（由 MainActivity 设置，供 FloatService 读取）
    data class ModelEntry(
        val filename: String,
        val displayName: String,
        val precision: String,
        val inputSize: Int,
        val outputSize: Int,
        val description: String
    )

    var modelList: List<ModelEntry> = emptyList()
    var selectedModelIndex: Int = 0
}
