package io.github.love3025.yolovaim.service

import android.content.Context
import android.util.Log
import io.github.love3025.yolovaim.injector.InjectorCallback
import io.github.love3025.yolovaim.injector.InputManagerInjectorClient
import io.github.love3025.yolovaim.injector.RootInjectorClient
import io.github.love3025.yolovaim.injector.ShizukuInjectorClient
import io.github.love3025.yolovaim.injector.TouchInjectorInterface
import io.github.love3025.yolovaim.model.TouchMethod
import io.github.love3025.yolovaim.util.ProjectionHolder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TouchService(private val context: Context) : TouchInjectorInterface {
    companion object {
        private const val TAG = "TouchService"
    }

    // Trigger taps are dispatched here instead of running on the caller's
    // thread. Both injector backends implement triggerTap as
    // down -> sleep(durationMs) -> up, and both sleep on the *calling* thread:
    // RootInjectorClient does it directly, and ShizukuInjectorClient inherits
    // it because RemoteInjectorService.triggerTap sleeps inside a synchronous
    // binder transaction. The caller is the inference loop, so every shot froze
    // detection and aim tracking for up to 50ms — precisely while the user is
    // shooting. Sequencing still holds: a single thread keeps DOWN/UP pairs
    // ordered, and the trigger finger uses its own uinput slot, so a concurrent
    // aim move cannot disturb it.
    //
    // Recreated on reconnect: connect() after disconnect() is a normal flow
    // here (FloatService tears the injector down and rebuilds it), and a
    // shut-down executor would silently reject every tap from then on.
    @Volatile
    private var tapExecutor: java.util.concurrent.ExecutorService = newTapExecutor()

    private fun newTapExecutor(): java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "yolovaim-tap").apply { isDaemon = true }
        }

    // Guards against pile-up if cooldown is ever configured below the touch
    // duration. Previously the blocking call rate-limited this implicitly.
    private val tapInFlight = AtomicBoolean(false)

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    var state = ConnectionState.DISCONNECTED
        private set
    var onStateChanged: ((ConnectionState) -> Unit)? = null

    private var delegate: TouchInjectorInterface? = null

    private fun updateState(newState: ConnectionState) {
        state = newState
        onStateChanged?.invoke(newState)
    }

    override fun connect(callback: InjectorCallback) {
        if (tapExecutor.isShutdown) tapExecutor = newTapExecutor()
        tapInFlight.set(false)
        updateState(ConnectionState.CONNECTING)
        when (ProjectionHolder.selectedTouchMethod) {
            TouchMethod.INPUT_MANAGER -> connectInputManager(callback)
            TouchMethod.UINPUT -> connectUinput(callback)
        }
    }

    private fun connectInputManager(callback: InjectorCallback) {
        try {
            val client = InputManagerInjectorClient(context)
            client.connect(object : InjectorCallback {
                override fun onConnected() {
                    delegate = client
                    updateState(ConnectionState.CONNECTED)
                    callback.onConnected()
                }
                override fun onDisconnected() {
                    delegate = null
                    updateState(ConnectionState.DISCONNECTED)
                    callback.onDisconnected()
                }
                override fun onError(msg: String) {
                    Log.e(TAG, "InputManager failed: $msg")
                    updateState(ConnectionState.ERROR)
                    callback.onError("InputManager failed: $msg")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "InputManager init failed: ${e.message}")
            updateState(ConnectionState.ERROR)
            callback.onError("InputManager init failed: ${e.message}")
        }
    }

    private fun connectUinput(callback: InjectorCallback) {
        try {
            val rootClient = RootInjectorClient(context)
            rootClient.connect(object : InjectorCallback {
                override fun onConnected() {
                    delegate = rootClient
                    updateState(ConnectionState.CONNECTED)
                    callback.onConnected()
                }
                override fun onDisconnected() {
                    delegate = null
                    updateState(ConnectionState.DISCONNECTED)
                    callback.onDisconnected()
                }
                override fun onError(msg: String) {
                    Log.d(TAG, "Root not available ($msg), trying Shizuku...")
                    try {
                        val shizukuClient = ShizukuInjectorClient(context)
                        shizukuClient.connect(object : InjectorCallback {
                            override fun onConnected() {
                                delegate = shizukuClient
                                updateState(ConnectionState.CONNECTED)
                                callback.onConnected()
                            }
                            override fun onDisconnected() {
                                delegate = null
                                updateState(ConnectionState.DISCONNECTED)
                                callback.onDisconnected()
                            }
                            override fun onError(msg2: String) {
                                Log.e(TAG, "Shizuku also failed: $msg2")
                                updateState(ConnectionState.ERROR)
                                callback.onError("Both root and Shizuku failed")
                            }
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Shizuku init failed: ${e.message}")
                        updateState(ConnectionState.ERROR)
                        callback.onError("Shizuku init failed: ${e.message}")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Root init failed: ${e.message}")
            callback.onError("Root init failed: ${e.message}")
        }
    }

    // --- 触摸操作：转发给 delegate ---

    override fun tap(x: Int, y: Int) { delegate?.tap(x, y) }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        delegate?.swipe(x1, y1, x2, y2, durationMs)
    }

    override fun moveTo(x: Int, y: Int) { delegate?.moveTo(x, y) }

    override fun lift() { delegate?.lift() }

    override fun keepAlive() { delegate?.keepAlive() }

    override fun triggerDown(x: Int, y: Int) { delegate?.triggerDown(x, y) }

    override fun triggerUp() { delegate?.triggerUp() }

    override fun triggerTap(x: Int, y: Int, durationMs: Int) {
        val d = delegate ?: return
        if (!tapInFlight.compareAndSet(false, true)) return
        try {
            tapExecutor.execute {
                try { d.triggerTap(x, y, durationMs) }
                catch (e: Exception) { Log.e(TAG, "triggerTap: ${e.message}") }
                finally { tapInFlight.set(false) }
            }
        } catch (e: Exception) {
            tapInFlight.set(false)
            Log.e(TAG, "triggerTap dispatch: ${e.message}")
        }
    }

    override fun blockPhysicalTouch() { delegate?.blockPhysicalTouch() }

    override fun unblockPhysicalTouch() { delegate?.unblockPhysicalTouch() }

    // --- 配置/查询/生命周期：转发 ---

    override fun isConnected(): Boolean = delegate?.isConnected() ?: false

    override fun disconnect() {
        tapExecutor.shutdownNow()
        delegate?.disconnect()
        delegate = null
        updateState(ConnectionState.DISCONNECTED)
    }

    override fun setInputMethod(method: TouchMethod) { delegate?.setInputMethod(method) }

    override fun initRemote(): Boolean = delegate?.initRemote() ?: false

    override fun setResolution(screenW: Int, screenH: Int, devW: Int, devH: Int) {
        delegate?.setResolution(screenW, screenH, devW, devH)
    }

    override fun setOrientationConfig(landscapeStart: Boolean) {
        delegate?.setOrientationConfig(landscapeStart)
    }

    override fun startGeteventListener() { delegate?.startGeteventListener() }

    override fun stopGeteventListener() { delegate?.stopGeteventListener() }

    override fun setTriggerZone(left: Int, top: Int, right: Int, bottom: Int) {
        delegate?.setTriggerZone(left, top, right, bottom)
    }

    override fun isFingerInTriggerZone(): Boolean = delegate?.isFingerInTriggerZone() ?: false

    override fun setFireZone(left: Int, top: Int, right: Int, bottom: Int) {
        delegate?.setFireZone(left, top, right, bottom)
    }

    override fun isFingerInFireZone(): Boolean = delegate?.isFingerInFireZone() ?: false

    override fun consumeFireState(): Int = delegate?.consumeFireState() ?: -1

    override fun setJoystickZone(left: Int, top: Int, right: Int, bottom: Int) {
        delegate?.setJoystickZone(left, top, right, bottom)
    }

    override fun isFingerInJoystickZone(): Boolean = delegate?.isFingerInJoystickZone() ?: false

    override fun liftJoystickFinger(): Boolean = delegate?.liftJoystickFinger() ?: false

    override fun destroyRemote() { delegate?.destroyRemote() }

    override fun queryDeviceAbs(devicePath: String, axis: Int): IntArray =
        delegate?.queryDeviceAbs(devicePath, axis) ?: intArrayOf(0, 0)

    override fun findTouchDevice(): String? = delegate?.findTouchDevice()
}
