package io.github.love3025.yolovaim.model

import android.graphics.RectF

data class DetectionInfo(val rect: RectF, val classId: Int, val className: String)
