package team.maodie.aimbot.svc.ktx

import android.util.Log

@PublishedApi internal const val DEFAULT_TAG = "AimSvc"

inline fun logd(tag: String = DEFAULT_TAG, msg: String) {
    if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, msg)
}

inline fun logi(tag: String = DEFAULT_TAG, msg: String) {
    Log.i(tag, msg)
}

inline fun logw(tag: String = DEFAULT_TAG, msg: String, tr: Throwable? = null) {
    if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
}
