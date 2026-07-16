package team.maodie.aimbot.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import team.maodie.aimbot.R
import team.maodie.aimbot.inference.JniCallBack
import team.maodie.aimbot.manager.ConfigManager
import team.maodie.aimbot.manager.LicenseManager
import team.maodie.aimbot.manager.LicenseManager.LicenseInfo
import team.maodie.aimbot.manager.LicenseManager.VerifyResult
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 授权码校验入口。
 *
 * 启动流程：
 *   1. 检查 SharedPreferences 中是否有未过期授权码 → 有则直接跳转 MainActivity
 *   2. 否则展示输入界面，输入并点「验证」
 *   3. 通过 → 写入 SharedPreferences + 跳转 MainActivity
 *   4. 失败 → 在状态卡片显示具体错误（长度 / CRC / 已过期 等）
 *
 * 设计上只接受 launcher 跳转，不暴露 deep link（避免被攻击者直接绕过验证）。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var codeInputLayout: TextInputLayout
    private lateinit var codeInput: TextInputEditText
    private lateinit var pasteBtn: MaterialButton
    private lateinit var verifyBtn: MaterialButton
    private lateinit var getCodeBtn: MaterialButton
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusText: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 每次启动都重新解码保存的 code 并校验过期（不信任任何缓存的过期字段）
        when (val r = LicenseManager.verifySaved(this)) {
            is VerifyResult.Success -> {
                // 自动验证成功 — 和手动登录一样显示状态卡 + 延迟跳转
                setContentView(R.layout.activity_login)
                bindViews()
                wireListeners()
                showAutoVerifySuccess(r.info)
                return
            }
            is VerifyResult.Failure -> {
                // 保存的码已过期 / 失效 — 清掉，进入输入流程
                LicenseManager.clearSaved(this)
            }
        }

        setContentView(R.layout.activity_login)
        bindViews()
        wireListeners()

        // 显示从 MainActivity 跳过来的失败原因（如「授权验证失败：授权码已过期」）
        intent?.getStringExtra(EXTRA_REASON)?.let { reason ->
            if (reason.isNotEmpty()) showStatus(reason, isError = true)
        }
    }

    /**
     * 自动验证成功的 UI：状态卡显示「到期时间 + 剩余时间」，输入框禁用，
     * 后台静默串行 QNN HTP 编译所有 .tflite 模型，立即跳转到 MainActivity。
     * 与手动输入成功路径（onVerifySuccess）的体验保持一致。
     */
    private fun showAutoVerifySuccess(info: LicenseInfo) {
        // 用 server time 算 remaining —— 与 native 校验用的时间源一致
        val serverNow = LicenseManager.getServerTimeSec()
            .takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000L)
        android.util.Log.d(
            "LicenseDebug",
            "auto-verify OK startTs=${info.startTs} hours=${info.hours} expiryTs=${info.expiryTs} " +
                "now(server)=$serverNow now(local)=${System.currentTimeMillis() / 1000L}"
        )
        val expiryFmt = SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault())
            .format(Date(info.expiryTs * 1000))
        val remaining = formatRemaining(info.expiryTs - serverNow)
        android.util.Log.d("LicenseDebug", "showRemaining='$remaining' expiryFmt='$expiryFmt'")

        showStatus(
            getString(R.string.login_success_format, expiryFmt, remaining),
            isError = false
        )
        verifyBtn.isEnabled = false
        codeInput.isEnabled = false
        // Fire-and-forget: kick off the prewarm worker, then immediately move on
        // to MainActivity. The user never sees a "compiling…" overlay; the first
        // model selection inside MainActivity just pays the compile cost if the
        // background thread hasn't reached that model yet.
        startPrewarmInBackground()
        launchMainAndFinish()
    }

    private fun bindViews() {
        codeInputLayout = findViewById(R.id.codeInputLayout)
        codeInput = findViewById(R.id.codeInput)
        pasteBtn = findViewById(R.id.pasteBtn)
        verifyBtn = findViewById(R.id.verifyBtn)
        getCodeBtn = findViewById(R.id.getCodeBtn)
        statusCard = findViewById(R.id.statusCard)
        statusText = findViewById(R.id.statusText)
    }

    private fun wireListeners() {
        codeInput.addTextChangedListener(CodeFormattingWatcher())
        codeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onVerifyClicked()
                true
            } else {
                false
            }
        }

        pasteBtn.setOnClickListener { pasteFromClipboard() }
        verifyBtn.setOnClickListener { onVerifyClicked() }
        getCodeBtn.setOnClickListener { openGetCodeUrl() }
    }

    /**
     * 实时格式化授权码输入：
     *   1. 过滤掉非 [0-9A-Za-z] 字符（混入的空格、换行、复制残留都过滤）
     *   2. 截断到 25 位
     *   3. 按 5 位一组加 - 分隔显示（输入时实时看见）
     *   4. 把光标放回末尾
     */
    private inner class CodeFormattingWatcher : TextWatcher {
        private var selfChange = false

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (selfChange || s == null) return

            val raw = s.toString()
            val clean = raw.filter { it.isLetterOrDigit() }
                .take(CODE_MAX_CHARS)
            val formatted = clean.chunked(CODE_GROUP_SIZE).joinToString("-")

            if (formatted == raw) {
                codeInputLayout.error = null
                if (statusCard.visibility == View.VISIBLE) statusCard.visibility = View.GONE
                return
            }

            val cursorAtEnd = formatted.length
            selfChange = true
            s.replace(0, raw.length, formatted)
            codeInput.setSelection(cursorAtEnd.coerceIn(0, formatted.length))
            selfChange = false

            codeInputLayout.error = null
            if (statusCard.visibility == View.VISIBLE) statusCard.visibility = View.GONE
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!cm.hasPrimaryClip() || cm.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) != true) {
            showStatus("剪贴板为空或非文本内容", isError = true)
            return
        }
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        if (text.isEmpty()) {
            showStatus("剪贴板为空", isError = true)
            return
        }
        codeInput.setText(text)
        // setSelection 由 TextWatcher 设置到格式化末尾
        codeInputLayout.error = null
    }

    private fun onVerifyClicked() {
        val code = codeInput.text?.toString().orEmpty().trim()
        if (code.isEmpty()) {
            codeInputLayout.error = getString(R.string.login_error_empty)
            return
        }
        codeInputLayout.error = null

        when (val result = LicenseManager.verifyAndPersist(this, code)) {
            is VerifyResult.Success -> onVerifySuccess(result.info)
            is VerifyResult.Failure -> showStatus(result.error, isError = true)
        }
    }

    private fun onVerifySuccess(info: LicenseInfo) {
        // native 已经在 verifyAndPersist 成功时把 code 写到了 cache，无需 Kotlin 再写

        val serverNow = LicenseManager.getServerTimeSec()
            .takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000L)
        val expiryFmt = SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault())
            .format(Date(info.expiryTs * 1000))
        val remaining = formatRemaining(info.expiryTs - serverNow)

        showStatus(
            getString(R.string.login_success_format, expiryFmt, remaining),
            isError = false
        )
        verifyBtn.isEnabled = false
        codeInput.isEnabled = false
        // Fire-and-forget: kick off the prewarm worker, then immediately move on
        // to MainActivity. The user never sees a "compiling…" overlay; the first
        // model selection inside MainActivity just pays the compile cost if the
        // background thread hasn't reached that model yet.
        startPrewarmInBackground()
        launchMainAndFinish()
    }

    private fun launchMainAndFinish() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun openGetCodeUrl() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.login_get_code_url)))
            )
        } catch (_: Exception) {
            showStatus("未找到浏览器", isError = true)
        }
    }

    private fun showStatus(msg: String, isError: Boolean) {
        statusText.text = msg
        if (isError) {
            statusCard.setCardBackgroundColor(
                MaterialColors.getColor(statusCard, com.google.android.material.R.attr.colorErrorContainer)
            )
            statusText.setTextColor(
                MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOnErrorContainer)
            )
        } else {
            statusCard.setCardBackgroundColor(
                MaterialColors.getColor(statusCard, com.google.android.material.R.attr.colorTertiaryContainer)
            )
            statusText.setTextColor(
                MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOnTertiaryContainer)
            )
        }
        statusCard.visibility = View.VISIBLE
    }

    private fun formatRemaining(remainingSec: Long): String {
        if (remainingSec <= 0) return "已过期"
        val d = remainingSec / 86400
        val h = (remainingSec % 86400) / 3600
        val m = (remainingSec % 3600) / 60
        return when {
            d > 0 -> "${d} 天 ${h} 小时"
            h > 0 -> "${h} 小时 ${m} 分"
            m > 0 -> "${m} 分"
            else  -> "${remainingSec} 秒"
        }
    }

    // ========== QNN HTP prewarm (silent) ==========
    // Kick off a background single-thread executor that visits every .tflite
    // model in models.json so QNN HTP gets a chance to compile + cache each
    // graph. Fire-and-forget: we don't block the UI, don't show an overlay,
    // and the user lands on MainActivity immediately. First model selection
    // in MainActivity still pays the compile cost if prewarm hasn't reached
    // it yet; subsequent switches hit warmed QnnContext binary on disk.
    private var prewarmExecutor: ExecutorService? = null

    private fun startPrewarmInBackground() {
        val models = listTfliteModelNames()
        // Copy assets → filesDir up front so the worker thread can hand a real
        // path to native immediately. Main-thread I/O is fine; Login is gone
        // before this matters and files are tiny (1–10 MB).
        for (name in models) {
            copyModelAssetToFilesIfNeeded(name)
        }

        // QNN HTP 设备 per-device 互斥：worker 持锁编译时，MainActivity 主线程的
        // JniCallBack.init(当前模型) 必须等它释放。如果按 models.json 顺序串行编，
        // 当前 modelIndex 靠后时 splash 要等到前面所有模型都编完才消失。
        // 修法：把当前选中的模型提到最前面先编，后台再补编其余。
        val ordered = ConfigManager.getConfig().modelIndex
            .coerceIn(0, models.size - 1)
            .let { models.getOrNull(it) }
            ?.let { p -> listOf(p) + models.filter { it != p } }
            ?: models

        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "QnnPrewarm").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
        prewarmExecutor = executor
        executor.execute {
            for (name in ordered) {
                val path = File(filesDir, name).absolutePath
                if (!File(path).exists()) {
                    Log.w("QnnPrewarm", "skip $name (not in filesDir)")
                    continue
                }
                val ok = JniCallBack.prewarmQnn(path)
                Log.i("QnnPrewarm", "prewarm $name -> $ok")
            }
            executor.shutdown()
            prewarmExecutor = null
        }
    }

    /** Returns the filenames of every .tflite entry in assets/models.json. */
    private fun listTfliteModelNames(): List<String> {
        return try {
            val json = assets.open("models.json").bufferedReader().use { it.readText() }
            val arr = JSONObject(json).getJSONArray("models")
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val name = arr.getJSONObject(i).getString("filename")
                if (name.endsWith(".tflite")) out.add(name)
            }
            out
        } catch (e: Exception) {
            Log.w("QnnPrewarm", "failed to parse models.json: ${e.message}")
            emptyList()
        }
    }

    /**
     * Replicates MainActivity.loadModel's asset → filesDir copy on first launch.
     * Native init needs a real on-disk path, not an asset FD.
     */
    private fun copyModelAssetToFilesIfNeeded(filename: String) {
        val modelFile = File(filesDir, filename)
        if (modelFile.exists()) return
        try {
            modelFile.parentFile?.mkdirs()
            assets.open(filename).use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.w("QnnPrewarm", "copy failed for $filename: ${e.message}")
        }
    }

    companion object {
        private const val CODE_MAX_CHARS = 25
        private const val CODE_GROUP_SIZE = 5
        const val EXTRA_REASON = "extra_reason"
    }
}
