package team.maodie.aimbot

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    data class ModelInfo(
        val filename: String,
        val displayName: String,
        val precision: String,
        val inputSize: Int,
        val outputSize: Int,
        val description: String
    )

    private var modelList: List<ModelInfo> = emptyList()
    private var selectedModelIndex = 0

    // 缓存density避免重复访问
    private val displayDensity: Float by lazy { resources.displayMetrics.density }

    // ── Shizuku 状态 ──────────────────────────
    private var shizukuGranted = false
    private var shizukuRunning = false

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQ_SHIZUKU) {
            shizukuGranted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.d("Shizuku", "permission result: $grantResult, granted=$shizukuGranted")
            if (shizukuGranted) {
                runOnUiThread { recreate() }
            }
        }
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("AimbotInfer", "captureLauncher result: resultCode=${result.resultCode}, data=${result.data}, result=${result}")
        val data = result.data
        if (data != null) {
            Log.d("AimbotInfer", "captureLauncher data not null, starting FloatService")
            ProjectionHolder.resultCode = result.resultCode
            ProjectionHolder.resultData = data
            // 同步模型列表到服务
            ProjectionHolder.modelList = modelList.map { m ->
                ProjectionHolder.ModelEntry(m.filename, m.displayName, m.precision, m.inputSize, m.outputSize, m.description)
            }
            ProjectionHolder.selectedModelIndex = selectedModelIndex
            startForegroundService(Intent(this, FloatService::class.java))
        } else {
            Log.d("AimbotInfer", "captureLauncher data is null, not starting service")
        }
    }

    companion object {
        private const val REQ_SHIZUKU = 10001
    }

    // 白色卡片风格颜色
    private val COLOR_BG = Color.parseColor("#F5F5F7")
    private val COLOR_CARD_BG = Color.WHITE
    private val COLOR_PRIMARY = Color.parseColor("#007AFF")
    private val COLOR_TEXT_DARK = Color.parseColor("#1C1C1E")
    private val COLOR_TEXT_MUTED = Color.parseColor("#8E8E93")
    private val COLOR_BORDER = Color.parseColor("#E5E5EA")
    private val COLOR_SUCCESS = Color.parseColor("#34C759")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load models from JSON
        loadModelsFromJson()

        // 根布局
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(COLOR_BG)
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }

        // 标题
        rootLayout.addView(TextView(this).apply {
            text = "Aimbot 设置"
            textSize = 24f
            setTextColor(COLOR_TEXT_DARK)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(24))
        })

        // 权限卡片
        rootLayout.addView(buildPermissionCard())
        rootLayout.addView(createSpacer(16))

        // 模型卡片
        rootLayout.addView(buildModelCard())
        rootLayout.addView(createSpacer(24))

        // 启动按钮
        val btnStart = Button(this).apply {
            text = "启动系统"
            setTextColor(Color.WHITE)
            textSize = 16f
            background = GradientDrawable().apply {
                setColor(COLOR_PRIMARY)
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            )
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    captureLauncher.launch(manager.createScreenCaptureIntent())
                } else {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivity(intent)
                    Toast.makeText(this@MainActivity, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                }
            }
        }
        rootLayout.addView(btnStart)
        rootLayout.addView(createSpacer(12))

        // ── 触摸测试按钮 ───────────────────────
        val testInjector = TouchInjector()
        var injectorReady = false

        // 主动初始化：先检查 Shizuku 状态，再初始化 TouchInjector
        fun tryInitInjector(): Boolean {
            try {
                Log.d("Shizuku", "pingBinder=${Shizuku.pingBinder()} uid=${Shizuku.getUid()} perm=${Shizuku.checkSelfPermission()}")
            } catch (e: Exception) {
                Log.e("Shizuku", "binder not ready yet: ${e.message}")
            }
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Log.d("Shizuku", "permission not granted, requesting...")
                    Shizuku.requestPermission(REQ_SHIZUKU)
                    return false
                }
                val ok = testInjector.init()
                injectorReady = ok
                Log.d("Shizuku", "TouchInjector init: $ok")
                return ok
            }
            return false
        }

        // 同时也注册 binder 到达监听，实现自动初始化
        Shizuku.addBinderReceivedListenerSticky(object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                Log.d("Shizuku", "binder received callback!")
                runOnUiThread { tryInitInjector() }
            }
        })

        rootLayout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            addView(Button(this@MainActivity).apply {
                text = "测试点击"
                textSize = 14f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FF9F0A"))
                    cornerRadius = dp(10).toFloat()
                }
                setOnClickListener {
                    if (!injectorReady && !tryInitInjector()) {
                        Toast.makeText(this@MainActivity, "Shizuku 未就绪 (ping=${Shizuku.pingBinder()})", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    val x = resources.displayMetrics.widthPixels / 2
                    val y = resources.displayMetrics.heightPixels / 2
                    testInjector.tap(x, y)
                    Toast.makeText(this@MainActivity, "点击 ($x, $y)", Toast.LENGTH_SHORT).show()
                }
            })

            addView(Button(this@MainActivity).apply {
                text = "测试滑动"
                textSize = 14f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) }
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#34C759"))
                    cornerRadius = dp(10).toFloat()
                }
                setOnClickListener {
                    if (!injectorReady && !tryInitInjector()) {
                        Toast.makeText(this@MainActivity, "Shizuku 未就绪 (ping=${Shizuku.pingBinder()})", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    val cx = resources.displayMetrics.widthPixels / 2
                    val cy = resources.displayMetrics.heightPixels / 2
                    testInjector.swipe(cx - 100, cy, cx + 100, cy, 200)
                    Toast.makeText(this@MainActivity, "滑动 ${cx-100},$cy → ${cx+100},$cy", Toast.LENGTH_SHORT).show()
                }
            })
        })

        setContentView(rootLayout)

        // 延迟加载默认模型
        android.os.Handler(mainLooper).postDelayed({
            loadDefaultModel()
        }, 500)
    }

    private fun loadModelsFromJson() {
        try {
            val jsonString = assets.open("models.json").bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)
            val modelsArray = jsonObject.getJSONArray("models")

            modelList = (0 until modelsArray.length()).map { i ->
                val model = modelsArray.getJSONObject(i)
                ModelInfo(
                    filename = model.getString("filename"),
                    displayName = model.getString("displayName"),
                    precision = model.getString("precision"),
                    inputSize = model.getInt("inputSize"),
                    outputSize = model.getInt("outputSize"),
                    description = model.getString("description")
                )
            }

            Log.d("Aimbot_AI", "Loaded ${modelList.size} models from JSON")
        } catch (e: Exception) {
            Log.e("Aimbot_AI", "Failed to load models from JSON: ${e.message}", e)
            // Fallback to empty list
            modelList = emptyList()
        }
    }

    private fun buildPermissionCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(COLOR_CARD_BG)
                cornerRadius = dp(12).toFloat()
            }
            elevation = dp(2).toFloat()

            addView(TextView(context).apply {
                text = "权限状态"
                textSize = 13f
                setTextColor(COLOR_TEXT_MUTED)
                setPadding(0, 0, 0, dp(12))
            })

            // ── 屏幕录制 ──────────────────────────
            addView(buildPermissionRow("屏幕录制", canCaptureScreen()))
            addView(divider())

            // ── 悬浮窗 ────────────────────────────
            addView(buildPermissionRow("悬浮窗", Settings.canDrawOverlays(this@MainActivity)))
            addView(divider())

            // ── Shizuku ───────────────────────────
            val shizukuOk = isShizukuGranted()
            addView(buildPermissionRow("Shizuku 提权", shizukuOk))
            if (!shizukuOk) {
                val hintTv = TextView(context).apply {
                    textSize = 11f
                    setTextColor(Color.parseColor("#FF9F0A"))
                    setPadding(dp(18), 0, 0, dp(4))
                }
                try {
                    when {
                        !Shizuku.pingBinder() -> {
                            hintTv.text = "请通过无线调试启动 Shizuku 后再试"
                            // 可选：加一个跳转按钮
                        }
                        else -> {
                            hintTv.text = "Shizuku 运行中，点击下方按钮授权"
                            addView(Button(context).apply {
                                text = "授权 Shizuku"
                                textSize = 13f
                                setTextColor(Color.WHITE)
                                setPadding(dp(12), dp(6), dp(12), dp(6))
                                layoutParams = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)
                                ).apply { setMargins(dp(18), dp(6), 0, 0) }
                                background = GradientDrawable().apply {
                                    setColor(COLOR_PRIMARY)
                                    cornerRadius = dp(8).toFloat()
                                }
                                setOnClickListener { requestShizukuPermission() }
                            })
                        }
                    }
                } catch (_: Exception) {
                    hintTv.text = "Shizuku 未安装或不可用"
                }
                addView(hintTv)
            }
        }
    }

    private fun buildPermissionRow(name: String, granted: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))

            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (granted) COLOR_SUCCESS else Color.parseColor("#FF3B30"))
                }
            })

            addView(TextView(context).apply {
                text = name
                textSize = 15f
                setTextColor(COLOR_TEXT_DARK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(TextView(context).apply {
                text = if (granted) "已授权" else "未授权"
                textSize = 13f
                setTextColor(if (granted) COLOR_SUCCESS else Color.parseColor("#FF3B30"))
            })
        }
    }

    private fun buildModelCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(COLOR_CARD_BG)
                cornerRadius = dp(12).toFloat()
            }
            elevation = dp(2).toFloat()

            addView(TextView(context).apply {
                text = "AI 模型"
                textSize = 13f
                setTextColor(COLOR_TEXT_MUTED)
                setPadding(0, 0, 0, dp(12))
            })

            addView(TextView(context).apply {
                text = "选择模型"
                textSize = 12f
                setTextColor(COLOR_TEXT_MUTED)
                setPadding(0, 0, 0, dp(6))
            })

            val spinner = Spinner(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(44)
                )
            }

            val displayNames = modelList.map { it.displayName }
            if (displayNames.isEmpty()) {
                // No models available
                addView(TextView(context).apply {
                    text = "无可用模型"
                    textSize = 14f
                    setTextColor(Color.parseColor("#FF3B30"))
                })
                return@apply
            }

            val adapter = object : ArrayAdapter<String>(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                displayNames
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent)
                    if (view is TextView) {
                        view.setPadding(dp(12), dp(8), dp(12), dp(8))
                    }
                    return view
                }

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getDropDownView(position, convertView, parent)
                    if (view is TextView) {
                        view.setPadding(dp(12), dp(10), dp(12), dp(10))
                    }
                    return view
                }
            }
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter

            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedModelIndex = position
                    val model = modelList[position]
                    loadModel(model.filename)
                    updateModelInfoCard(model)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            addView(spinner)

            addView(createSpacer(12))
            addView(divider())
            addView(createSpacer(8))

            // Model info card - starts with first model info
            if (modelList.isNotEmpty()) {
                addView(buildModelInfoCard(modelList[0]))
            }
        }
    }

    private fun buildModelInfoCard(model: ModelInfo): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0F0F5"))
                cornerRadius = dp(10).toFloat()
            }
            tag = "modelInfoCard"  // Tag to find and update later

            addView(TextView(context).apply {
                text = "模型信息"
                textSize = 12f
                setTextColor(COLOR_TEXT_MUTED)
                setPadding(0, 0, 0, dp(8))
            })

            addView(buildInfoRow("量化方式", model.precision))
            addView(createSpacer(6))
            addView(buildInfoRow("输入尺寸", "${model.inputSize}x${model.inputSize}"))
            addView(createSpacer(6))
            addView(buildInfoRow("输出数量", model.outputSize.toString()))
            addView(createSpacer(6))
            addView(buildInfoRow("描述", model.description))
        }
    }

    private fun updateModelInfoCard(model: ModelInfo) {
        // This will be called when model selection changes
        // For simplicity, we rebuild the info card when needed
    }

    private fun buildInfoRow(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(COLOR_TEXT_MUTED)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(TextView(context).apply {
                text = value
                textSize = 14f
                setTextColor(COLOR_TEXT_DARK)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        }
    }

    // ── Shizuku 权限管理 ──────────────────────

    private fun requestShizukuPermission() {
        try {
            Shizuku.requestPermission(REQ_SHIZUKU)
        } catch (e: Exception) {
            Toast.makeText(this, "Shizuku 请求失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isShizukuGranted(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    // ── Lifecycle ──────────────────────────────

    override fun onStart() {
        super.onStart()
        try {
            Shizuku.addRequestPermissionResultListener(shizukuListener)
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuListener)
        } catch (_: Exception) {}
    }

    private fun canCaptureScreen(): Boolean {
        return try {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            pm != null
        } catch (e: Exception) {
            false
        }
    }

    private fun divider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { setMargins(0, dp(4), 0, dp(4)) }
            setBackgroundColor(COLOR_BORDER)
        }
    }

    private fun createSpacer(h: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(h))
        }
    }

    private fun dp(v: Int): Int = (v * displayDensity).toInt()

    private fun loadModel(filename: String) {
        val modelFile = File(filesDir, filename)
        try {
            if (!modelFile.exists()) {
                assets.open(filename).use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            val success = JniCallBack.init(modelFile.absolutePath)
            if (success) {
                Toast.makeText(this, "模型加载成功", Toast.LENGTH_SHORT).show()
                Log.d("Aimbot_AI", "模型加载成功: $filename")
            } else {
                Toast.makeText(this, "模型加载失败", Toast.LENGTH_SHORT).show()
                Log.e("Aimbot_AI", "模型加载失败")
            }
        } catch (e: Exception) {
            Log.e("Aimbot_AI", "模型加载异常: ${e.message}", e)
        }
    }

    private fun loadDefaultModel() {
        if (modelList.isEmpty()) {
            Log.e("Aimbot_AI", "No models available")
            return
        }

        try {
            val defaultModel = modelList[0]
            val modelFile = File(filesDir, defaultModel.filename)
            if (!modelFile.exists()) {
                assets.open(defaultModel.filename).use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            JniCallBack.init(modelFile.absolutePath)
            Log.d("Aimbot_AI", "默认模型加载成功: ${defaultModel.filename}")
        } catch (e: Exception) {
            Log.e("Aimbot_AI", "默认模型加载异常: ${e.message}", e)
        }
    }
}