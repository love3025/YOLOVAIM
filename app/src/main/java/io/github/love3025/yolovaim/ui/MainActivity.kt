package io.github.love3025.yolovaim.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.widget.NestedScrollView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.card.MaterialCardView
import rikka.shizuku.Shizuku
import io.github.love3025.yolovaim.manager.ConfigManager
import io.github.love3025.yolovaim.manager.ModelRepository
import io.github.love3025.yolovaim.manager.ModelRepository.ImportedModel
import io.github.love3025.yolovaim.inference.JniCallBack
import io.github.love3025.yolovaim.model.TouchMethod
import io.github.love3025.yolovaim.util.ProjectionHolder
import io.github.love3025.yolovaim.service.FloatService
import io.github.love3025.yolovaim.R
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    enum class AimbotState { STANDBY, RUNNING, INFERENCING }

    private val stateListener: (Int, String) -> Unit = { state, modelName ->
        runOnUiThread { setAimbotState(AimbotState.entries[state], modelName) }
    }

    private fun loadStateFromPrefs() {
        // 不要在打开app时恢复状态，app打开时应该是待机中
    }

    // 模型列表直接用 ModelRepository 的条目，不再维护一份本地镜像结构
    private var modelList: List<ImportedModel> = emptyList()
    private var selectedModelIndex = 0
    private var modelInfoCardView: LinearLayout? = null
    private var modelAutoComplete: MaterialAutoCompleteTextView? = null
    private var modelSection: LinearLayout? = null
    private var aimbotState = AimbotState.STANDBY

    // 触摸方式选择
    private var selectedTouchMethod = TouchMethod.entries[ConfigManager.getConfig().touchMethodIndex.coerceIn(0, TouchMethod.entries.size - 1)]
    private var touchMethodAutoComplete: MaterialAutoCompleteTextView? = null

    private lateinit var statusText: TextView
    private lateinit var modelBadge: TextView
    private lateinit var shizukuValue: TextView
    private lateinit var overlayValue: TextView
    private lateinit var touchValue: TextView
    private lateinit var fab: ExtendedFloatingActionButton
    private var rootAvailable = false

    private var permissionDialog: androidx.appcompat.app.AlertDialog? = null
    private var permissionDialogShizukuStatus: TextView? = null
    private var permissionDialogOverlayStatus: TextView? = null
    private var permissionDialogShizukuGrant: TextView? = null
    private var permissionDialogOverlayGrant: TextView? = null

    // 彩蛋：连续点按 modelBadge
    private var badgeTapCount = 0
    private val badgeTapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val badgeTapReset = Runnable { badgeTapCount = 0 }
    private val badgeTaunts = arrayOf(
        "雑魚～", "再戳也没有用哦～", "都说了换不了的啦！",
        "你是不是傻，这又不能换", "憨憨，推理方式不能换的哦～",
        "臭杂鱼，别戳我！", "哼！（扭头）",
        "这只是一个普普通通的标签而已啦", "换不了换不了，死心吧～",
        "MUA～ 还是死心吧", "欸嘿～ 不行哦", "杂鱼杂鱼～"
    )

    private val displayDensity: Float by lazy { resources.displayMetrics.density }

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQ_SHIZUKU) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                runOnUiThread { recreate() }
            }
        }
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (data != null) {
            ProjectionHolder.resultCode = result.resultCode
            ProjectionHolder.resultData = data
            publishModelList()
            ProjectionHolder.selectedTouchMethod = selectedTouchMethod
            startForegroundService(Intent(this, FloatService::class.java))
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { ConfigManager.exportToUri(this, it) }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { ConfigManager.importFromUri(this, it) }
    }

    // 模型导入。用 OpenMultipleDocuments 是为了让 ncnn 用户能一次把
    // .param 和 .bin 一起选中——分两次选也行，同名即可配对。
    // .tflite / .param / .bin 都没有注册 MIME 类型，只能开 */* 让用户自己找。
    private val modelImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) importModels(uris)
    }

    companion object {
        private const val REQ_SHIZUKU = 10001
        const val ACTION_STATE_CHANGE = "io.github.love3025.yolovaim.STATE_CHANGE"
        const val EXTRA_STATE = "state"
        const val EXTRA_MODEL_NAME = "model_name"
    }

    // MD3 colors
    private val MD3_PRIMARY = Color.parseColor("#6750A4")
    private val MD3_ON_PRIMARY = Color.WHITE
    private val MD3_PRIMARY_CONTAINER = Color.parseColor("#EADDFF")
    private val MD3_ON_PRIMARY_CONTAINER = Color.parseColor("#21005D")
    private val MD3_SURFACE = Color.parseColor("#FFFBFE")
    private val MD3_ON_SURFACE = Color.parseColor("#1C1B1F")
    private val MD3_SURFACE_VARIANT = Color.parseColor("#E7E0EC")
    private val MD3_ON_SURFACE_VARIANT = Color.parseColor("#49454F")
    private val MD3_OUTLINE = Color.parseColor("#79747E")
    private val MD3_SURFACE_CONTAINER = Color.parseColor("#F3EDF7")
    private val MD3_START_BG = Color.parseColor("#DAE1FF")
    private val MD3_STOP_BG = Color.parseColor("#FFCDD2")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ConfigManager.init(this)
        ModelRepository.init(this)
        selectedTouchMethod = TouchMethod.entries[ConfigManager.getConfig().touchMethodIndex.coerceIn(0, TouchMethod.entries.size - 1)]
        loadModelsFromRepository()
        val cfgModelIndex = ConfigManager.getConfig().modelIndex
        if (cfgModelIndex !in 0 until modelList.size) {
            ConfigManager.updateConfig { modelIndex = 0 }
        }
        selectedModelIndex = if (cfgModelIndex in 0 until modelList.size) cfgModelIndex else 0
        ProjectionHolder.selectedModelIndex = selectedModelIndex
        setContentView(createRootLayout())
        ProjectionHolder.setStateListener(stateListener)
        ProjectionHolder.setModelIndexListener { idx ->
            runOnUiThread {
                if (idx in modelList.indices) {
                    selectedModelIndex = idx
                    val model = modelList[idx]
                    modelAutoComplete?.setText(model.displayName, false)
                    modelSection?.let { section ->
                        modelInfoCardView?.let { section.removeView(it) }
                        modelInfoCardView = buildModelInfoCard(model)
                        section.addView(modelInfoCardView)
                    }
                }
            }
        }
        ProjectionHolder.setTouchStatusListener { text ->
            runOnUiThread { if (::touchValue.isInitialized) touchValue.text = text }
        }
        loadStateFromPrefs()

        if (!isDisclaimerAccepted()) {
            showDisclaimerDialog()
        } else {
            initAfterDisclaimer()
        }
    }

    private fun initAfterDisclaimer() {
        android.os.Handler(mainLooper).postDelayed({
            loadDefaultModel()
            startPrewarmInBackground()
        }, 500)
        android.os.Handler(mainLooper).postDelayed({
            if (::statusText.isInitialized) checkPermissionsOnStart()
        }, 1500)
    }

    private fun isDisclaimerAccepted(): Boolean {
        val prefs = getSharedPreferences("disclaimer_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("accepted", false)
    }

    private fun setDisclaimerAccepted() {
        getSharedPreferences("disclaimer_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("accepted", true)
            .putLong("accepted_at", System.currentTimeMillis())
            .apply()
    }

    private fun showDisclaimerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_disclaimer, null)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("同意 (${15})", null)
            .setNegativeButton("退出") { _, _ -> finish() }
            .create()

        dialog.setOnShowListener {
            val positiveBtn = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            positiveBtn.isEnabled = false
            positiveBtn.alpha = 0.5f

            var timeReady = false
            var scrolledToBottom = false

            fun updateButtonState() {
                val canAccept = timeReady && scrolledToBottom
                positiveBtn.isEnabled = canAccept
                positiveBtn.alpha = if (canAccept) 1.0f else 0.5f
            }

            // 30 秒倒计时
            val handler = android.os.Handler(mainLooper)
            val countdownRunnable = object : Runnable {
                var remaining = 15
                override fun run() {
                    remaining--
                    if (remaining > 0) {
                        positiveBtn.text = "同意 ($remaining)"
                        handler.postDelayed(this, 1000)
                    } else {
                        positiveBtn.text = "同意"
                        timeReady = true
                        updateButtonState()
                    }
                }
            }
            handler.postDelayed(countdownRunnable, 1000)

            // 滚动监听
            val scrollView = dialogView.findViewById<android.widget.ScrollView>(R.id.disclaimerScrollView)
            scrollView?.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val child = scrollView.getChildAt(0)
                if (child != null) {
                    val diff = child.bottom - (scrollView.height + scrollY)
                    scrolledToBottom = diff <= 50
                    updateButtonState()
                }
            }

            positiveBtn.setOnClickListener {
                setDisclaimerAccepted()
                dialog.dismiss()
                initAfterDisclaimer()
            }
        }

        dialog.show()
    }

    private fun createRootLayout(): View {
        val root = CoordinatorLayout(this).apply {
            setBackgroundColor(MD3_SURFACE)
        }

        val scrollView = NestedScrollView(this)
        scrollView.layoutParams = CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(100))
        }

        // Title bar with more button at top-right
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(24), dp(16), dp(16))
        }

        titleBar.addView(TextView(this).apply {
            text = "YOLOVAIM"
            textSize = 28f
            setTextColor(MD3_ON_SURFACE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        // More button - MD3 IconButton at top-right
        titleBar.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            icon = context.getDrawable(R.drawable.ic_more_vert)
            iconSize = dp(24)
            iconTint = android.content.res.ColorStateList.valueOf(MD3_ON_SURFACE_VARIANT)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            stateListAnimator = null
            isClickable = true
            isFocusable = true
            setOnClickListener { showMainMenu(it) }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        })

        content.addView(titleBar)

        // 状态面板
        content.addView(buildStatusPanel())
        content.addView(createSpacer(16))

        // 模型卡片
        buildModelCard().also { modelSection = it; content.addView(it) }
        content.addView(createSpacer(16))

        // 触摸方式卡片
        content.addView(buildTouchMethodCard())

        scrollView.addView(content)
        root.addView(scrollView)

        // FAB 右下角
        fab = ExtendedFloatingActionButton(this).apply {
            text = "启动"
            contentDescription = "启动系统"
            setBackgroundColor(MD3_START_BG)
            setTextColor(Color.parseColor("#21005D"))
            icon = context.getDrawable(R.drawable.ic_triangle)
            elevation = 0f
            layoutParams = CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.WRAP_CONTENT,
                CoordinatorLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(dp(16), 0, dp(16), dp(16))
            }
            setOnClickListener { onFabClick() }
        }
        root.addView(fab)

        return root
    }

    private fun buildStatusPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(MD3_SURFACE_CONTAINER)
                cornerRadius = dp(12).toFloat()
            }

            val statusRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            statusText = TextView(context).apply {
                text = "待机中"
                textSize = 22f
                setTextColor(MD3_ON_SURFACE)
                typeface = Typeface.DEFAULT_BOLD
            }
            statusRow.addView(statusText)

            statusRow.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(12), 1)
            })

            modelBadge = TextView(context).apply {
                text = ProjectionHolder.currentModelName.ifEmpty { "---" }
                setTextColor(MD3_ON_PRIMARY)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setBackgroundDrawable(GradientDrawable().apply {
                    setColor(MD3_PRIMARY)
                    cornerRadius = dp(16).toFloat()
                })
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    badgeTapCount++
                    badgeTapHandler.removeCallbacks(badgeTapReset)
                    if (badgeTapCount >= 5) {
                        badgeTapCount = 0
                        Toast.makeText(context, badgeTaunts[(Math.random() * badgeTaunts.size).toInt()], Toast.LENGTH_SHORT).show()
                    } else {
                        badgeTapHandler.postDelayed(badgeTapReset, 2000)
                    }
                }
            }
            statusRow.addView(modelBadge)

            // Spacer to push help button to far right
            statusRow.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })

            // Help button - MD3 IconButton
            statusRow.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                icon = context.getDrawable(R.drawable.ic_help)
                iconSize = dp(24)
                iconTint = android.content.res.ColorStateList.valueOf(MD3_ON_SURFACE_VARIANT)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                stateListAnimator = null
                isClickable = true
                isFocusable = true
                setOnClickListener { showPermissionHelpDialog() }
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            })

            addView(statusRow)
            addView(createSpacer(4))

            // Version row
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "Version"
                    textSize = 14f
                    setTextColor(MD3_ON_SURFACE_VARIANT)
                })
                addView(TextView(context).apply {
                    text = ": "
                    textSize = 14f
                    setTextColor(MD3_ON_SURFACE_VARIANT)
                })
                addView(TextView(context).apply {
                    text = "1.2.1"
                    textSize = 14f
                    setTextColor(MD3_ON_SURFACE)
                    typeface = Typeface.DEFAULT_BOLD
                })
            })

            addView(createSpacer(4))

            val shizukuLabel = TextView(context).apply {
                text = "Privilege"
                textSize = 14f
                setTextColor(MD3_ON_SURFACE_VARIANT)
            }
            shizukuValue = TextView(context).apply {
                text = "-"
                textSize = 14f
                setTextColor(MD3_ON_SURFACE)
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(shizukuLabel)
                addView(TextView(context).apply {
                    text = ": "
                    textSize = 14f
                    setTextColor(MD3_ON_SURFACE_VARIANT)
                })
                addView(shizukuValue)
            })

            addView(createSpacer(4))

            val overlayLabel = TextView(context).apply {
                text = "Overlay"
                textSize = 14f
                setTextColor(MD3_ON_SURFACE_VARIANT)
            }
            overlayValue = TextView(context).apply {
                text = "-"
                textSize = 14f
                setTextColor(MD3_ON_SURFACE)
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(overlayLabel)
                addView(TextView(context).apply {
                    text = ": "
                    textSize = 14f
                    setTextColor(MD3_ON_SURFACE_VARIANT)
                })
                addView(overlayValue)
            })

            addView(createSpacer(4))

            val touchLabel = TextView(context).apply {
                text = "Touch Service"
                textSize = 14f
                setTextColor(MD3_ON_SURFACE_VARIANT)
            }
            touchValue = TextView(context).apply {
                text = "-"
                textSize = 14f
                setTextColor(MD3_ON_SURFACE)
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(touchLabel)
                addView(TextView(context).apply {
                    text = ": "
                    textSize = 14f
                    setTextColor(MD3_ON_SURFACE_VARIANT)
                })
                addView(touchValue)
            })
        }
    }

    private fun buildModelCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(MD3_SURFACE_CONTAINER)
                cornerRadius = dp(12).toFloat()
            }

            addView(TextView(context).apply {
                text = "检测模型"
                textSize = 16f
                setTextColor(MD3_PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(8))
            })

            // 导入按钮常驻，不管有没有模型都能再导
            addView(MaterialButton(this@MainActivity).apply {
                text = "导入模型"
                isAllCaps = false
                setOnClickListener { modelImportLauncher.launch(arrayOf("*/*")) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })

            if (modelList.isEmpty()) {
                addView(createSpacer(12))
                addView(TextView(context).apply {
                    text = "还没有导入任何模型。\n\n" +
                        "点上方按钮选择模型文件：\n" +
                        "· TFLite —— 选中 .tflite 单个文件\n" +
                        "· NCNN —— 同时选中 .param 和 .bin 两个文件\n\n" +
                        "导入后可自行填写类别名称。本应用不内置任何模型，" +
                        "检测什么完全取决于你导入的模型。"
                    textSize = 13f
                    setTextColor(MD3_ON_SURFACE_VARIANT)
                    setLineSpacing(dp(2).toFloat(), 1f)
                })
                return@apply
            }

            addView(createSpacer(12))

            // 从xml加载 MD3 外露下拉菜单
            val displayNames = modelList.map { it.displayName }
            val dropdownLayout = layoutInflater.inflate(R.layout.dropdown_layout, null) as TextInputLayout
            val autoComplete = dropdownLayout.findViewById<MaterialAutoCompleteTextView>(R.id.dropdown)
            modelAutoComplete = autoComplete

            val safeIndex = if (selectedModelIndex in displayNames.indices) selectedModelIndex else 0
            autoComplete.setText(displayNames[safeIndex], false)

            autoComplete.setAdapter(
                ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    displayNames
                )
            )

            autoComplete.setOnItemClickListener { _, _, position, _ ->
                selectedModelIndex = position
                ProjectionHolder.selectedModelIndex = position
                ConfigManager.updateConfig { modelIndex = position }
                val model = modelList[position]
                loadModel(model)
                modelInfoCardView?.let { removeView(it) }
                modelInfoCardView = buildModelInfoCard(model)
                addView(modelInfoCardView!!)
                // 通知 FloatService 同步模型
                syncModelToFloatService()
            }

            addView(dropdownLayout)
            addView(createSpacer(8))

            // 当前选中模型的编辑 / 删除
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(MaterialButton(
                    this@MainActivity, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = "编辑信息"
                    isAllCaps = false
                    setOnClickListener {
                        modelList.getOrNull(selectedModelIndex)?.let { showModelEditDialog(it) }
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = dp(8) }
                })
                addView(MaterialButton(
                    this@MainActivity, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = "删除"
                    isAllCaps = false
                    setTextColor(Color.parseColor("#B3261E"))
                    setOnClickListener {
                        modelList.getOrNull(selectedModelIndex)?.let { confirmDeleteModel(it) }
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            })

            addView(createSpacer(16))

            modelList.getOrNull(selectedModelIndex)?.let { model ->
                modelInfoCardView = buildModelInfoCard(model)
                addView(modelInfoCardView!!)
            }
        }
    }

    private fun buildTouchMethodCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(MD3_SURFACE_CONTAINER)
                cornerRadius = dp(12).toFloat()
            }

            addView(TextView(context).apply {
                text = "触摸方式"
                textSize = 16f
                setTextColor(MD3_PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(8))
            })

            // 从xml加载 MD3 外露下拉菜单
            val dropdownLayout = layoutInflater.inflate(R.layout.dropdown_layout, null) as TextInputLayout
            val autoComplete = dropdownLayout.findViewById<MaterialAutoCompleteTextView>(R.id.dropdown)
            touchMethodAutoComplete = autoComplete

            val displayNames = listOf(
                "Uinput - 适合部分设备",
                "InputManager - 适配大部分设备",
                "Stealth - 内核无痕(需 root + KPM)"
            )
            val touchMethods = TouchMethod.entries

            val safeIndex = if (selectedTouchMethod.ordinal in touchMethods.indices) selectedTouchMethod.ordinal else 0
            autoComplete.setText(displayNames[safeIndex], false)

            val adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_dropdown_item_1line,
                displayNames
            )
            autoComplete.setAdapter(adapter)

            autoComplete.setOnItemClickListener { _, _, position, _ ->
                selectedTouchMethod = touchMethods[position]
                ProjectionHolder.selectedTouchMethod = selectedTouchMethod
                ConfigManager.updateConfig { touchMethodIndex = position }
                syncTouchMethodToFloatService()
            }

            addView(dropdownLayout)
        }
    }

    private fun buildModelInfoCard(model: ImportedModel): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(MD3_SURFACE_VARIANT)
                cornerRadius = dp(12).toFloat()
            }

            addView(TextView(context).apply {
                text = "模型信息"
                textSize = 12f
                setTextColor(MD3_ON_SURFACE_VARIANT)
                setPadding(0, 0, 0, dp(8))
            })

            addView(buildInfoRow("文件", model.fileName))
            addView(createSpacer(8))
            // 这里显示的是输入张量的数据类型，也就是引擎实际按什么精度喂数据
            addView(buildInfoRow("输入类型", model.precision))
            addView(createSpacer(8))
            addView(buildInfoRow("输入尺寸", "${model.inputSize}x${model.inputSize}"))
            addView(createSpacer(8))
            addView(buildInfoRow("输出数量", model.outputSize.toString()))
            addView(createSpacer(8))
            addView(buildInfoRow("形状", model.description))
            if (model.classes.isNotEmpty()) {
                addView(createSpacer(8))
                val classStr = model.classes.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}:${it.value}" }
                addView(buildInfoRow("类别", classStr))
            }
        }
    }

    private fun buildInfoRow(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(MD3_ON_SURFACE_VARIANT)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(TextView(context).apply {
                text = value
                textSize = 14f
                setTextColor(MD3_ON_SURFACE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        }
    }

    private fun createSpacer(h: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(h))
        }
    }

    private fun dp(v: Int): Int = (v * displayDensity).toInt()

    private fun onFabClick() {
        if (aimbotState != AimbotState.STANDBY) {
            // 直接从 Activity 移除所有覆盖层视图（更可靠，不依赖 Service 生命周期）
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            ProjectionHolder.clearViews(wm)
            // 停止 Service
            stopService(Intent(this, FloatService::class.java))
            ProjectionHolder.updateState(0, ProjectionHolder.currentModelName.ifEmpty { "---" }) // STANDBY
        } else {
            // 启动
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivity(intent)
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                return
            }
            if (!isInjectorAvailable()) {
                showPermissionHelpDialog()
                return
            }
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureLauncher.launch(manager.createScreenCaptureIntent())
        }
    }

    private fun updateFabState() {
        if (!::fab.isInitialized) return
        val isRunning = aimbotState != AimbotState.STANDBY
        fab.text = if (isRunning) "停止" else "启动"
        fab.setBackgroundColor(if (isRunning) MD3_STOP_BG else MD3_START_BG)
        fab.setTextColor(if (isRunning) Color.parseColor("#B71C1C") else Color.parseColor("#21005D"))
        fab.icon = getDrawable(if (isRunning) R.drawable.ic_stop_outline else R.drawable.ic_triangle)
    }

    private fun checkPermissionsOnStart() {
        if (!Settings.canDrawOverlays(this) || !isInjectorAvailable()) {
            showPermissionHelpDialog()
        }
    }

    override fun onStart() {
        super.onStart()
        // isRootAvailable() 会 exec("su -c id") 并 waitFor()，没有超时。root
        // 管理器设为「询问」时（首次安装必然如此）su 会一直阻塞到用户点授权，
        // 放在主线程就是把 UI 冻死等人操作 —— 5 秒后系统直接 ANR，用户根本
        // 来不及点那个授权框，于是首装第一次进来什么都用不了，反复重启到某次
        // 侥幸授上为止（授权一旦记住，su 立刻返回，就「好了」）。
        // 放到后台线程等：授权框可以慢慢点，拿到结果再回主线程刷新状态。
        Thread({
            val ok = isRootAvailable()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                rootAvailable = ok
                if (!rootAvailable) {
                    try {
                        Shizuku.addRequestPermissionResultListener(shizukuListener)
                    } catch (_: Exception) {}
                    // 主动申请 Shizuku 权限
                    try {
                        if (Shizuku.pingBinder() && !isShizukuGranted()) {
                            Shizuku.requestPermission(REQ_SHIZUKU)
                        }
                    } catch (_: Exception) {}
                }
                updatePermissionStates()
            }
        }, "root-probe").start()
        // 探测没回来之前也要先把已知权限画出来，别让界面空着
        updatePermissionStates()
    }

    override fun onDestroy() {
        super.onDestroy()
        ProjectionHolder.removeStateListener()
        ProjectionHolder.removeModelIndexListener()
        ProjectionHolder.removeTouchStatusListener()
    }

    override fun onStop() {
        super.onStop()
        if (!rootAvailable) {
            try {
                Shizuku.removeRequestPermissionResultListener(shizukuListener)
            } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
        if (ProjectionHolder.needsModelReload) {
            ProjectionHolder.needsModelReload = false
            loadDefaultModel()
        }
        // 同步悬浮窗的模型选择到 MainActivity UI
        val holderIndex = ProjectionHolder.selectedModelIndex
        if (holderIndex != selectedModelIndex && holderIndex in modelList.indices) {
            selectedModelIndex = holderIndex
            val model = modelList[holderIndex]
            modelAutoComplete?.setText(model.displayName, false)
            modelSection?.let { section ->
                modelInfoCardView?.let { section.removeView(it) }
                modelInfoCardView = buildModelInfoCard(model)
                section.addView(modelInfoCardView)
            }
        }
        syncStateFromHolder()
        if (permissionDialog?.isShowing == true) {
            refreshPermissionDialog()
        }
    }

    private fun updatePermissionStates() {
        val overlay = Settings.canDrawOverlays(this)
        if (rootAvailable) {
            shizukuValue.text = "Root"
        } else {
            val shizukuPing = Shizuku.pingBinder()
            val shizukuGranted = isShizukuGranted()
            shizukuValue.text = when {
                !shizukuPing -> "Shizuku Connecting"
                shizukuGranted -> "Shizuku Ready"
                else -> "Shizuku Not Granted"
            }
        }
        overlayValue.text = if (overlay) "Granted" else "Not Granted"
        touchValue.text = ProjectionHolder.touchStatusText
    }

    private fun isShizukuGranted(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private fun isRootAvailable(): Boolean = io.github.love3025.yolovaim.injector.RootInjectorClient.isRootAvailable()

    private fun isInjectorAvailable(): Boolean {
        return rootAvailable || isShizukuGranted()
    }

    private fun showPermissionHelpDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        lateinit var shizukuStatusView: TextView
        lateinit var shizukuGrantBtn: TextView
        lateinit var overlayStatusView: TextView
        lateinit var overlayGrantBtn: TextView

        val ctx = this

        // 权限模式 row
        layout.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))

            addView(TextView(ctx).apply {
                text = "Privilege"
                textSize = 16f
                setTextColor(MD3_ON_SURFACE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            shizukuStatusView = TextView(ctx).apply {
                text = shizukuValue.text
                textSize = 14f
                setTextColor(MD3_ON_SURFACE_VARIANT)
            }
            addView(shizukuStatusView)

            shizukuGrantBtn = TextView(ctx).apply {
                text = "  授权"
                textSize = 14f
                setTextColor(MD3_PRIMARY)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { Shizuku.requestPermission(REQ_SHIZUKU) }
            }
            addView(shizukuGrantBtn)
        })

        // Overlay row
        layout.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))

            addView(TextView(ctx).apply {
                text = "Overlay"
                textSize = 16f
                setTextColor(MD3_ON_SURFACE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            overlayStatusView = TextView(ctx).apply {
                text = overlayValue.text
                textSize = 14f
                setTextColor(MD3_ON_SURFACE_VARIANT)
            }
            addView(overlayStatusView)

            overlayGrantBtn = TextView(ctx).apply {
                text = "  授权"
                textSize = 14f
                setTextColor(MD3_PRIMARY)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivity(intent)
                }
            }
            addView(overlayGrantBtn)
        })

        permissionDialogShizukuStatus = shizukuStatusView
        permissionDialogOverlayStatus = overlayStatusView
        permissionDialogShizukuGrant = shizukuGrantBtn
        permissionDialogOverlayGrant = overlayGrantBtn

        permissionDialog = MaterialAlertDialogBuilder(this)
            .setTitle("权限说明")
            .setView(layout)
            .setPositiveButton("关闭", null)
            .create()

        permissionDialog!!.setOnShowListener {
            refreshPermissionDialog()
        }

        permissionDialog!!.show()
    }

    private fun refreshPermissionDialog() {
        permissionDialogShizukuStatus?.text = shizukuValue.text
        permissionDialogOverlayStatus?.text = overlayValue.text

        val shizukuStatus = shizukuValue.text.toString()
        permissionDialogShizukuGrant?.visibility = if (shizukuStatus == "Shizuku Ready" || shizukuStatus == "Shizuku Connecting" || shizukuStatus == "Root") View.GONE else View.VISIBLE
        permissionDialogOverlayGrant?.visibility = if (overlayValue.text == "Granted") View.GONE else View.VISIBLE
    }

    private fun showMainMenu(anchor: View) {
        val popupView = layoutInflater.inflate(R.layout.popup_menu, null)

        val popup = PopupWindow(popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true)
        popup.elevation = 8f

        popupView.findViewById<LinearLayout>(R.id.menuExport).setOnClickListener {
            popup.dismiss()
            exportLauncher.launch("aimbot_config.json")
        }
        popupView.findViewById<LinearLayout>(R.id.menuImport).setOnClickListener {
            popup.dismiss()
            importLauncher.launch(arrayOf("application/json"))
        }
        popupView.findViewById<LinearLayout>(R.id.menuChangelog).setOnClickListener {
            popup.dismiss()
            showChangelogDialog()
        }
        popupView.findViewById<LinearLayout>(R.id.menuGithub).setOnClickListener {
            popup.dismiss()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xiangsu1145/Auto-aim_android-yolo/")))
        }
        popupView.findViewById<LinearLayout>(R.id.menuSettings).setOnClickListener {
            popup.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        popupView.findViewById<LinearLayout>(R.id.menuGithub).setOnClickListener {
            popup.dismiss()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xiangsu1145/Auto-aim_android-yolo/")))
        }
        popupView.findViewById<LinearLayout>(R.id.menuSettings).setOnClickListener {
            popup.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = popupView.measuredWidth
        popup.showAsDropDown(anchor, -popupWidth + anchor.width, 0)
    }

    private fun showChangelogDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_changelog, null)

        MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setPositiveButton("关闭", null)
            .show()
    }

    fun setAimbotState(state: AimbotState, modelName: String = ProjectionHolder.currentModelName.ifEmpty { "---" }) {
        aimbotState = state
        if (!::statusText.isInitialized) return
        runOnUiThread {
            statusText.text = when (state) {
                AimbotState.STANDBY -> "待机中"
                AimbotState.RUNNING -> "运行中"
                AimbotState.INFERENCING -> "推理中"
            }
            modelBadge.text = modelName
            updateFabState()
        }
    }

    private fun syncStateFromHolder() {
        val stateOrdinal = ProjectionHolder.currentState
        if (::statusText.isInitialized) {
            val state = AimbotState.entries[stateOrdinal]
            val modelName = ProjectionHolder.currentModelName
            aimbotState = state
            statusText.text = when (state) {
                AimbotState.STANDBY -> "待机中"
                AimbotState.RUNNING -> "运行中"
                AimbotState.INFERENCING -> "推理中"
            }
            modelBadge.text = modelName
        }
        updateFabState()
    }

    // ==================== 模型：加载 / 导入 / 预热 ====================

    /** 从 ModelRepository 读一遍用户已导入的模型，并把快照推给 FloatService。 */
    private fun loadModelsFromRepository() {
        modelList = ModelRepository.list()
        if (selectedModelIndex !in modelList.indices) selectedModelIndex = 0
        publishModelList()
        Log.d("YOLOVAIM", "已导入模型 ${modelList.size} 个")
    }

    /**
     * 把当前模型列表快照写进 ProjectionHolder，供 FloatService 读取。
     * 列表变化（导入 / 删除 / 改元数据）之后都要调一次，否则悬浮窗那边还是旧的。
     */
    private fun publishModelList() {
        ProjectionHolder.modelList = modelList.map { m ->
            ProjectionHolder.ModelEntry(
                filename = m.fileName,
                path = ModelRepository.absolutePathOf(m),
                displayName = m.displayName,
                precision = m.precision,
                inputSize = m.inputSize,
                outputSize = m.outputSize,
                description = m.description,
                classes = m.classes
            )
        }
        ProjectionHolder.selectedModelIndex = selectedModelIndex
    }

    /**
     * 导入用户选中的模型文件。
     *
     * 探测要开 TFLite Interpreter 读张量形状，大模型可能几百毫秒，所以整个过程
     * 放后台线程，主线程只负责进度框和结果提示。
     */
    private fun importModels(uris: List<Uri>) {
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle("正在导入")
            .setMessage("正在复制并解析模型…")
            .setCancelable(false)
            .create()
        progress.show()

        Thread {
            val added = mutableListOf<ImportedModel>()
            val failed = mutableListOf<String>()
            for (uri in uris) {
                ModelRepository.importFrom(uri)
                    .onSuccess { m -> if (m != null) added.add(m) }
                    .onFailure { e -> failed.add(e.message ?: "未知错误") }
            }
            runOnUiThread {
                progress.dismiss()
                loadModelsFromRepository()
                rebuildModelCard()

                if (added.isEmpty() && failed.isEmpty()) {
                    Toast.makeText(this, "已复制权重文件", Toast.LENGTH_SHORT).show()
                } else if (failed.isNotEmpty()) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("部分文件导入失败")
                        .setMessage(failed.joinToString("\n"))
                        .setPositiveButton("知道了", null)
                        .show()
                }
                // 刚导入的模型先让用户确认一下元数据，尤其是类别名
                added.firstOrNull()?.let { showModelEditDialog(it) }
            }
        }.start()
    }

    /**
     * 元数据编辑框。探测出来的只是默认值——类别名尤其需要用户自己填，
     * 因为类别语义完全取决于用户训练时用的数据集，本应用不做任何假设。
     */
    private fun showModelEditDialog(model: ImportedModel) {
        val pad = dp(20)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }

        fun field(label: String, value: String, numeric: Boolean = false): EditText {
            container.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(MD3_ON_SURFACE_VARIANT)
                setPadding(0, dp(8), 0, 0)
            })
            val et = EditText(this).apply {
                setText(value)
                textSize = 14f
                if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            container.addView(et)
            return et
        }

        val nameEt = field("显示名称", model.displayName)
        val inputEt = field("输入尺寸（正方形边长，像素）", model.inputSize.toString(), numeric = true)
        val outputEt = field("输出数量（anchor 数，0 = 由引擎自行读取）", model.outputSize.toString(), numeric = true)
        val classesEt = field(
            "类别名（按 id 顺序，逗号分隔）",
            model.classes.entries.sortedBy { it.key }.joinToString(", ") { it.value }
        )

        container.addView(TextView(this).apply {
            text = "文件：${model.fileName}\n${model.description}"
            textSize = 11f
            setTextColor(MD3_ON_SURFACE_VARIANT)
            setPadding(0, dp(12), 0, 0)
        })

        MaterialAlertDialogBuilder(this)
            .setTitle("模型信息")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("保存") { _, _ ->
                model.displayName = nameEt.text.toString().trim().ifEmpty { model.fileName }
                model.inputSize = inputEt.text.toString().trim().toIntOrNull() ?: model.inputSize
                model.outputSize = outputEt.text.toString().trim().toIntOrNull() ?: model.outputSize
                val names = classesEt.text.toString().split(',')
                    .map { it.trim() }.filter { it.isNotEmpty() }
                model.classes = if (names.isEmpty()) {
                    mutableMapOf(0 to "class_0")
                } else {
                    names.mapIndexed { i, n -> i to n }.toMap().toMutableMap()
                }
                ModelRepository.update(model)
                loadModelsFromRepository()
                rebuildModelCard()
                // 保存的就是当前选中的模型 → 立即(重)载引擎。两个作用:
                // 1. inputSize 可能被手改过,ncnn 需要新值;
                // 2. 首次安装导入的模型靠这里第一次真正加载——导入流程本身
                //    不初始化引擎,下拉框 setText(...,false) 预填文本也不触发
                //    选择监听,不加载的话点启动就是"运行中 none"。
                if (modelList.getOrNull(selectedModelIndex)?.id == model.id) {
                    loadDefaultModel()
                }
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteModel(model: ImportedModel) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除模型")
            .setMessage("确定删除「${model.displayName}」？模型文件会从应用内部存储中移除，此操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                ModelRepository.remove(model)
                if (selectedModelIndex >= ModelRepository.list().size) selectedModelIndex = 0
                ConfigManager.updateConfig { modelIndex = selectedModelIndex }
                loadModelsFromRepository()
                rebuildModelCard()
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 模型卡片是代码构建的，列表变化后整块重建最省事。 */
    private fun rebuildModelCard() {
        val section = modelSection ?: return
        val parent = section.parent as? ViewGroup ?: return
        val idx = parent.indexOfChild(section)
        if (idx < 0) return
        parent.removeViewAt(idx)
        modelInfoCardView = null
        val fresh = buildModelCard()
        modelSection = fresh
        parent.addView(fresh, idx)
    }

    // ========== QNN HTP 预热 ==========
    // 原先挂在 LoginActivity 上（跟着授权界面那几秒顺带做掉），授权界面移除后
    // 搬到这里。作用是让 QNN 提前把每个 tflite 的 HTP 图编译好写进 cache/qnn/，
    // 否则用户第一次选中某个模型时要现场编译，首次推理会明显卡一下。
    private var prewarmExecutor: ExecutorService? = null

    private fun startPrewarmInBackground() {
        val tflite = modelList.filter { it.fileName.endsWith(".tflite", ignoreCase = true) }
        if (tflite.isEmpty()) return

        // QNN HTP 是 per-device 互斥的：worker 持锁编译时，主线程的 JniCallBack.init()
        // 必须等它放锁。所以把当前选中的模型提到队首先编，其余的随后补，
        // 避免当前模型排在后面时首次加载要等前面全部编完。
        val current = modelList.getOrNull(selectedModelIndex)
        val ordered = if (current != null && current in tflite) {
            listOf(current) + tflite.filter { it.id != current.id }
        } else tflite

        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "QnnPrewarm").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
        prewarmExecutor = executor
        executor.execute {
            for (m in ordered) {
                val f = ModelRepository.fileOf(m)
                if (!f.exists()) continue
                val ok = JniCallBack.prewarmQnn(f.absolutePath)
                Log.i("QnnPrewarm", "prewarm ${m.fileName} -> $ok")
            }
            executor.shutdown()
            prewarmExecutor = null
        }
    }

    private fun loadModel(model: ImportedModel) {
        val modelFile = ModelRepository.fileOf(model)
        if (!modelFile.exists()) {
            Toast.makeText(this, "模型文件已丢失，请重新导入", Toast.LENGTH_LONG).show()
            return
        }
        if (ModelRepository.isMissingWeights(model)) {
            Toast.makeText(this, "缺少同名 .bin 权重文件，请一并导入", Toast.LENGTH_LONG).show()
            return
        }
        try {
            // 不要清 QNN 缓存：预热写进去的编译产物按 <token>_<fingerprint>.bin
            // 分文件存放，加载第二个模型只是新增（或命中）一个文件而已。
            // 清掉的话每次切模型都要重新编译 HTP 图。
            File(cacheDir, "qnn").mkdirs()
            val cfg = ConfigManager.getConfig()
            JniCallBack.setForceCpu(cfg.useCpuInference)
            JniCallBack.setCpuThreads(cfg.cpuThreadCount)
            // ncnn 需要显式告知输入尺寸；tflite 由引擎自己从模型里读
            if (model.inputSize > 0) JniCallBack.setInputSize(model.inputSize, model.inputSize)
            val success = JniCallBack.init(modelFile.absolutePath)
            if (success) {
                ProjectionHolder.currentModelName = JniCallBack.getBackend()
                statusText.text = when (aimbotState) {
                    AimbotState.STANDBY -> "待机中"
                    AimbotState.RUNNING -> "运行中"
                    AimbotState.INFERENCING -> "推理中"
                }
                modelBadge.text = ProjectionHolder.currentModelName
                Toast.makeText(this, "模型加载成功", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "模型加载失败，检查格式是否为 YOLO 导出的 tflite/ncnn", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("YOLOVAIM", "模型加载异常: ${e.message}", e)
            Toast.makeText(this, "模型加载异常：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun syncModelToFloatService() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val running = am.getRunningServices(100).any {
            it.service.className == FloatService::class.java.name
        }
        if (running) {
            startForegroundService(Intent(this, FloatService::class.java).apply {
                action = "SYNC_MODEL"
            })
        }
    }

    private fun syncTouchMethodToFloatService() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val running = am.getRunningServices(100).any {
            it.service.className == FloatService::class.java.name
        }
        if (running) {
            startForegroundService(Intent(this, FloatService::class.java).apply {
                action = "RECONNECT_TOUCH"
            })
        }
    }

    /** 启动时按上次选中的下标加载模型。一个模型都没导入时什么都不做。 */
    private fun loadDefaultModel() {
        if (modelList.isEmpty()) return
        try {
            File(cacheDir, "qnn").mkdirs()
            val idx = selectedModelIndex.coerceIn(0, modelList.size - 1)
            val defaultModel = modelList[idx]
            val modelFile = ModelRepository.fileOf(defaultModel)
            if (!modelFile.exists() || ModelRepository.isMissingWeights(defaultModel)) {
                Log.w("YOLOVAIM", "默认模型不可用: ${defaultModel.fileName}")
                return
            }
            val cfg2 = ConfigManager.getConfig()
            JniCallBack.setForceCpu(cfg2.useCpuInference)
            JniCallBack.setCpuThreads(cfg2.cpuThreadCount)
            if (defaultModel.inputSize > 0) {
                JniCallBack.setInputSize(defaultModel.inputSize, defaultModel.inputSize)
            }
            val ok = JniCallBack.init(modelFile.absolutePath)
            if (ok) {
                ProjectionHolder.currentModelName = JniCallBack.getBackend()
                if (::statusText.isInitialized) {
                    modelBadge.text = ProjectionHolder.currentModelName
                }
            }
        } catch (e: Exception) {
            Log.e("YOLOVAIM", "默认模型加载异常: ${e.message}", e)
        }
    }
}