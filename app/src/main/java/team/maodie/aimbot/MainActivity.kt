package team.maodie.aimbot

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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    enum class AimbotState { STANDBY, RUNNING, INFERENCING }

    private val stateListener: (Int, String) -> Unit = { state, modelName ->
        runOnUiThread { setAimbotState(AimbotState.entries[state], modelName) }
    }

    private fun loadStateFromPrefs() {
        // 不要在打开app时恢复状态，app打开时应该是待机中
    }

    data class ModelInfo(
        val filename: String,
        val displayName: String,
        val precision: String,
        val inputSize: Int,
        val outputSize: Int,
        val description: String,
        val classes: Map<Int, String> = emptyMap()
    )

    private var modelList: List<ModelInfo> = emptyList()
    private var selectedModelIndex = 0
    private var modelInfoCardView: LinearLayout? = null
    private var modelAutoComplete: MaterialAutoCompleteTextView? = null
    private var modelSection: LinearLayout? = null
    private var aimbotState = AimbotState.STANDBY

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
            ProjectionHolder.modelList = modelList.map { m ->
                ProjectionHolder.ModelEntry(m.filename, m.displayName, m.precision, m.inputSize, m.outputSize, m.description, m.classes)
            }
            ProjectionHolder.selectedModelIndex = selectedModelIndex
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

    companion object {
        private const val REQ_SHIZUKU = 10001
        const val ACTION_STATE_CHANGE = "team.maodie.aimbot.STATE_CHANGE"
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
        loadModelsFromJson()
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
        loadStateFromPrefs()

        if (!isDisclaimerAccepted()) {
            showDisclaimerDialog()
        } else {
            initAfterDisclaimer()
        }
    }

    private fun initAfterDisclaimer() {
        android.os.Handler(mainLooper).postDelayed({ loadDefaultModel() }, 500)
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
            .setPositiveButton("同意 (${30})", null)
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
                var remaining = 30
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
            text = "Aimbot"
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
                text = "QNN HTP"
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
                    text = "1.0.8"
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
                text = "选择模型"
                textSize = 16f
                setTextColor(MD3_PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(8))
            })

            val displayNames = modelList.map { it.displayName }
            if (displayNames.isEmpty()) {
                addView(TextView(context).apply {
                    text = "无可用模型"
                    textSize = 14f
                    setTextColor(Color.parseColor("#B3261E"))
                })
                return@apply
            }

            // 从xml加载 MD3 外露下拉菜单
            val dropdownLayout = layoutInflater.inflate(R.layout.dropdown_layout, null) as TextInputLayout
            val autoComplete = dropdownLayout.findViewById<MaterialAutoCompleteTextView>(R.id.dropdown)
            modelAutoComplete = autoComplete

            val safeIndex = if (selectedModelIndex in displayNames.indices) selectedModelIndex else 0
            autoComplete.setText(displayNames[safeIndex], false)

            val adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_dropdown_item_1line,
                displayNames
            )
            autoComplete.setAdapter(adapter)

            autoComplete.setOnItemClickListener { _, _, position, _ ->
                selectedModelIndex = position
                ProjectionHolder.selectedModelIndex = position
                ConfigManager.updateConfig { modelIndex = position }
                val model = modelList[position]
                loadModel(model.filename)
                modelInfoCardView?.let { removeView(it) }
                modelInfoCardView = buildModelInfoCard(model)
                addView(modelInfoCardView!!)
                // 通知 FloatService 同步模型
                syncModelToFloatService()
            }

            addView(dropdownLayout)
            addView(createSpacer(16))

            if (modelList.isNotEmpty()) {
                val model = modelList[selectedModelIndex]
                modelInfoCardView = buildModelInfoCard(model)
                addView(modelInfoCardView!!)
            }
        }
    }

    private fun buildModelInfoCard(model: ModelInfo): LinearLayout {
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

            addView(buildInfoRow("量化方式", model.precision))
            addView(createSpacer(8))
            addView(buildInfoRow("输入尺寸", "${model.inputSize}x${model.inputSize}"))
            addView(createSpacer(8))
            addView(buildInfoRow("输出数量", model.outputSize.toString()))
            addView(createSpacer(8))
            addView(buildInfoRow("描述", model.description))
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
            ProjectionHolder.updateState(0, "QNN HTP") // STANDBY
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
        rootAvailable = isRootAvailable()
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

    override fun onDestroy() {
        super.onDestroy()
        ProjectionHolder.removeStateListener()
        ProjectionHolder.removeModelIndexListener()
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
        touchValue.text = "Running"
    }

    private fun isShizukuGranted(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val output = reader.readLine() ?: ""
            process.waitFor()
            output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

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

    fun setAimbotState(state: AimbotState, modelName: String = "QNN HTP") {
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

    private fun loadModelsFromJson() {
        try {
            val jsonString = assets.open("models.json").bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)
            val modelsArray = jsonObject.getJSONArray("models")

            modelList = (0 until modelsArray.length()).map { i ->
                val model = modelsArray.getJSONObject(i)
                val classesMap = mutableMapOf<Int, String>()
                if (model.has("classes")) {
                    val classesObj = model.getJSONObject("classes")
                    classesObj.keys().forEach { key ->
                        classesMap[key.toInt()] = classesObj.getString(key)
                    }
                }
                ModelInfo(
                    filename = model.getString("filename"),
                    displayName = model.getString("displayName"),
                    precision = model.getString("precision"),
                    inputSize = model.getInt("inputSize"),
                    outputSize = model.getInt("outputSize"),
                    description = model.getString("description"),
                    classes = classesMap
                )
            }

            Log.d("Aimbot_AI", "Loaded ${modelList.size} models from JSON")
        } catch (e: Exception) {
            Log.e("Aimbot_AI", "Failed to load models from JSON: ${e.message}", e)
            modelList = emptyList()
        }
    }

    private fun loadModel(filename: String) {
        val modelFile = File(filesDir, filename)
        try {
            val qnnCache = File(cacheDir, "qnn")
            if (qnnCache.exists()) {
                qnnCache.deleteRecursively()
            }
            qnnCache.mkdirs()
            if (!modelFile.exists()) {
                assets.open(filename).use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            val cfg = ConfigManager.getConfig()
            JniCallBack.setForceCpu(cfg.useCpuInference)
            JniCallBack.setCpuThreads(cfg.cpuThreadCount)
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
            }
        } catch (e: Exception) {
            Log.e("Aimbot_AI", "模型加载异常: ${e.message}", e)
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

    private fun loadDefaultModel() {
        if (modelList.isEmpty()) return

        try {
            val qnnCache = File(cacheDir, "qnn")
            if (qnnCache.exists()) {
                qnnCache.deleteRecursively()
            }
            qnnCache.mkdirs()
            val idx = selectedModelIndex.coerceIn(0, modelList.size - 1)
            val defaultModel = modelList[idx]
            val modelFile = File(filesDir, defaultModel.filename)
            if (!modelFile.exists()) {
                assets.open(defaultModel.filename).use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            val cfg2 = ConfigManager.getConfig()
            JniCallBack.setForceCpu(cfg2.useCpuInference)
            JniCallBack.setCpuThreads(cfg2.cpuThreadCount)
            val ok = JniCallBack.init(modelFile.absolutePath)
            if (ok) {
                ProjectionHolder.currentModelName = JniCallBack.getBackend()
                if (::statusText.isInitialized) {
                    modelBadge.text = ProjectionHolder.currentModelName
                }
            }
        } catch (e: Exception) {
            Log.e("Aimbot_AI", "默认模型加载异常: ${e.message}", e)
        }
    }
}