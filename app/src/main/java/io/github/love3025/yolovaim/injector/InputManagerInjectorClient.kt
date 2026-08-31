package io.github.love3025.yolovaim.injector

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku
import io.github.love3025.yolovaim.IRemoteInjector
import io.github.love3025.yolovaim.model.TouchMethod
import io.github.love3025.yolovaim.service.RemoteInjectorService
import java.io.BufferedReader
import java.io.InputStreamReader

class InputManagerInjectorClient(private val context: Context) : TouchInjectorInterface {
    companion object {
        private const val TAG = "InputMgrInjector"
        private const val CONNECT_TIMEOUT_MS = 10000L
    }

    @Volatile
    private var connected = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var remoteService: IRemoteInjector? = null

    @SuppressLint("PrivateApi")
    override fun connect(callback: InjectorCallback) {
        Log.d(TAG, "Attempting InputManager connection...")

        val permResult = Shizuku.checkSelfPermission()
        val pingOk = Shizuku.pingBinder()
        Log.d(TAG, "Shizuku ping=$pingOk perm=$permResult")

        if (!pingOk) {
            callback.onError("Shizuku not running")
            return
        }
        if (permResult != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            callback.onError("Shizuku permission not granted")
            return
        }

        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context, RemoteInjectorService::class.java)
            )
                .daemon(false)
                .processNameSuffix("inputmgr")
                .debuggable(true)
                .version(1)

            val conn = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    Log.d(TAG, "onServiceConnected")
                    connected = true
                    remoteService = IRemoteInjector.Stub.asInterface(service)
                    // Set InputManager mode before init
                    try {
                        remoteService?.setInputMethod(TouchMethod.INPUT_MANAGER.ordinal)
                    } catch (e: Exception) {
                        Log.e(TAG, "setInputMethod error: ${e.message}")
                    }
                    callback.onConnected()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    Log.w(TAG, "Service disconnected")
                    connected = false
                    remoteService = null
                    callback.onDisconnected()
                }
            }

            Shizuku.bindUserService(args, conn)

            mainHandler.postDelayed({
                if (!connected) {
                    Log.e(TAG, "Connection timeout")
                    connected = false
                    callback.onError("Connection timeout")
                }
            }, CONNECT_TIMEOUT_MS)

        } catch (e: Exception) {
            Log.e(TAG, "connect error: ${e.message}")
            callback.onError("Connect error: ${e.message}")
        }
    }

    override fun isConnected(): Boolean = connected && remoteService != null

    override fun tap(x: Int, y: Int) {
        try { remoteService?.tap(x, y) } catch (e: Exception) { Log.e(TAG, "tap: ${e.message}") }
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        try { remoteService?.swipe(x1, y1, x2, y2, durationMs) } catch (e: Exception) { Log.e(TAG, "swipe: ${e.message}") }
    }

    override fun moveTo(x: Int, y: Int) {
        try { remoteService?.moveTo(x, y) } catch (e: Exception) { Log.e(TAG, "moveTo: ${e.message}") }
    }

    override fun lift() {
        try { remoteService?.lift() } catch (e: Exception) { Log.e(TAG, "lift: ${e.message}") }
    }

    override fun keepAlive() {
        try { remoteService?.keepAlive() } catch (e: Exception) { Log.e(TAG, "keepAlive: ${e.message}") }
    }

    override fun triggerDown(x: Int, y: Int) {
        try { remoteService?.triggerDown(x, y) } catch (e: Exception) { Log.e(TAG, "triggerDown: ${e.message}") }
    }

    override fun triggerUp() {
        try { remoteService?.triggerUp() } catch (e: Exception) { Log.e(TAG, "triggerUp: ${e.message}") }
    }

    override fun triggerTap(x: Int, y: Int, durationMs: Int) {
        try { remoteService?.triggerTap(x, y, durationMs) } catch (e: Exception) { Log.e(TAG, "triggerTap: ${e.message}") }
    }

    override fun setTriggerZone(left: Int, top: Int, right: Int, bottom: Int) {
        try { remoteService?.setTriggerZone(left, top, right, bottom) } catch (e: Exception) { Log.e(TAG, "setTriggerZone: ${e.message}") }
    }

    override fun isFingerInTriggerZone(): Boolean {
        return try { remoteService?.isFingerInTriggerZone() ?: false } catch (e: Exception) { Log.e(TAG, "isFingerInTriggerZone: ${e.message}"); false }
    }

    override fun setFireZone(left: Int, top: Int, right: Int, bottom: Int) {
        try { remoteService?.setFireZone(left, top, right, bottom) } catch (e: Exception) { Log.e(TAG, "setFireZone: ${e.message}") }
    }

    override fun isFingerInFireZone(): Boolean {
        return try { remoteService?.isFingerInFireZone() ?: false } catch (e: Exception) { Log.e(TAG, "isFingerInFireZone: ${e.message}"); false }
    }

    override fun consumeFireState(): Int {
        return try { remoteService?.consumeFireState() ?: -1 } catch (e: Exception) { Log.e(TAG, "consumeFireState: ${e.message}"); -1 }
    }

    override fun setJoystickZone(left: Int, top: Int, right: Int, bottom: Int) {
        try { remoteService?.setJoystickZone(left, top, right, bottom) } catch (e: Exception) { Log.e(TAG, "setJoystickZone: ${e.message}") }
    }

    override fun isFingerInJoystickZone(): Boolean {
        return try { remoteService?.isFingerInJoystickZone() ?: false } catch (e: Exception) { Log.e(TAG, "isFingerInJoystickZone: ${e.message}"); false }
    }

    override fun liftJoystickFinger(): Boolean {
        return try { remoteService?.liftJoystickFinger() ?: false } catch (e: Exception) { Log.e(TAG, "liftJoystickFinger: ${e.message}"); false }
    }

    override fun setInputMethod(method: TouchMethod) {
        try {
            remoteService?.setInputMethod(method.ordinal)
            Log.d(TAG, "setInputMethod: $method")
        } catch (e: Exception) { Log.e(TAG, "setInputMethod: ${e.message}") }
    }

    override fun initRemote(): Boolean {
        return try {
            val ok = remoteService?.init() ?: false
            if (ok) {
                remoteService?.linkToDeath(android.os.Binder())
            }
            ok
        } catch (e: Exception) { Log.e(TAG, "initRemote: ${e.message}"); false }
    }

    override fun setResolution(screenW: Int, screenH: Int, devW: Int, devH: Int) {
        try {
            remoteService?.setResolution(screenW, screenH, devW, devH)
            Log.d(TAG, "setResolution: ${screenW}x${screenH}")
        } catch (e: Exception) { Log.e(TAG, "setResolution: ${e.message}") }
    }

    override fun setOrientationConfig(landscapeStart: Boolean) {
        try {
            remoteService?.setOrientationConfig(landscapeStart)
            Log.d(TAG, "setOrientationConfig: $landscapeStart")
        } catch (e: Exception) { Log.e(TAG, "setOrientationConfig: ${e.message}") }
    }

    override fun startGeteventListener() {
        try { remoteService?.startGeteventListener() } catch (e: Exception) { Log.e(TAG, "startGeteventListener: ${e.message}") }
    }

    override fun stopGeteventListener() {
        try { remoteService?.stopGeteventListener() } catch (e: Exception) { Log.e(TAG, "stopGeteventListener: ${e.message}") }
    }

    override fun blockPhysicalTouch() {
        try { remoteService?.blockPhysicalTouch() } catch (e: Exception) { Log.e(TAG, "blockPhysicalTouch: ${e.message}") }
    }

    override fun unblockPhysicalTouch() {
        try { remoteService?.unblockPhysicalTouch() } catch (e: Exception) { Log.e(TAG, "unblockPhysicalTouch: ${e.message}") }
    }

    override fun destroyRemote() {
        try {
            val m = remoteService?.javaClass?.getMethod("destroy")
            m?.invoke(remoteService)
        } catch (e: Exception) { Log.e(TAG, "destroyRemote: ${e.message}") }
    }

    override fun queryDeviceAbs(devicePath: String, axis: Int): IntArray {
        try {
            val p = Runtime.getRuntime().exec("getevent -p $devicePath")
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.contains(String.format(" %04x ", axis))) {
                    val parts = line!!.split(",")
                    var min = 0; var max = 0
                    for (part in parts) {
                        val trimmed = part.trim()
                        if (trimmed.startsWith("min")) min = trimmed.split(" ")[1].toInt()
                        if (trimmed.startsWith("max")) max = trimmed.split(" ")[1].toInt()
                    }
                    reader.close()
                    return intArrayOf(min, max)
                }
            }
            reader.close()
        } catch (e: Exception) { Log.e(TAG, "queryDeviceAbs: ${e.message}") }
        return intArrayOf(0, 0)
    }

    override fun findTouchDevice(): String? {
        try {
            val p = Runtime.getRuntime().exec("getevent -p")
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                // NOTE: 遗留过滤器，同 touch_core.cpp 的说明——虚拟设备现已不再
                // 使用品牌名命名，该条件恒为真。保留以维持行为不变。
                if (line!!.contains("ABS_X") && line!!.contains("ABS_Y") && !line!!.contains("YOLOVAIM")) {
                    val idx = line!!.indexOf("/dev/input")
                    if (idx >= 0) {
                        val path = line!!.substring(idx).split(" ")[0].trim()
                        reader.close()
                        return path
                    }
                }
            }
            reader.close()
        } catch (e: Exception) { Log.e(TAG, "findTouchDevice: ${e.message}") }
        return null
    }

    override fun disconnect() {
        connected = false
        remoteService = null
        try {
            Shizuku.unbindUserService(
                Shizuku.UserServiceArgs(
                    ComponentName(context, RemoteInjectorService::class.java)
                ).processNameSuffix("inputmgr"),
                null, true
            )
        } catch (e: Exception) { Log.w(TAG, "unbind error: ${e.message}") }
    }
}
