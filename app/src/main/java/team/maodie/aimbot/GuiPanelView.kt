package team.maodie.aimbot

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textview.MaterialTextView

class GuiPanelView(context: Context) : MaterialCardView(ContextThemeWrapper(context, R.style.Theme_Aimbot)) {

    var onEnabledChanged: ((Boolean) -> Unit)? = null
    var onSpeedChanged: ((Float) -> Unit)? = null
    var onRangeChanged: ((Int) -> Unit)? = null
    var onConfidenceChanged: ((Float) -> Unit)? = null
    var onModelSelected: ((Int) -> Unit)? = null
    var onTriggerEnabled: ((Boolean) -> Unit)? = null
    var onTriggerReactionSpeed: ((Int) -> Unit)? = null
    var onTriggerCooldown: ((Int) -> Unit)? = null
    var onTriggerUpFluctuation: ((Int) -> Unit)? = null
    var onTriggerDownFluctuation: ((Int) -> Unit)? = null
    var onTriggerTouchDuration: ((Int) -> Unit)? = null
    var onTriggerTouchRange: ((Int) -> Unit)? = null
    var onTriggerShowArea: ((Boolean) -> Unit)? = null
    var onTestCircle: (() -> Unit)? = null
    var onToggleModel: ((Boolean) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onAimOffsetYRatioChanged: ((Float) -> Unit)? = null
    var onAimSwayAmplitudeChanged: ((Int) -> Unit)? = null
    var onAimPredictionChanged: ((Int) -> Unit)? = null
    var onTriggerOffsetYRatioChanged: ((Float) -> Unit)? = null
    var onKiChanged: ((Float) -> Unit)? = null
    var onKdChanged: ((Float) -> Unit)? = null
    var onAimTouchDisplay: ((Boolean) -> Unit)? = null
    var onAimTouchSize: ((Int) -> Unit)? = null
    var onAimHoldEnabled: ((Boolean) -> Unit)? = null
    var onCaptureRangeEnabled: ((Boolean) -> Unit)? = null
    var onShowCaptureRangeChanged: ((Boolean) -> Unit)? = null
    var onShowDetectionBoxChanged: ((Boolean) -> Unit)? = null
    var onShowCenterDotChanged: ((Boolean) -> Unit)? = null
    var onAreaSettingsToggle: (() -> Unit)? = null

    var aimbotEnabled = false; var speed = 0.3f; var range = 300
    var confidence = 0.50f; var modelIndex = 0; var modelNames: List<String> = emptyList()
    var aimOffsetYRatio = 0f; var aimSwayAmplitude = 0; var aimPrediction = 0
    var ki = 0.02f; var kd = 0.08f
    var aimTouchDisplay = false; var aimTouchSize = 20
    var aimHoldEnabled = false
    var showCaptureRange = false; var showDetectionBox = false; var showCenterDot = false
    var triggerEnabled = false; var triggerReactionSpeed = 100; var triggerCooldown = 200
    var triggerUpFluctuation = 3; var triggerDownFluctuation = 3
    var triggerTouchDuration = 10; var triggerTouchRange = 100; var triggerShowArea = false; var triggerOffsetYRatio = 0f
    var modelRunning = false
    private var navScrollView: ScrollView? = null
    private var savedNavScrollY = 0

    private val clPrimary = Color.parseColor("#1976D2")
    private val clOnPrimary = Color.WHITE; private val clSurface = Color.WHITE
    private val clOnSurface = Color.parseColor("#1C1B1F")
    private val clOnSurfaceVariant = Color.parseColor("#9CA3AF")
    private val clOutline = Color.parseColor("#E5E7EB")
    private val clSurfaceVariant = Color.parseColor("#F3F4F6")
    private val clPrimaryLight = Color.argb(24, Color.red(clPrimary), Color.green(clPrimary), Color.blue(clPrimary))

    private data class TabDef(val label: String)
    private val tabs = listOf(TabDef("自瞄"), TabDef("扳机"), TabDef("防闪"), TabDef("模型"), TabDef("系统"))
    var activeTab = 0
    private lateinit var contentContainer: LinearLayout
    private var switching = false

    init {
        radius = dp(16).toFloat(); setCardBackgroundColor(clSurface); cardElevation = dp(12).toFloat()
        buildUI()
    }

    fun buildUI() {
        removeAllViews()
        val root = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT) }
        addView(root)
        root.addView(buildNavRail())
        root.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(1), MATCH_PARENT); setBackgroundColor(clOutline) })
        val scroll = ScrollView(context).apply { layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f); overScrollMode = View.OVER_SCROLL_NEVER }
        contentContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT) }
        scroll.addView(contentContainer); root.addView(scroll)
        buildContent()
        contentContainer.alpha = 0f; contentContainer.animate().alpha(1f).setDuration(180).start()
    }

    private fun buildNavRail(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(12), dp(6), dp(12)); setBackgroundColor(clSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(dp(60), MATCH_PARENT)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(32))
                setOnClickListener { onClose?.invoke() }
                addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(20), dp(3)); setBackgroundColor(clOnSurfaceVariant) })
            })
            addView(spacer(dp(6)))
            addView(ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    tabs.forEachIndexed { idx, tab ->
                        addView(navItem(tab.label, idx))
                        if (idx < tabs.size - 1) addView(spacer(dp(4)))
                    }
                })
            }.also { navScrollView = it })
            addView(MaterialTextView(context).apply {
                text = if (modelRunning) "■" else "▶"; textSize = 18f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(36))
                setTextColor(clOnSurfaceVariant)
                setOnClickListener {
                    modelRunning = !modelRunning; text = if (modelRunning) "■" else "▶"
                    setTextColor(if (modelRunning) clPrimary else clOnSurfaceVariant)
                    onToggleModel?.invoke(modelRunning)
                }
            })
        }
    }

    private fun navItem(label: String, idx: Int): View {
        val active = idx == activeTab
        return MaterialTextView(context).apply {
            text = label; textSize = 12f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { minimumHeight = dp(56) }; setPadding(0, dp(6), 0, dp(6))
            val r = dp(8).toFloat()
            if (active) { setTextColor(clPrimary); typeface = Typeface.DEFAULT_BOLD; background = android.graphics.drawable.GradientDrawable().apply { setColor(clPrimaryLight); cornerRadius = r } }
            else { setTextColor(clOnSurfaceVariant); typeface = Typeface.DEFAULT; background = null }
            setOnClickListener { switchTab(idx) }
        }
    }

    private fun switchTab(target: Int) {
        if (target == activeTab || switching) return; switching = true; activeTab = target
        savedNavScrollY = navScrollView?.scrollY ?: 0
        buildUI()
        navScrollView?.post { navScrollView?.scrollTo(0, savedNavScrollY) }
        switching = false
    }

    private fun buildContent() {
        contentContainer.removeAllViews()
        when (activeTab) { 0 -> buildAimbot(); 1 -> buildTriggerbot(); 3 -> buildModelTab(); 4 -> buildSystem(); else -> buildEmpty("防闪") }
    }

    private fun buildAimbot() {
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(MaterialTextView(context).apply { text = "自瞄"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(MaterialSwitch(context).apply { isChecked = aimbotEnabled; setOnCheckedChangeListener { _, c -> aimbotEnabled = c; onEnabledChanged?.invoke(c) } })
        })
        contentContainer.addView(spacer(dp(2))); contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4))
            addView(MaterialTextView(context).apply { text = "按住激发"; textSize = 12f; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(MaterialSwitch(context).apply { isChecked = aimHoldEnabled; setOnCheckedChangeListener { _, c -> aimHoldEnabled = c; onAimHoldEnabled?.invoke(c) } })
        })
        contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(MaterialTextView(context).apply { text = "PID参数"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(clOnSurface) })
        contentContainer.addView(spacer(dp(4)))
        contentContainer.addView(buildSlider("Kp", speed, 0.01f, 0.2f, "%.2f") { speed = it; onSpeedChanged?.invoke(it) })
        contentContainer.addView(spacer(dp(2)))
        contentContainer.addView(buildSlider("Ki", ki, 0.00f, 0.20f, "%.2f") { ki = it; onKiChanged?.invoke(it) })
        contentContainer.addView(spacer(dp(2)))
        contentContainer.addView(buildSlider("Kd", kd, 0.00f, 0.30f, "%.2f") { kd = it; onKdChanged?.invoke(it) })
        contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(buildSlider("Y偏移", aimOffsetYRatio, -1.5f, 0f, "%.0f%%") { aimOffsetYRatio = it; onAimOffsetYRatioChanged?.invoke(it) })
        contentContainer.addView(spacer(dp(2)))
        contentContainer.addView(buildSliderInt("摆动幅度", aimSwayAmplitude, 0, 2, "px") { aimSwayAmplitude = it; onAimSwayAmplitudeChanged?.invoke(it) })
        contentContainer.addView(spacer(dp(2)))
        contentContainer.addView(buildSliderInt("预测强度", aimPrediction, 0, 10, "") { aimPrediction = it; onAimPredictionChanged?.invoke(it) })
    }

    private fun buildTriggerbot() {
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(MaterialTextView(context).apply { text = "扳机"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(MaterialSwitch(context).apply { isChecked = triggerEnabled; setOnCheckedChangeListener { _, c -> triggerEnabled = c; onTriggerEnabled?.invoke(c) } })
        })
        contentContainer.addView(spacer(dp(2))); contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(buildSliderInt("反应速度", triggerReactionSpeed, 10, 500, "ms") { triggerReactionSpeed = it; onTriggerReactionSpeed?.invoke(it) })
        contentContainer.addView(spacer(dp(2)))
        contentContainer.addView(buildSliderInt("冷却时间", triggerCooldown, 10, 1000, "ms") { triggerCooldown = it; onTriggerCooldown?.invoke(it) })
        contentContainer.addView(spacer(dp(2)))
        contentContainer.addView(buildSliderInt("向上波动", triggerUpFluctuation, 0, 15, "ms") { triggerUpFluctuation = it; onTriggerUpFluctuation?.invoke(it) })
        contentContainer.addView(spacer(dp(2)))
        contentContainer.addView(buildSliderInt("向下波动", triggerDownFluctuation, 0, 15, "ms") { triggerDownFluctuation = it; onTriggerDownFluctuation?.invoke(it) })
        contentContainer.addView(spacer(dp(2)))
        contentContainer.addView(buildSliderInt("触摸时间", triggerTouchDuration, 1, 50, "ms") { triggerTouchDuration = it; onTriggerTouchDuration?.invoke(it) })
        contentContainer.addView(spacer(dp(2)))
        contentContainer.addView(buildSlider("Y偏移", triggerOffsetYRatio, -2f, 0f, "%.0f%%") { triggerOffsetYRatio = it; onTriggerOffsetYRatioChanged?.invoke(it) })
    }

    private fun buildModelTab() {
        contentContainer.addView(MaterialTextView(context).apply { text = "模型选择"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(clOnSurface) })
        contentContainer.addView(spacer(dp(2))); contentContainer.addView(divider()); contentContainer.addView(spacer(dp(8)))
        if (modelNames.isEmpty()) {
            contentContainer.addView(MaterialTextView(context).apply { text = "无可用模型"; textSize = 13f; setTextColor(clOnSurfaceVariant); setPadding(0, dp(16), 0, dp(16)) })
        } else {
            modelNames.forEachIndexed { idx, name ->
                val selected = idx == modelIndex
                contentContainer.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = if (selected) android.graphics.drawable.GradientDrawable().apply { setColor(clPrimaryLight); cornerRadius = dp(8).toFloat() } else null
                    setOnClickListener { if (modelIndex != idx) { modelIndex = idx; onModelSelected?.invoke(idx); buildContent() } }
                    addView(MaterialTextView(context).apply { text = if (selected) "●" else "○"; textSize = 14f; setTextColor(if (selected) clPrimary else clOnSurfaceVariant); setPadding(0, 0, dp(8), 0) })
                    addView(MaterialTextView(context).apply { text = name; textSize = 13f; setTextColor(if (selected) clPrimary else clOnSurface); typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
                })
            }
        }
        contentContainer.addView(spacer(dp(12)))
        contentContainer.addView(MaterialTextView(context).apply { text = "检测置信度"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(clOnSurface) })
        contentContainer.addView(spacer(dp(2))); contentContainer.addView(divider()); contentContainer.addView(spacer(dp(8)))
        contentContainer.addView(buildSlider("阈值", confidence, 0.10f, 0.90f, "%.2f") { confidence = it; onConfidenceChanged?.invoke(it) })
        contentContainer.addView(MaterialTextView(context).apply { text = "低于此值的检测结果将被过滤"; textSize = 9f; setTextColor(clOnSurfaceVariant); setPadding(0, dp(2), 0, 0) })
    }

    private fun buildSystem() {
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(MaterialTextView(context).apply { text = "显示截取范围"; textSize = 11f; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(MaterialSwitch(context).apply { isChecked = showCaptureRange; setOnCheckedChangeListener { _, c -> showCaptureRange = c; onShowCaptureRangeChanged?.invoke(c) } })
        })
        contentContainer.addView(buildSliderInt("截取范围", range, 50, 800, "px") { range = it; onRangeChanged?.invoke(it) })
        contentContainer.addView(spacer(dp(2)))
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(MaterialTextView(context).apply { text = "显示检测框"; textSize = 11f; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(MaterialSwitch(context).apply { isChecked = showDetectionBox; setOnCheckedChangeListener { _, c -> showDetectionBox = c; onShowDetectionBoxChanged?.invoke(c) } })
        })
        contentContainer.addView(spacer(dp(2)))
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(MaterialTextView(context).apply { text = "显示中心点"; textSize = 11f; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(MaterialSwitch(context).apply { isChecked = showCenterDot; setOnCheckedChangeListener { _, c -> showCenterDot = c; onShowCenterDotChanged?.invoke(c) } })
        })
        contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(MaterialTextView(context).apply { text = "输入测试"; textSize = 11f; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(MaterialSwitch(context).apply { isChecked = false; setOnCheckedChangeListener { _, c -> if (c) { onTestCircle?.invoke(); isChecked = false } } })
        })
        contentContainer.addView(MaterialTextView(context).apply { text = "在屏幕中心画一个圆"; textSize = 10f; setTextColor(clOnSurfaceVariant) })
        contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(MaterialTextView(context).apply { text = "区域设置"; textSize = 11f; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(MaterialSwitch(context).apply { isChecked = false; setOnCheckedChangeListener { _, c -> if (c) { onAreaSettingsToggle?.invoke(); isChecked = false } } })
        })
        contentContainer.addView(MaterialTextView(context).apply { text = "配置开火/触发/瞄准区域"; textSize = 10f; setTextColor(clOnSurfaceVariant) })
    }

    private fun buildSlider(label: String, value: Float, min: Float, max: Float, fmt: String, onChange: (Float) -> Unit): LinearLayout {
        fun display(v: Float) = when (fmt) {
            "0px" -> "${v.toInt()}px"
            "%.0f%%" -> "%.0f%%".format(v * 100)
            else -> fmt.format(v)
        }
        val valueTv = MaterialTextView(context).apply { text = display(value); textSize = 12f; setTextColor(clPrimary); typeface = Typeface.DEFAULT_BOLD }
        val slider = Slider(context).apply { valueFrom = min; valueTo = max; this.value = value; trackHeight = dp(4); layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            addOnChangeListener { _, v, fu -> if (fu) { onChange(v); valueTv.text = display(v) } } }
        return LinearLayout(context).apply { orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(MaterialTextView(context).apply { text = label; textSize = 11f; setTextColor(clOnSurfaceVariant); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
                addView(valueTv) })
            addView(slider) }
    }

    private fun buildSliderInt(label: String, value: Int, min: Int, max: Int, suffix: String, onChange: (Int) -> Unit): LinearLayout {
        val valueTv = MaterialTextView(context).apply { text = "$value$suffix"; textSize = 12f; setTextColor(clPrimary); typeface = Typeface.DEFAULT_BOLD }
        val slider = Slider(context).apply { valueFrom = min.toFloat(); valueTo = max.toFloat(); if (max - min < 10) stepSize = 1f; this.value = value.toFloat(); trackHeight = dp(4); layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            addOnChangeListener { _, v, fu -> if (fu) { val iv = v.toInt(); onChange(iv); valueTv.text = "$iv$suffix" } } }
        return LinearLayout(context).apply { orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(MaterialTextView(context).apply { text = label; textSize = 11f; setTextColor(clOnSurfaceVariant); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
                addView(valueTv) })
            addView(slider) }
    }

    private fun buildEmpty(name: String) {
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumHeight = dp(140)
            addView(MaterialTextView(context).apply { text = name; textSize = 14f; setTextColor(clOnSurfaceVariant); typeface = Typeface.DEFAULT_BOLD })
            addView(spacer(dp(4)))
            addView(MaterialTextView(context).apply { text = "开发中"; textSize = 10f; setTextColor(clOnSurfaceVariant) })
        })
    }

    private fun divider() = View(context).apply { layoutParams = LayoutParams(MATCH_PARENT, dp(1)); setBackgroundColor(clOutline) }
    private fun spacer(h: Int) = View(context).apply { layoutParams = LayoutParams(1, dp(h)) }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
