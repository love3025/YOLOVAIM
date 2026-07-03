package team.maodie.aimbot.svc

import android.content.Context
import android.util.Log
import java.io.File

object AimSvcLauncher {

    private const val TAG = "AimSvcLauncher"
    private const val SERVER_PROCESS = "aimbot_svc"
    private const val TMP_SO_PATH = "/data/local/tmp/libaimbot_svc.so"

    fun start(context: Context): Int? {
        val apkPath = context.applicationInfo.sourceDir
        val libDir = context.applicationInfo.nativeLibraryDir
        val soSrc = File(libDir, "libaimbot_svc.so")

        if (!soSrc.exists()) {
            Log.e(TAG, "libaimbot_svc.so not found at $libDir")
            return null
        }

        // 1. 拷贝 .so 到 /data/local/tmp/ （adb shell uid 2000 在该目录可写）
        val cpCmd = "cp -f '$soSrc' $TMP_SO_PATH && chmod 755 $TMP_SO_PATH"
        if (!runShell(cpCmd)) {
            Log.e(TAG, "failed to copy libaimbot_svc.so to /data/local/tmp")
            return null
        }

        // 2. 启动 starter；starter 内部 fork+exec app_process
        val execCmd = "exec $TMP_SO_PATH --apk='$apkPath'"
        Log.i(TAG, "launching: $execCmd")
        val proc = try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", execCmd))
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: ${e.message}")
            return null
        }

        // 等几秒让 starter fork 出 app_process
        Thread.sleep(2500)

        val pid = pidofServer()
        if (pid != null) {
            Log.i(TAG, "aimbot_svc started, pid=$pid")
        } else {
            Log.w(TAG, "aimbot_svc not running after launch; stderr=${proc.errorStream.bufferedReader().readText()}")
        }
        return pid
    }

    fun stop(context: Context): Boolean {
        val pid = pidofServer() ?: return true
        return runShell("kill -TERM $pid")
    }

    fun isRunning(): Boolean = pidofServer() != null

    private fun pidofServer(): Int? = try {
        val proc = Runtime.getRuntime().exec(arrayOf("pidof", SERVER_PROCESS))
        val line = proc.inputStream.bufferedReader().readLine()?.trim()
        line?.split(" ")?.firstOrNull()?.toIntOrNull()
    } catch (e: Exception) {
        null
    }

    private fun runShell(cmd: String): Boolean = try {
        val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        proc.waitFor() == 0
    } catch (e: Exception) {
        Log.e(TAG, "shell exec failed: ${e.message}")
        false
    }
}