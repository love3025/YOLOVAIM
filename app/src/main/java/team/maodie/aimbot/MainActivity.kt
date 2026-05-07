package team.maodie.aimbot

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import com.topjohnwu.superuser.Shell
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

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("AimbotInfer", "resultCode=${result.resultCode}, data=${result.data}")
        val data = result.data
        if (data != null) {
            ProjectionHolder.resultCode = result.resultCode
            ProjectionHolder.resultData = data
            startForegroundService(Intent(this, FloatService::class.java))
        }
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
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )

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

            addView(buildPermissionRow("屏幕录制", canCaptureScreen()))
            addView(divider())
            addView(buildPermissionRow("悬浮窗", Settings.canDrawOverlays(this@MainActivity)))
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