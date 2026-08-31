package io.github.love3025.yolovaim.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import io.github.love3025.yolovaim.manager.ConfigManager
import io.github.love3025.yolovaim.inference.JniCallBack
import io.github.love3025.yolovaim.util.ProjectionHolder
import io.github.love3025.yolovaim.service.FloatService

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
}
