package io.github.love3025.yolovaim.injector

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import io.github.love3025.yolovaim.model.TouchMethod

open class RootInjectorClient(private val context: Context) : TouchInjectorInterface {
    companion object {
        private const val TAG = "RootInjector"
        private const val LAT_TAG = "YolovaimLatency"
        private const val CONNECT_TIMEOUT_MS = 10000L
    }

    @Volatile
    private var connected = false
    private var process: Process? = null
    private var daemonStdin: OutputStream? = null
    private var daemonReader: BufferedReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cmdLock = Object()

    override fun connect(callback: InjectorCallback) {
        Log.d(TAG, "Attempting root connection...")
        try {
            val daemonPath = context.applicationInfo.nativeLibraryDir + "/libroot_daemon.so"
            Log.d(TAG, "Daemon path: $daemonPath")

            val pb = ProcessBuilder("su")
            pb.redirectErrorStream(false)
            process = pb.start()
            daemonStdin = process!!.outputStream
            daemonReader = BufferedReader(InputStreamReader(process!!.inputStream))

            // Start the daemon binary
            daemonStdin!!.write("exec $daemonPath\n".toByteArray())
            daemonStdin!!.flush()

            // Wait for READY response with timeout
            val startTime = System.currentTimeMillis()
            var ready = false
            while (System.currentTimeMillis() - startTime < CONNECT_TIMEOUT_MS) {
                // Use a thread to read with timeout
                val line = readLineWithTimeout(2000)
                if (line != null) {
                    Log.d(TAG, "Daemon response: $line")
                    if (line == "READY") {
                        ready = true
                        break
                    }
                }
            }

            if (!ready) {
                callback.onError("Root daemon READY timeout")
                destroyProcess()
                return
            }

            connected = true
            Log.d(TAG, "Root daemon connected")

            // Liveness monitoring thread
            Thread({
                try {
                    val exitCode = process!!.waitFor()
                    Log.w(TAG, "Daemon exited with code $exitCode")
                    connected = false
                    mainHandler.post { callback.onDisconnected() }
                } catch (_: Exception) {}
            }, "root-daemon-monitor").start()

            callback.onConnected()

        } catch (e: Exception) {
            Log.e(TAG, "Root connect error: ${e.message}")
            callback.onError("su not available: ${e.message}")
            destroyProcess()
        }
    }

    private fun readLineWithTimeout(timeoutMs: Long): String? {
        val future = java.util.concurrent.CompletableFuture<String?>()
        Thread({
            try {
                future.complete(daemonReader?.readLine())
            } catch (e: Exception) {
                future.complete(null)
            }
        }).start()
        return future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    override fun isConnected(): Boolean = connected && process != null

    private fun execCmd(cmd: String): String? {
        synchronized(cmdLock) {
            if (!connected) return null
            val t0 = System.nanoTime()
            try {
                daemonStdin!!.write("$cmd\n".toByteArray())
                daemonStdin!!.flush()
                val resp = daemonReader?.readLine()
                val dtMs = (System.nanoTime() - t0) / 1e6
                // Log slow commands. Daemon IPC is usually <1ms; >1ms indicates daemon is behind.
                if (dtMs > 0.5) {
                    Log.d(LAT_TAG, String.format(java.util.Locale.US, "RootIPC %s = %.2fms", cmd.split(" ").firstOrNull() ?: cmd, dtMs))
                }
                return resp
            } catch (e: Exception) {
                Log.e(TAG, "execCmd error: ${e.message}")
                connected = false
                return null
            }
        }
    }

    private fun execOk(cmd: String): Boolean {
        val resp = execCmd(cmd)
        return resp?.startsWith("OK") == true
    }

    /**
     * Send a command without waiting for its response.
     *
     * [execCmd] is a blocking round-trip: it writes, then parks in readLine()
     * until the daemon has finished the uinput write and echoed OK. For the
     * per-frame injection commands that put the inference thread to sleep on
     * another process's scheduling several times per frame, which is exactly
     * the kind of latency that spikes under CPU contention.
     *
     * The '!' prefix tells the daemon to execute the command and emit no
     * response line, so nothing accumulates in the pipe for a later read to
     * trip over. Ordering is fully preserved: cmdLock serialises writers here,
     * and the daemon consumes stdin from a single thread, so a fire-and-forget
     * command is still executed strictly before whatever is sent after it.
     * Only void commands are eligible — anything that returns a value still
     * goes through [execCmd].
     */
    private fun execNoReply(cmd: String) {
        synchronized(cmdLock) {
            if (!connected) return
            try {
                daemonStdin!!.write("!$cmd\n".toByteArray())
                daemonStdin!!.flush()
            } catch (e: Exception) {
                Log.e(TAG, "execNoReply error: ${e.message}")
                connected = false
            }
        }
    }

    protected fun sendOk(cmd: String): Boolean = execOk(cmd)
    protected fun sendCmd(cmd: String): String? = execCmd(cmd)

    override fun tap(x: Int, y: Int) {
        execOk("DOWN $x $y")
        Thread.sleep(8)
        execOk("UP")
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        execOk("DOWN $x1 $y1")
        if (durationMs > 0) {
            // Complete swipe: move in steps then lift
            val steps = maxOf(1, durationMs / 8)
            for (i in 1..steps) {
                val cx = x1 + (x2 - x1) * i / steps
                val cy = y1 + (y2 - y1) * i / steps
                execOk("MOVE $cx $cy")
                Thread.sleep(8)
            }
            execOk("UP")
        }
        // durationMs == 0: stay down (caller will moveTo + lift later)
    }

    // Per-frame during aiming — fire-and-forget so the inference loop is not
    // blocked on the daemon's round-trip.
    override fun moveTo(x: Int, y: Int) {
        execNoReply("MOVE $x $y")
    }

    override fun lift() {
        execNoReply("UP")
    }

    override fun keepAlive() {
        execOk("KEEP_ALIVE")
    }

    override fun triggerDown(x: Int, y: Int) {
        execOk("TRIGGER_DOWN $x $y")
    }

    override fun triggerUp() {
        execOk("TRIGGER_UP")
    }

    override fun triggerTap(x: Int, y: Int, durationMs: Int) {
        execOk("TRIGGER_DOWN $x $y")
        if (durationMs > 0) Thread.sleep(durationMs.toLong())
        execOk("TRIGGER_UP")
    }

    override fun setTriggerZone(left: Int, top: Int, right: Int, bottom: Int) {
        execOk("SET_TRIGGER_ZONE $left $top $right $bottom")
    }

    override fun isFingerInTriggerZone(): Boolean {
        val resp = execCmd("IS_FINGER_IN_ZONE")
        return resp == "OK:1"
    }

    override fun setFireZone(left: Int, top: Int, right: Int, bottom: Int) {
        execOk("SET_FIRE_ZONE $left $top $right $bottom")
    }

    override fun isFingerInFireZone(): Boolean {
        val resp = execCmd("IS_FINGER_IN_FIRE_ZONE")
        return resp == "OK:1"
    }

    override fun setJoystickZone(left: Int, top: Int, right: Int, bottom: Int) {
        execOk("SET_JOYSTICK_ZONE $left $top $right $bottom")
    }

    override fun isFingerInJoystickZone(): Boolean {
        val resp = execCmd("IS_FINGER_IN_JOYSTICK_ZONE")
        return resp == "OK:1"
    }

    override fun liftJoystickFinger(): Boolean {
        val resp = execCmd("LIFT_JOYSTICK_FINGER")
        return resp == "OK:1"
    }

    override fun setInputMethod(method: TouchMethod) {
        // Root always uses uinput, method param ignored
    }

    override fun initRemote(): Boolean {
        return execOk("OPEN_UINPUT")
    }

    protected open fun openUinput(): Boolean = execOk("OPEN_UINPUT")
    protected open fun openRealPanel(): Boolean = execOk("OPEN_REAL_PANEL")

    override fun setResolution(screenW: Int, screenH: Int, devW: Int, devH: Int) {
        execOk("SET_RESOLUTION $screenW $screenH")
        execOk("SET_DEVICE_RESOLUTION $devW $devH")
    }

    override fun setOrientationConfig(landscapeStart: Boolean) {
        execOk("SET_ORIENTATION ${if (landscapeStart) 1 else 0}")
    }

    override fun startGeteventListener() {
        execOk("START_GETEVENT")
    }

    override fun stopGeteventListener() {
        execOk("STOP_GETEVENT")
    }

    override fun blockPhysicalTouch() {
        // Not implemented for root mode
    }

    override fun unblockPhysicalTouch() {
        // Not implemented for root mode
    }

    override fun destroyRemote() {
        execOk("DESTROY")
        connected = false
    }

    override fun queryDeviceAbs(devicePath: String, axis: Int): IntArray {
        // For root mode, use the already-detected device values
        return intArrayOf(0, g_dev_abs_max_for_axis(axis))
    }

    private fun g_dev_abs_max_for_axis(axis: Int): Int {
        return when (axis) {
            0x35 -> 21199 // ABS_MT_POSITION_X
            0x36 -> 29999 // ABS_MT_POSITION_Y
            else -> 0
        }
    }

    override fun findTouchDevice(): String? {
        // The daemon detects the touch device internally
        return "/dev/input/event0"
    }

    override fun disconnect() {
        try {
            execOk("DESTROY")
        } catch (_: Exception) {}
        connected = false
        destroyProcess()
    }

    private fun destroyProcess() {
        try {
            daemonStdin?.close()
            daemonReader?.close()
            process?.destroy()
        } catch (_: Exception) {}
        process = null
        daemonStdin = null
        daemonReader = null
    }
}
