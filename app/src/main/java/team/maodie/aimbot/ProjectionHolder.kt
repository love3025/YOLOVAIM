package team.maodie.aimbot

import android.content.Intent
import android.media.projection.MediaProjection

object ProjectionHolder {
    var resultCode: Int = -1
    var resultData: Intent? = null
    var mediaProjection: MediaProjection? = null
}