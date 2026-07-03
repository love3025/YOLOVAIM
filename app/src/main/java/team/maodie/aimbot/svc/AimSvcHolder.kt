package team.maodie.aimbot.svc

import android.content.Context
import android.util.Log
import team.maodie.aimbot.svc.client.AimSvc
import java.util.concurrent.Executors

object AimSvcHolder {

    private const val TAG = "AimSvcHolder"
    private const val PREFS = "aimbot_svc_prefs"
    private const val KEY_HOST = "adb_pairing_host"
    private const val KEY_PORT = "adb_pairing_port"

    sealed class State {
        object NotPaired : State()
        data class Paired(val host: String, val port: Int) : State()
        data class Running(val host: String, val port: Int, val pid: Int?) : State()
        data class Error(val message: String) : State()
    }

    @Volatile
    var state: State = State.NotPaired
        private set

    private var listener: ((State) -> Unit)? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()

    fun setListener(l: ((State) -> Unit)?) { listener = l }

    private fun updateState(newState: State) {
        state = newState
        listener?.invoke(newState)
    }

    fun bootstrap(context: Context) {
        // 1. 内嵌服务是否已在跑（PID 2000 进程通过 binder 已投递）
        if (AimSvc.pingBinder()) {
            val pid = readPid()
            val (host, port) = readPairing(context) ?: "" to 0
            updateState(State.Running(host, port, pid))
            return
        }

        // 2. 是否之前配过对但服务没启起来
        val (host, port) = readPairing(context) ?: return
        if (host.isNotEmpty() && port > 0) {
            updateState(State.Paired(host, port))
            return
        }

        // 3. 全新状态
        updateState(State.NotPaired)
    }

    fun markPaired(context: Context, host: String, port: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .apply()
        updateState(State.Paired(host, port))
    }

    fun markRunning(context: Context, pid: Int?) {
        val (host, port) = readPairing(context) ?: "" to 0
        updateState(State.Running(host, port, pid))
    }

    fun markStopped(context: Context) {
        val (host, port) = readPairing(context) ?: "" to 0
        if (host.isNotEmpty() && port > 0) {
            updateState(State.Paired(host, port))
        } else {
            updateState(State.NotPaired)
        }
    }

    fun markError(msg: String) {
        updateState(State.Error(msg))
    }

    fun clearPairing(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_HOST)
            .remove(KEY_PORT)
            .apply()
        updateState(State.NotPaired)
    }

    fun startAsync(context: Context) {
        ioExecutor.execute {
            val pid = AimSvcLauncher.start(context)
            if (pid != null) {
                markRunning(context, pid)
            } else {
                markError("启动失败：查看 logcat")
            }
        }
    }

    fun stopAsync(context: Context) {
        ioExecutor.execute {
            val ok = AimSvcLauncher.stop(context)
            if (ok) {
                markStopped(context)
            } else {
                markError("停止失败")
            }
        }
    }

    private fun readPairing(context: Context): Pair<String, Int>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val port = prefs.getInt(KEY_PORT, -1)
        return if (port > 0) host to port else null
    }

    private fun readPid(): Int? = try {
        val proc = Runtime.getRuntime().exec(arrayOf("pidof", "aimbot_svc"))
        proc.inputStream.bufferedReader().readLine()?.trim()?.split(" ")?.firstOrNull()?.toIntOrNull()
    } catch (e: Exception) {
        Log.w(TAG, "pidof failed: ${e.message}")
        null
    }
}