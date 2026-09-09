package io.github.love3025.yolovaim.injector

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import io.github.love3025.yolovaim.model.TouchMethod

open class RootInjectorClient(private val context: Context) : TouchInjectorInterface, HudClient {
    companion object {
        private const val TAG = "RootInjector"
        private const val LAT_TAG = "YolovaimLatency"

        /**
         * 每条阻塞往返拆成 write/wait 两半打日志。默认关 —— 开火状态查询是
         * 每帧一次的，开着就是每帧一条 String.format + logd 写。
         *
         * **要和 FloatService.TRACE_LAT 一起翻。** 那边给的是每帧分段的
         * avg/max(看哪一段贵)，这边给的是单次往返的内部拆分(看那一段贵在
         * 哪一半)。本仓的惯例是每个文件各自一个 trace 常量(见
         * FloatService.TRACE / AimController.TRACE)，所以不做成同一个开关。
         */
        private const val TRACE_LAT = false
        // 首次安装时这段等待里包含「用户在 root 管理器上点授权」的人类反应
        // 时间，10s 太紧。connect() 现在跑在自己的线程上（见
        // FloatService.initTouchInjector），等久一点不会拖住推理循环。
        private const val CONNECT_TIMEOUT_MS = 60000L

        /**
         * Root 是否已授权（不是 su 二进制是否存在——是要真的能拿到 uid=0）。
         * 防捕获 HUD 只能由 root daemon 创建，设置页的「防录屏」开关打开前
         * 用它做门禁：拿不到 root 的设备根本不该能把开关打开。
         * MainActivity 的权限检测也走这里，两处判据保持一致。
         * 注意：root 管理器设为"询问"时会弹授权框，阻塞到用户选择为止。
         */
        fun isRootAvailable(): Boolean = try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine() ?: ""
            process.waitFor()
            output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    @Volatile
    private var connected = false
    private var process: Process? = null
    private var daemonStdin: OutputStream? = null
    private var daemonReader: BufferedReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cmdLock = Object()

    // 单线程复用，不是每次调用起一个线程 —— 这条路径是每帧走的。
    //
    // 优先级必须跟推理线程齐平。execCmd 里是 URGENT_DISPLAY(-8) 的推理线程
    // 同步 fut.get() 等这条线程读回包，留默认优先级(0) 就是标准的优先级反转:
    // 实测 GET_FIRE_STATE 的往返 mean=1.21ms 里 wait 占 1.04ms(p95 3.04ms),
    // 而 daemon 那边只是读一个缓存标志 —— 等的不是干活，是这条线程被调度。
    // 必须在线程自己身上调 Process.setThreadPriority: Thread.priority 在
    // Android 上映射不到 Linux nice 值，设了没用。
    private val readerExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            r.run()
        }, "root-daemon-reader").apply { isDaemon = true }
    }

    // daemon 正常回复在 1ms 内（execCmd 里 >0.5ms 就打日志了）。
    // 500ms 足够宽松到不会误伤，又不至于让一帧卡到肉眼可见。
    private val CMD_TIMEOUT_MS = 500L

    // GET_FIRE_STATE 是后加的命令，旧 daemon 不认识。连接时探一次，
    // 不支持就永久回退到 IS_FINGER_IN_FIRE_ZONE，不在热路径上反复试。
    @Volatile private var supportsFireState = false

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
            val ready = awaitReady(CONNECT_TIMEOUT_MS)

            if (!ready) {
                callback.onError("Root daemon READY timeout")
                destroyProcess()
                return
            }

            connected = true

            // 能力探测：新增命令只在这里试一次。旧 daemon 会回 ERR:unknown
            // command，此后热路径永远走已验证的 IS_FINGER_IN_FIRE_ZONE。
            val probe = execCmd("GET_FIRE_STATE")
            supportsFireState = probe != null && probe.startsWith("OK:") &&
                                probe.removePrefix("OK:").trim().toIntOrNull() != null
            Log.d(TAG, "GET_FIRE_STATE supported=$supportsFireState (probe=$probe)")

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

    /**
     * 等 daemon 吐出 READY，整体一个截止时间。
     *
     * 旧实现是 `while (未超 10s) { 每次读 2s }`，有两个问题：
     * 1. 那个 2s 读超时会**抛** TimeoutException，而它没被就地接住，直接穿到
     *    connect() 的 catch(Exception) —— 于是外层 10s 窗口从来没有过第二次
     *    迭代。首次安装等 root 授权框的场景 2 秒就判死，报「su not available」。
     * 2. 就算接住了，每次迭代都会再起一个线程去读同一个 reader，多个线程抢
     *    同一条流，而且全都泄漏在阻塞的 readLine 上。
     *
     * 现在：一条读线程循环读到 READY 或 EOF，外面只等一次。
     */
    private fun awaitReady(timeoutMs: Long): Boolean {
        val future = java.util.concurrent.CompletableFuture<Boolean>()
        Thread({
            var ok = false
            try {
                while (true) {
                    val line = daemonReader?.readLine() ?: break  // EOF
                    Log.d(TAG, "Daemon response: $line")
                    if (line == "READY") { ok = true; break }
                }
            } catch (e: Exception) {
                Log.e(TAG, "awaitReady read error: ${e.message}")
            }
            future.complete(ok)
        }, "root-daemon-ready").start()
        return try {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "awaitReady timeout/error: ${e.message}")
            false
        }
    }

    override fun isConnected(): Boolean = connected && process != null

    /**
     * 阻塞往返，带超时。
     *
     * 超时���是可选的保险：这个函数在推理循环里每帧都被调用，而
     * BufferedReader.readLine() 本身没有超时。daemon 只要有一次没吐出预期的
     * 回复，推理线程就永久停在这里 —— 表现是「应用能打开、不闪退、但完全
     * 没有效果」，没有崩溃也没有异常日志，极难定位（2026-08-31 实测踩过）。
     *
     * 超时后把 connected 置 false：读线程已经卡死在 readLine 上，
     * cancel(true) 对阻塞的 InputStream 读无效，后续命令会排在它后面。
     * readerExec 是单线程的，所以那条卡死的任务会把后面所有 submit 都堵住 ——
     * 这也是必须整条连接判死、而不是重试的原因。
     * 与其每帧再挂一次，不如直接进入断开状态，让上层看到真实情况。
     */
    private fun execCmd(cmd: String, timeoutMs: Long = CMD_TIMEOUT_MS): String? {
        var resp: String? = null
        var t0 = 0L
        var tWrote = 0L
        var tDone = 0L
        synchronized(cmdLock) {
            if (!connected) return null
            t0 = System.nanoTime()
            try {
                daemonStdin!!.write("$cmd\n".toByteArray())
                daemonStdin!!.flush()
                // 把一次往返拆成「写进管道」和「等回复」两半(仅 TRACE_LAT)。
                // FloatService 的 fire/hold 段只能说明整趟慢了，拆开才知道慢在
                // 哪一半：write 慢 = 管道满/daemon 没在读；wait 慢 = daemon 被
                // 调度掐住，或者 readerExec 这条线程醒得晚(它已经和推理线程一样
                // 是 URGENT_DISPLAY，见上面的线程工厂；仍慢就是真的在等 daemon)。
                // 注意这一半仍分不开「daemon 干活慢」和「daemon 没被调度」——
                // 那需要 daemon 回带自己的时间戳，是协议改动，先不做。
                tWrote = if (TRACE_LAT) System.nanoTime() else 0L
                val fut = readerExec.submit<String?> { daemonReader?.readLine() }
                resp = try {
                    fut.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (te: java.util.concurrent.TimeoutException) {
                    fut.cancel(true)
                    Log.e(TAG, "execCmd timeout (${timeoutMs}ms): $cmd")
                    connected = false
                    return null
                }
                tDone = System.nanoTime()
            } catch (e: Exception) {
                Log.e(TAG, "execCmd error: ${e.message}")
                connected = false
                return null
            }
        }
        // 日志一律在锁外打。锁内打等于每帧多占 cmdLock 一次 String.format +
        // 一次 logd socket 写，而排在这把锁后面的正是每帧的 MOVE 注入 ——
        // 仪表会把它想量的那个延迟自己制造出来。原来那条 >0.5ms 的慢命令
        // 日志也在锁内，一并挪出来：内容和触发条件都没变。
        if (tDone != 0L) {
            val dtMs = (tDone - t0) / 1e6
            if (TRACE_LAT) {
                Log.d(LAT_TAG, String.format(
                    java.util.Locale.US, "RootIPC %s write=%.2f wait=%.2f total=%.2fms",
                    cmd.split(" ").firstOrNull() ?: cmd,
                    (tWrote - t0) / 1e6, (tDone - tWrote) / 1e6, dtMs
                ))
            }
            // Log slow commands. 阈值原来是 0.5ms，基于「daemon IPC 通常 <1ms」
            // 这个假设 —— 实测反驳了它: GET_FIRE_STATE p50=0.89ms、mean=1.21ms，
            // 结果这条本该记异常的日志在 8252/9296 = 89% 的帧上触发，等于每帧一次
            // String.format 分配加一次 logd 写，而且不受 TRACE_LAT 控制、正式包里
            // 也在跑。抬到 p90(2.31ms) 之上，恢复它「只记异常」的本意。
            if (dtMs > 3.0) {
                Log.d(LAT_TAG, String.format(java.util.Locale.US, "RootIPC %s = %.2fms", cmd.split(" ").firstOrNull() ?: cmd, dtMs))
            }
        }
        return resp
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
    private fun execNoReply(cmd: String): Boolean {
        synchronized(cmdLock) {
            if (!connected) return false
            try {
                daemonStdin!!.write("!$cmd\n".toByteArray())
                daemonStdin!!.flush()
            } catch (e: Exception) {
                Log.e(TAG, "execNoReply error: ${e.message}")
                connected = false
                return false
            }
        }
        return true
    }

    protected fun sendOk(cmd: String): Boolean = execOk(cmd)
    protected fun sendCmd(cmd: String): String? = execCmd(cmd)
    protected fun sendNoReply(cmd: String): Boolean = execNoReply(cmd)

    // ================= HudClient(防捕获 native HUD)=================
    // 协议见 root_daemon.cpp 头部注释。除 hudOn 外全部走 execNoReply
    // ('!' 前缀):这些调用出现在推理热路径上,不能阻塞等回话。

    override fun hudOn(): Boolean {
        val resp = execCmd("HUD_ON", 3000L) // layer 创建含 binder 往返,给宽裕超时
        return resp?.startsWith("OK") == true
    }

    override fun hudOff() {
        execNoReply("HUD_OFF")
    }

    override fun hudToggle(what: String, on: Boolean) {
        execNoReply("HUD_TOGGLE $what ${if (on) 1 else 0}")
    }

    override fun hudCheck(on: Boolean) {
        execNoReply(if (on) "HUD_CHECK_ON" else "HUD_CHECK_OFF")
    }

    override fun hudGeo(w: Int, h: Int) {
        execNoReply("HUD_GEO $w $h")
    }

    override fun hudFov(r: Int) {
        execNoReply("HUD_FOV $r")
    }

    override fun hudRange(r: Int) {
        execNoReply("HUD_RANGE $r")
    }

    override fun hudBoxes(rects: IntArray) {
        val n = rects.size / 4
        // 20 框上限与 daemon 端一致;每行 ~360 字符,远低于 4096 的行缓冲
        val sb = StringBuilder(32 + n * 24)
        sb.append("HUD_BOXES ").append(n)
        for (v in rects) sb.append(' ').append(v)
        execNoReply(sb.toString())
    }

    override fun hudTextMask(w: Int, h: Int, fg: Int, bg: Int, mask: ByteArray, len: Int) {
        // 头部一行 + 掩码整块,两次 write 一次 flush,全程只抢一次 cmdLock。
        // BufferedOutputStream 对 len >= 缓冲区的写入会直接落到 fd,所以
        // 48KB 的掩码就是一次 write 系统调用(旧 RLE 协议是 270 次)。
        val n = if (len > mask.size) mask.size else len
        if (w <= 0 || h <= 0 || n < w * h) return
        val header = "!HUD_TEXT_MASK $w $h ${"%08X".format(fg)} ${"%08X".format(bg)} $n\n"
        synchronized(cmdLock) {
            if (!connected) return
            try {
                daemonStdin!!.write(header.toByteArray())
                daemonStdin!!.write(mask, 0, n)
                daemonStdin!!.flush()
            } catch (e: Exception) {
                Log.e(TAG, "hudTextMask error: ${e.message}")
                connected = false
            }
        }
    }

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

    /**
     * 一枪 = DOWN + sleep + UP，两条注入命令都走 '!' 不等回话。
     *
     * daemon 的协议头(root_daemon.cpp)本来就把 TRIGGER_DOWN / TRIGGER_UP 列在
     * fire-and-forget 命令里，这边却一直用 execOk 等 OK —— 每枪两次阻塞往返
     * (实测 p90 2.3ms，尾部到 CMD_TIMEOUT_MS=500ms)全压在 tapExecutor 这条线程
     * 上，把 tapInFlight 占得更久；冷却一短就直接把下一枪丢掉。顺序由 cmdLock +
     * daemon 单线程读 stdin 保证，不会乱序。
     *
     * 代价是拿不到 OK：注入失败只能靠带回话的 zone 查询那类命令发现 —— 与
     * MOVE/UP 热路径同一个取舍。写入失败(连接已断)仍会返回 false。
     */
    override fun triggerTap(x: Int, y: Int, durationMs: Int): Boolean {
        if (!execNoReply("TRIGGER_DOWN $x $y")) return false
        if (durationMs > 0) Thread.sleep(durationMs.toLong())
        execNoReply("TRIGGER_UP")
        return true
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

    override fun consumeFireState(): Int {
        if (!supportsFireState) return -1        // 不支持：本地返回，不发 IPC
        val resp = execCmd("GET_FIRE_STATE") ?: return -1
        if (!resp.startsWith("OK:")) return -1
        return resp.removePrefix("OK:").trim().toIntOrNull() ?: -1
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

    override fun setDisplayRotation(rotation: Int) {
        execOk("SET_ORIENTATION $rotation")
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
