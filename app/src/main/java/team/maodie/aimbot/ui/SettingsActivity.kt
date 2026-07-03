package team.maodie.aimbot.ui

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import team.maodie.aimbot.manager.ConfigManager
import team.maodie.aimbot.inference.JniCallBack
import team.maodie.aimbot.svc.AimSvcHolder
import team.maodie.aimbot.svc.PairingForegroundService
import team.maodie.aimbot.util.ProjectionHolder
import team.maodie.aimbot.service.FloatService

class SettingsActivity : AppCompatActivity() {

    private val MD3_SURFACE = Color.parseColor("#FFFBFE")
    private val MD3_ON_SURFACE = Color.parseColor("#1C1B1F")
    private val MD3_ON_SURFACE_VARIANT = Color.parseColor("#49454F")
    private val MD3_PRIMARY = Color.parseColor("#6750A4")

    private val THREAD_VALUES = intArrayOf(1, 2, 4, 6, 8)

    private val displayDensity: Float by lazy { resources.displayMetrics.density }
    private fun dp(value: Int): Int = (value * displayDensity + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigManager.init(this)
        setContentView(createLayout())
    }

    private fun createLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MD3_SURFACE)
        }

        val toolbar = MaterialToolbar(this).apply {
            title = "设置"
            setTitleTextColor(MD3_ON_SURFACE)
            navigationIcon = getDrawable(androidx.appcompat.R.drawable.abc_ic_ab_back_material)?.apply {
                setTint(MD3_ON_SURFACE)
            }
            setNavigationOnClickListener { finish() }
            setBackgroundColor(MD3_SURFACE)
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(toolbar)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }

        // === 推理设置 section ===
        content.addView(createSectionHeader("推理设置"))

        // CPU 推理开关
        val cpuRow = createSwitchRow(
            title = "使用 CPU 推理",
            subtitle = "强制使用 CPU 进行模型推理，不使用 NPU 加速"
        )
        val cpuSwitch = cpuRow.tag as MaterialSwitch
        cpuSwitch.isChecked = ConfigManager.getConfig().useCpuInference
        content.addView(cpuRow)

        // CPU 线程数设置（仅 CPU 推理时显示）
        val threadRow = createThreadSliderRow()
        threadRow.visibility = if (cpuSwitch.isChecked) View.VISIBLE else View.GONE
        content.addView(threadRow)

        cpuSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                showCpuWarningDialog(cpuSwitch, threadRow)
            } else {
                ConfigManager.updateConfig { useCpuInference = false }
                JniCallBack.setForceCpu(false)
                ProjectionHolder.needsModelReload = true
                threadRow.visibility = View.GONE
                reloadModelIfServiceRunning()
            }
        }

        // === 无线调试 section ===
        content.addView(createSectionHeader("无线调试"))
        val svcStatusRow = createServiceStatusRow()
        content.addView(svcStatusRow.row)
        val startStopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val pairBtn = createActionButton("开始配对")
        val startBtn = createActionButton("启动内嵌服务")
        val stopBtn = createActionButton("停止内嵌服务")
        val clearBtn = createActionButton("清空配对")
        startStopRow.addView(pairBtn)
        startStopRow.addView(startBtn)
        startStopRow.addView(stopBtn)
        startStopRow.addView(clearBtn)
        content.addView(startStopRow)

        AimSvcHolder.bootstrap(this)
        renderSvcButtons(AimSvcHolder.state, pairBtn, startBtn, stopBtn, clearBtn)
        AimSvcHolder.setListener { state -> runOnUiThread {
            svcStatusRow.value.text = formatState(state)
            renderSvcButtons(state, pairBtn, startBtn, stopBtn, clearBtn)
        }}

        pairBtn.setOnClickListener { showPairDialog() }
        startBtn.setOnClickListener { AimSvcHolder.startAsync(this) }
        stopBtn.setOnClickListener { AimSvcHolder.stopAsync(this) }
        clearBtn.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("清空配对信息？")
                .setMessage("清空后下次使用需要重新配对。")
                .setPositiveButton("清空") { _, _ -> AimSvcHolder.clearPairing(this) }
                .setNegativeButton("取消", null)
                .show()
        }

        registerReceiver(svcBroadcastReceiver, IntentFilter().apply {
            addAction(PairingForegroundService.BROADCAST_SUCCESS)
            addAction(PairingForegroundService.BROADCAST_FAILURE)
        }, Context.RECEIVER_NOT_EXPORTED)

        scrollView.addView(content)
        root.addView(scrollView)
        return root
    }

    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(MD3_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(0), dp(16), dp(0), dp(8))
        }
    }

    private fun createSwitchRow(title: String, subtitle: String): LinearLayout {
        val switch = MaterialSwitch(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(MD3_ON_SURFACE)
        }

        val subtitleView = TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(MD3_ON_SURFACE_VARIANT)
            setPadding(0, dp(2), 0, 0)
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
        }
        textCol.addView(titleView)
        textCol.addView(subtitleView)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(MD3_SURFACE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(1), 0, 0) }
        }
        row.addView(textCol)
        row.addView(switch)
        row.tag = switch
        row.setOnClickListener { switch.toggle() }
        return row
    }

    private fun createThreadSliderRow(): LinearLayout {
        val cfg = ConfigManager.getConfig()
        val currentIndex = THREAD_VALUES.indexOf(cfg.cpuThreadCount).let { if (it < 0) 2 else it } // default 4

        val titleView = TextView(this).apply {
            text = "CPU 线程数"
            textSize = 16f
            setTextColor(MD3_ON_SURFACE)
        }

        val valueLabel = TextView(this).apply {
            text = "${THREAD_VALUES[currentIndex]} 线程"
            textSize = 14f
            setTextColor(MD3_ON_SURFACE_VARIANT)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(0))
            addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueLabel)
        }

        val slider = Slider(this).apply {
            valueFrom = 0f
            valueTo = (THREAD_VALUES.size - 1).toFloat()
            stepSize = 1f
            value = currentIndex.toFloat()
            setPadding(dp(12), dp(4), dp(12), dp(12))
            setLabelFormatter { value -> "${THREAD_VALUES[value.toInt()]}" }
            addOnChangeListener { _, value, _ ->
                val threads = THREAD_VALUES[value.toInt()]
                valueLabel.text = "$threads 线程"
                ConfigManager.updateConfig { cpuThreadCount = threads }
                JniCallBack.setCpuThreads(threads)
                reloadModelIfServiceRunning()
            }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MD3_SURFACE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(1), 0, 0) }
        }
        col.addView(headerRow)
        col.addView(slider)
        return col
    }

    private fun reloadModelIfServiceRunning() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = am.getRunningServices(100).any {
            it.service.className == FloatService::class.java.name
        }
        if (running) {
            startForegroundService(Intent(this, FloatService::class.java).apply {
                action = "RELOAD_MODEL"
            })
        }
    }

    private fun showCpuWarningDialog(cpuSwitch: MaterialSwitch, threadRow: LinearLayout) {
        MaterialAlertDialogBuilder(this)
            .setTitle("使用 CPU 推理？")
            .setMessage(
                "切换为 CPU 推理后，可能出现以下问题：\n\n" +
                "• 推理速度显著下降，帧率降低\n" +
                "• 设备发烫严重，影响游戏体验\n" +
                "• CPU 占用过高，可能导致游戏卡顿\n" +
                "• 耗电量大幅增加\n\n" +
                "仅建议在 NPU 加速不可用时使用。\n" +
                "确定切换为 CPU 推理吗？"
            )
            .setPositiveButton("确定") { _, _ ->
                ConfigManager.updateConfig { useCpuInference = true }
                JniCallBack.setForceCpu(true)
                ProjectionHolder.needsModelReload = true
                threadRow.visibility = View.VISIBLE
                reloadModelIfServiceRunning()
            }
            .setNegativeButton("取消") { _, _ ->
                cpuSwitch.isChecked = false
            }
            .setOnCancelListener {
                cpuSwitch.isChecked = false
            }
            .show()
    }

    private data class SvcStatusRow(val row: LinearLayout, val label: TextView, val value: TextView)

    private fun createServiceStatusRow(): SvcStatusRow {
        val label = TextView(this).apply {
            text = "内嵌服务状态"
            textSize = 16f
            setTextColor(MD3_ON_SURFACE)
        }
        val value = TextView(this).apply {
            text = formatState(AimSvcHolder.state)
            textSize = 14f
            setTextColor(MD3_ON_SURFACE_VARIANT)
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
        }
        textCol.addView(label)
        textCol.addView(value)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(textCol)
        return SvcStatusRow(row, label, value)
    }

    private fun createActionButton(text: String): Button = Button(this).apply {
        this.text = text
        textSize = 13f
        setPadding(dp(8), dp(6), dp(8), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(4), 0, dp(4), 0) }
    }

    private fun renderSvcButtons(
        state: AimSvcHolder.State,
        pairBtn: Button,
        startBtn: Button,
        stopBtn: Button,
        clearBtn: Button
    ) {
        when (state) {
            is AimSvcHolder.State.NotPaired -> {
                pairBtn.visibility = View.VISIBLE
                startBtn.visibility = View.GONE
                stopBtn.visibility = View.GONE
                clearBtn.visibility = View.GONE
            }
            is AimSvcHolder.State.Paired -> {
                pairBtn.visibility = View.GONE
                startBtn.visibility = View.VISIBLE
                stopBtn.visibility = View.GONE
                clearBtn.visibility = View.VISIBLE
            }
            is AimSvcHolder.State.Running -> {
                pairBtn.visibility = View.GONE
                startBtn.visibility = View.GONE
                stopBtn.visibility = View.VISIBLE
                clearBtn.visibility = View.VISIBLE
            }
            is AimSvcHolder.State.Error -> {
                pairBtn.visibility = View.VISIBLE
                startBtn.visibility = View.VISIBLE
                stopBtn.visibility = View.VISIBLE
                clearBtn.visibility = View.VISIBLE
            }
        }
    }

    private fun formatState(state: AimSvcHolder.State): String = when (state) {
        is AimSvcHolder.State.NotPaired -> "未配对"
        is AimSvcHolder.State.Paired -> "已配对 (${state.host}:${state.port})"
        is AimSvcHolder.State.Running -> "运行中${state.pid?.let { " (pid=$it)" } ?: ""}"
        is AimSvcHolder.State.Error -> "错误：${state.message}"
    }

    private fun showPairDialog() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            MaterialAlertDialogBuilder(this)
                .setTitle("不支持")
                .setMessage("无线调试需要 Android 11+")
                .setPositiveButton("好", null)
                .show()
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "6 位配对码"
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("无线调试配对")
            .setMessage("手机开\"开发者选项 → 无线调试\"，输入界面上的 6 位配对码。")
            .setView(input)
            .setPositiveButton("开始配对") { _, _ ->
                val code = input.text.toString().trim()
                if (code.length == 6) {
                    val intent = Intent(this, PairingForegroundService::class.java).apply {
                        action = PairingForegroundService.ACTION_START
                        putExtra(PairingForegroundService.EXTRA_PAIR_CODE, code)
                    }
                    startForegroundService(intent)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private val svcBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PairingForegroundService.BROADCAST_SUCCESS -> {
                    val host = intent.getStringExtra(PairingForegroundService.EXTRA_HOST) ?: "127.0.0.1"
                    val port = intent.getIntExtra(PairingForegroundService.EXTRA_PORT, 0)
                    MaterialAlertDialogBuilder(this@SettingsActivity)
                        .setTitle("配对成功")
                        .setMessage("已配对 $host:$port，可点击\"启动内嵌服务\"")
                        .setPositiveButton("好", null)
                        .show()
                }
                PairingForegroundService.BROADCAST_FAILURE -> {
                    val msg = intent.getStringExtra(PairingForegroundService.EXTRA_ERROR) ?: "未知错误"
                    MaterialAlertDialogBuilder(this@SettingsActivity)
                        .setTitle("配对失败")
                        .setMessage(msg)
                        .setPositiveButton("好", null)
                        .show()
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(svcBroadcastReceiver) }
        AimSvcHolder.setListener(null)
        super.onDestroy()
    }
}
