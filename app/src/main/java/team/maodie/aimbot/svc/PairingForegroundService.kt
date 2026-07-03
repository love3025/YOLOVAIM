package team.maodie.aimbot.svc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import team.maodie.aimbot.R
import team.maodie.aimbot.svc.adb.AdbKey
import team.maodie.aimbot.svc.adb.AdbMdns
import team.maodie.aimbot.svc.adb.AdbPairingClient
import team.maodie.aimbot.svc.adb.PreferenceAdbKeyStore

class PairingForegroundService : Service() {

    companion object {
        private const val TAG = "PairingService"
        private const val CHANNEL_ID = "aimbot_pairing"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "team.maodie.aimbot.svc.action.START_PAIRING"
        const val EXTRA_PAIR_CODE = "pair_code"

        const val BROADCAST_FOUND = "team.maodie.aimbot.svc.broadcast.PAIR_FOUND"
        const val BROADCAST_SUCCESS = "team.maodie.aimbot.svc.broadcast.PAIR_OK"
        const val BROADCAST_FAILURE = "team.maodie.aimbot.svc.broadcast.PAIR_FAIL"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_ERROR = "error"
    }

    private var pairCode: String = ""
    private var adbMdns: AdbMdns? = null
    private var portObserver: Observer<Int>? = null
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("aimbot_svc_prefs", Context.MODE_PRIVATE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("等待无线调试设备…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return START_NOT_STICKY
        if (intent.action != ACTION_START) return START_NOT_STICKY

        pairCode = intent.getStringExtra(EXTRA_PAIR_CODE).orEmpty()
        if (pairCode.length != 6) {
            broadcastFailure("配对码必须是 6 位")
            stopSelf()
            return START_NOT_STICKY
        }

        startMdnsDiscovery()
        return START_STICKY
    }

    private fun startMdnsDiscovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            broadcastFailure("需要 Android 11+")
            stopSelf()
            return
        }
        updateNotification("正在搜索无线调试设备…")

        val obs = Observer<Int> { port ->
            if (port > 0) {
                adbMdns?.stop()
                performPairing(port)
            }
        }
        portObserver = obs
        adbMdns = AdbMdns(this, "_adb-tls-pairing._tcp", obs).also { it.start() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun performPairing(port: Int) {
        updateNotification("正在配对 (端口 $port)…")
        val host = "127.0.0.1"
        val store = PreferenceAdbKeyStore(prefs)
        val key = AdbKey(store, "aimbot")

        try {
            AdbPairingClient(host, port, pairCode, key).use { client ->
                val ok = client.start()
                if (ok) {
                    Log.i(TAG, "pairing succeeded")
                    AimSvcHolder.markPaired(this, host, port)
                    sendBroadcast(Intent(BROADCAST_SUCCESS)
                        .putExtra(EXTRA_HOST, host)
                        .putExtra(EXTRA_PORT, port))
                } else {
                    broadcastFailure("配对失败：协议错误")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "pairing error: ${e.message}", e)
            broadcastFailure("配对异常：${e.message}")
        } finally {
            stopSelf()
        }
    }

    private fun broadcastFailure(msg: String) {
        Log.w(TAG, msg)
        AimSvcHolder.markError(msg)
        sendBroadcast(Intent(BROADCAST_FAILURE).putExtra(EXTRA_ERROR, msg))
    }

    override fun onDestroy() {
        adbMdns?.stop()
        adbMdns = null
        portObserver = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "无线调试配对",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "通过 Android 无线调试激活内嵌 AimSvc"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = openIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AimSvc 配对")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}