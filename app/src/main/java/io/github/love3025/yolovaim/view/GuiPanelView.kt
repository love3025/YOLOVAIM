package io.github.love3025.yolovaim.view

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import kotlin.math.roundToInt
import android.widget.ScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textview.MaterialTextView
import io.github.love3025.yolovaim.R

class GuiPanelView(context: Context) : MaterialCardView(ContextThemeWrapper(context, R.style.Theme_YOLOVAIM)) {

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
    var onKfChanged: ((Float) -> Unit)? = null
    var onAimTouchDisplay: ((Boolean) -> Unit)? = null
    var onAimTouchSize: ((Int) -> Unit)? = null
    var onAimHoldEnabled: ((Boolean) -> Unit)? = null
    var onAimModeChanged: ((Int) -> Unit)? = null
    var onBezierDurationChanged: ((Int) -> Unit)? = null
    var onBezierControlOffsetChanged: ((Float) -> Unit)? = null
    var onBezierRandomSpreadChanged: ((Float) -> Unit)? = null
    var onCaptureRangeEnabled: ((Boolean) -> Unit)? = null
    var onShowCaptureRangeChanged: ((Boolean) -> Unit)? = null
    var onShowDetectionBoxChanged: ((Boolean) -> Unit)? = null
    var onShowCenterDotChanged: ((Boolean) -> Unit)? = null
    var onAreaSettingsToggle: (() -> Unit)? = null
    var onRecordEnabledChanged: ((Boolean) -> Unit)? = null
    var onAutoSaveDatasetChanged: ((Boolean) -> Unit)? = null
    var onAimClassesChanged: ((Set<Int>) -> Unit)? = null
    var onPriorityClassChanged: ((Int) -> Unit)? = null
    var onClassAimOffsetChanged: ((Int, Float) -> Unit)? = null
    var onBoxAimRatioChanged: ((Float) -> Unit)? = null
    var onClassBoxAimRatioChanged: ((Int, Float) -> Unit)? = null
    var onClassTriggerOffsetChanged: ((Int, Float) -> Unit)? = null
    var onTriggerClassesChanged: ((Set<Int>) -> Unit)? = null
    var onRecoilEnabledChanged: ((Boolean) -> Unit)? = null
    var onRecoilStrengthChanged: ((Float) -> Unit)? = null
    var onRecoilMaxOffsetChanged: ((Float) -> Unit)? = null
    var onRecoilResetIntervalChanged: ((Int) -> Unit)? = null
    var onConvergeThreshChanged: ((Int) -> Unit)? = null
    var onAutoStopEnabledChanged: ((Boolean) -> Unit)? = null
    var onAimbotFovChanged: ((Int) -> Unit)? = null
    var onShowFovChanged: ((Boolean) -> Unit)? = null
    var onDynamicFovChanged: ((Boolean) -> Unit)? = null
    var onFovZoomDelayChanged: ((Int) -> Unit)? = null
    var onShowInferInfoChanged: ((Boolean) -> Unit)? = null

    var aimbotEnabled = false; var speed = 0.07f; var range = 300
    var confidence = 0.50f; var modelIndex = 0; var modelNames: List<String> = emptyList()
    var aimOffsetYRatio = 0f; var aimSwayAmplitude = 0; var aimPrediction = 0
    var ki = 0.001f; var kd = 0.05f; var kf = 0.05f
    var aimTouchDisplay = false; var aimTouchSize = 20
    var aimMode = 0; var bezierDuration = 30; var bezierControlOffset = 0.3f; var bezierRandomSpread = 0.1f
    var aimHoldEnabled = false
    var convergeThresh = 10
    var aimFov = 50
    var maxFov = 1000
    var showFov = false
    var dynamicFov = false
    var fovZoomDelay = 0
    var showInferInfo = false
    var showCaptureRange = false; var showDetectionBox = false; var showCenterDot = false
    var triggerEnabled = false; var triggerReactionSpeed = 100; var triggerCooldown = 200
    var triggerUpFluctuation = 3; var triggerDownFluctuation = 3
    var triggerTouchDuration = 10; var triggerTouchRange = 100; var triggerShowArea = false; var triggerOffsetYRatio = 0f
    var modelRunning = false
    var recordEnabled = false
    var classMap: Map<Int, String> = emptyMap()
    var aimClasses: MutableSet<Int> = mutableSetOf()  // empty = all
    var priorityClass: Int = -1
    var classAimOffsets: MutableMap<Int, Float> = mutableMapOf()
    var boxAimRatio = 0.5f
    var classBoxAimRatios: MutableMap<Int, Float> = mutableMapOf()
    var classTriggerOffsets: MutableMap<Int, Float> = mutableMapOf()
    var triggerClasses: MutableSet<Int> = mutableSetOf()  // empty = all
    var autoSaveDataset = false
    var recoilEnabled = false
    var recoilStrength = 0.5f
    var recoilMaxOffset = 200f
    var recoilResetIntervalMs = 300
    var autoStopEnabled = false
    private var navScrollView: ScrollView? = null
    private var savedNavScrollY = 0
    // Container wrapping the dynamic-FOV slider + description; visibility is
    // bound to the `dynamicFov` toggle so the controls disappear when disabled.
    private var dynamicFovControls: LinearLayout? = null

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
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4))
            addView(MaterialTextView(context).apply { text = "压枪"; textSize = 12f; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(MaterialSwitch(context).apply { isChecked = recoilEnabled; setOnCheckedChangeListener { _, c -> recoilEnabled = c; onRecoilEnabledChanged?.invoke(c) } })
        })
        contentContainer.addView(buildStepperSlider("压枪强度", recoilStrength, 0.05f, 1.0f, "%.0f%%") { recoilStrength = it; onRecoilStrengthChanged?.invoke(it) })
        contentContainer.addView(MaterialTextView(context).apply { text = "按住开火键时持续下压"; textSize = 9f; setTextColor(clOnSurfaceVariant); setPadding(0, dp(2), 0, 0) })
        contentContainer.addView(buildStepperSlider("压枪上限", recoilMaxOffset, 50f, 600f, "0px", 10f) { recoilMaxOffset = it; onRecoilMaxOffsetChanged?.invoke(it) })
        contentContainer.addView(MaterialTextView(context).apply { text = "偏移量累积到此值后不再增加"; textSize = 9f; setTextColor(clOnSurfaceVariant); setPadding(0, dp(2), 0, 0) })
        contentContainer.addView(buildStepperSlider("开火重置间隔", recoilResetIntervalMs.toFloat(), 50f, 1000f, "0ms", 50f) { v -> val iv = v.toInt(); recoilResetIntervalMs = iv; onRecoilResetIntervalChanged?.invoke(iv) })
        contentContainer.addView(MaterialTextView(context).apply { text = "松开开火键超过此时长才开始回落，连点时枪与枪之间不清零"; textSize = 9f; setTextColor(clOnSurfaceVariant); setPadding(0, dp(2), 0, 0) })
        contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(buildStepperSlider("收敛阈值", convergeThresh.toFloat(), 0f, 100f, "0px") { v -> val iv = v.toInt(); convergeThresh = iv; onConvergeThreshChanged?.invoke(iv) })
        contentContainer.addView(MaterialTextView(context).apply { text = "误差小于此值时抬手"; textSize = 9f; setTextColor(clOnSurfaceVariant); setPadding(0, dp(2), 0, 0) })
        contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
        // FOV (Field of View) circle — only targets inside this radius are aimable
        contentContainer.addView(buildStepperSlider("FOV 半径", aimFov.coerceIn(20, maxFov).toFloat(), 20f, maxFov.toFloat(), "0px") { v -> val iv = v.toInt(); aimFov = iv; onAimbotFovChanged?.invoke(iv) })
        contentContainer.addView(MaterialTextView(context).apply { text = "只瞄准屏幕中心 ${aimFov}px 范围内的目标"; textSize = 9f; setTextColor(clOnSurfaceVariant); setPadding(0, dp(2), 0, 0) })
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4))
            addView(MaterialTextView(context).apply { text = "显示 FOV 圆"; textSize = 12f; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(MaterialSwitch(context).apply {
                isChecked = showFov
                setOnCheckedChangeListener { _, c -> showFov = c; onShowFovChanged?.invoke(c) }
            })
        })
        // Dynamic FOV: shrink FOV onto the locked target so another detection
        // can't take over the aim; expand back to aimFov after target is lost.
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4))
            addView(MaterialTextView(context).apply { text = "动态 FOV"; textSize = 12f; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(MaterialSwitch(context).apply {
                isChecked = dynamicFov
                setOnCheckedChangeListener { _, c ->
                    dynamicFov = c
                    dynamicFovControls?.visibility = if (c) View.VISIBLE else View.GONE
                    onDynamicFovChanged?.invoke(c)
                }
            })
        })
        dynamicFovControls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (dynamicFov) View.VISIBLE else View.GONE
            addView(buildStepperSlider("缩放延迟", fovZoomDelay.toFloat(), 0f, 100f, "0ms") { v -> val iv = v.toInt(); fovZoomDelay = iv; onFovZoomDelayChanged?.invoke(iv) })
            addView(MaterialTextView(context).apply { text = "目标丢失后保持当前 FOV 的时长"; textSize = 9f; setTextColor(clOnSurfaceVariant); setPadding(0, dp(2), 0, 0) })
        }
        contentContainer.addView(dynamicFovControls)
        contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
        // Mode toggle
        contentContainer.addView(MaterialTextView(context).apply { text = "算法模式"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(clOnSurface) })
        contentContainer.addView(spacer(dp(4)))
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            val pidBtn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "PID"; textSize = 11f; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                if (aimMode == 0) { setBackgroundColor(clPrimary); setTextColor(clOnPrimary) } else { setTextColor(clOnSurface) }
                setOnClickListener { aimMode = 0; onAimModeChanged?.invoke(0); buildContent() }
            }
            val bezBtn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "贝塞尔"; textSize = 11f; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
                if (aimMode == 1) { setBackgroundColor(clPrimary); setTextColor(clOnPrimary) } else { setTextColor(clOnSurface) }
                setOnClickListener { aimMode = 1; onAimModeChanged?.invoke(1); buildContent() }
            }
            addView(pidBtn); addView(bezBtn)
        })
        contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
        if (aimMode == 0) {
            // PID controls
            contentContainer.addView(MaterialTextView(context).apply { text = "PID参数"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(clOnSurface) })
            contentContainer.addView(spacer(dp(4)))
            contentContainer.addView(buildStepperSlider("Kp", speed, 0.01f, 0.2f, "%.2f") { speed = it; onSpeedChanged?.invoke(it) })
            contentContainer.addView(spacer(dp(2)))
            contentContainer.addView(buildStepperSlider("Ki", ki, 0.00f, 0.1f, "%.3f") { ki = it; onKiChanged?.invoke(it) })
            contentContainer.addView(spacer(dp(2)))
            contentContainer.addView(buildStepperSlider("Kd", kd, 0.00f, 0.2f, "%.2f") { kd = it; onKdChanged?.invoke(it) })
            contentContainer.addView(spacer(dp(2)))
            contentContainer.addView(buildStepperSlider("Kf", kf, 0.0f, 0.2f, "%.2f") { kf = it; onKfChanged?.invoke(it) })
        } else {
            // Bezier controls
            contentContainer.addView(MaterialTextView(context).apply { text = "贝塞尔参数"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(clOnSurface) })
            contentContainer.addView(spacer(dp(4)))
            val curveView = BezierCurveView(context).apply {
                controlOffset = bezierControlOffset
                randomSpread = bezierRandomSpread
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(80))
            }
            contentContainer.addView(curveView)
            contentContainer.addView(spacer(dp(4)))
            contentContainer.addView(buildStepperSlider("持续时间", bezierDuration.toFloat(), 5f, 100f, "0ms") { v -> val iv = v.toInt(); bezierDuration = iv; onBezierDurationChanged?.invoke(iv) })
            contentContainer.addView(spacer(dp(2)))
            contentContainer.addView(buildStepperSlider("曲线弯曲", bezierControlOffset, 0.05f, 0.50f, "%.2f") { bezierControlOffset = it; curveView.controlOffset = it; onBezierControlOffsetChanged?.invoke(it) })
            contentContainer.addView(spacer(dp(2)))
            contentContainer.addView(buildStepperSlider("随机幅度", bezierRandomSpread, 0f, 0.50f, "%.2f") { bezierRandomSpread = it; curveView.randomSpread = it; onBezierRandomSpreadChanged?.invoke(it) })
        }
        contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
        if (classMap.size > 1) {
            // Per-class Y offset sliders
            contentContainer.addView(MaterialTextView(context).apply { text = "Y偏移(各类别)"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(clOnSurface) })
            contentContainer.addView(spacer(dp(4)))
            for ((id, name) in classMap.entries.sortedBy { it.key }) {
                val offset = classAimOffsets[id] ?: 0f
                contentContainer.addView(buildStepperSlider(name, offset, -1.5f, 1.5f, "%.0f%%") { v -> classAimOffsets[id] = v; onClassAimOffsetChanged?.invoke(id, v) })
            }
        } else {
            contentContainer.addView(buildStepperSlider("Y偏移", aimOffsetYRatio, -1.5f, 1.5f, "%.0f%%") { aimOffsetYRatio = it; onAimOffsetYRatioChanged?.invoke(it) })
        }
        contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
        if (classMap.size > 1) {
            // Per-class box aim ratio sliders
            contentContainer.addView(MaterialTextView(context).apply { text = "框内偏移(各类别) 1=最上 0=最下"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(clOnSurface) })
            contentContainer.addView(spacer(dp(4)))
            for ((id, name) in classMap.entries.sortedBy { it.key }) {
                val ratio = classBoxAimRatios[id] ?: 0.5f
                contentContainer.addView(buildStepperSlider(name, ratio, 0f, 1f, "%.0f%%") { v -> classBoxAimRatios[id] = v; onClassBoxAimRatioChanged?.invoke(id, v) })
            }
        } else {
            contentContainer.addView(buildStepperSlider("框内偏移 (1=最上 0=最下)", boxAimRatio, 0f, 1f, "%.0f%%") { boxAimRatio = it; onBoxAimRatioChanged?.invoke(it) })
        }
        contentContainer.addView(spacer(dp(2)))
        contentContainer.addView(buildStepperSlider("摆动幅度", aimSwayAmplitude.toFloat(), 0f, 2f, "0px") { v -> val iv = v.toInt(); aimSwayAmplitude = iv; onAimSwayAmplitudeChanged?.invoke(iv) })

        // Class selection
        if (classMap.isNotEmpty()) {
            contentContainer.addView(spacer(dp(6)))
            contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
            contentContainer.addView(MaterialTextView(context).apply { text = "目标类别"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(clOnSurface) })
            contentContainer.addView(spacer(dp(4)))
            val allIds = classMap.keys.sorted()
            val effectiveAim = if (aimClasses.isEmpty()) allIds.toMutableSet() else aimClasses
            for ((id, name) in classMap.entries.sortedBy { it.key }) {
                val checked = id in effectiveAim
                contentContainer.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(2))
                    addView(MaterialTextView(context).apply { text = name; textSize = 12f; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
                    addView(MaterialSwitch(context).apply {
                        isChecked = checked
                        if (allIds.size <= 1) { isEnabled = false; alpha = 0.5f }
                        setOnCheckedChangeListener { _, c ->
                            if (aimClasses.isEmpty()) aimClasses = allIds.toMutableSet()
                            if (!c && aimClasses.size <= 1 && id in aimClasses) { isChecked = true; return@setOnCheckedChangeListener }
                            if (c) aimClasses.add(id) else aimClasses.remove(id)
                            onAimClassesChanged?.invoke(aimClasses.toMutableSet())
                        }
                    })
                })
            }
            contentContainer.addView(spacer(dp(6)))
            contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
            contentContainer.addView(MaterialTextView(context).apply { text = "优先瞄准"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(clOnSurface) })
            contentContainer.addView(spacer(dp(4)))
            // "无" option
            contentContainer.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(2))
                addView(MaterialTextView(context).apply { text = "无"; textSize = 12f; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
                addView(MaterialTextView(context).apply { text = if (priorityClass < 0) "●" else "○"; textSize = 16f; setTextColor(if (priorityClass < 0) clPrimary else clOnSurfaceVariant) })
                setOnClickListener { priorityClass = -1; onPriorityClassChanged?.invoke(-1); buildContent() }
            })
            for ((id, name) in classMap.entries.sortedBy { it.key }) {
                val selected = priorityClass == id
                contentContainer.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(2))
                    addView(MaterialTextView(context).apply { text = name; textSize = 12f; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
                    addView(MaterialTextView(context).apply { text = if (selected) "●" else "○"; textSize = 16f; setTextColor(if (selected) clPrimary else clOnSurfaceVariant) })
                    setOnClickListener { priorityClass = id; onPriorityClassChanged?.invoke(id); buildContent() }
                })
            }
        }
    }

    private fun buildTriggerbot() {
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(MaterialTextView(context).apply { text = "扳机"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(MaterialSwitch(context).apply { isChecked = triggerEnabled; setOnCheckedChangeListener { _, c -> triggerEnabled = c; onTriggerEnabled?.invoke(c) } })
        })
        contentContainer.addView(spacer(dp(2))); contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(buildStepperSlider("反应速度", triggerReactionSpeed.toFloat(), 10f, 500f, "0ms") { v -> val iv = v.toInt(); triggerReactionSpeed = iv; onTriggerReactionSpeed?.invoke(iv) })
        contentContainer.addView(spacer(dp(2)))
        contentContainer.addView(buildStepperSlider("冷却时间", triggerCooldown.toFloat(), 10f, 1000f, "0ms") { v -> val iv = v.toInt(); triggerCooldown = iv; onTriggerCooldown?.invoke(iv) })
        contentContainer.addView(spacer(dp(2)))
        contentContainer.addView(buildStepperSlider("向上波动", triggerUpFluctuation.toFloat(), 0f, 15f, "0ms") { v -> val iv = v.toInt(); triggerUpFluctuation = iv; onTriggerUpFluctuation?.invoke(iv) })
        contentContainer.addView(spacer(dp(2)))
        contentContainer.addView(buildStepperSlider("向下波动", triggerDownFluctuation.toFloat(), 0f, 15f, "0ms") { v -> val iv = v.toInt(); triggerDownFluctuation = iv; onTriggerDownFluctuation?.invoke(iv) })
        contentContainer.addView(spacer(dp(2)))
        contentContainer.addView(buildStepperSlider("触摸时间", triggerTouchDuration.toFloat(), 1f, 50f, "0ms") { v -> val iv = v.toInt(); triggerTouchDuration = iv; onTriggerTouchDuration?.invoke(iv) })
        contentContainer.addView(spacer(dp(2)))
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(2))
            addView(MaterialTextView(context).apply { text = "自动急停"; textSize = 11f; setTextColor(clOnSurfaceVariant); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(MaterialSwitch(context).apply { isChecked = autoStopEnabled; setOnCheckedChangeListener { _, c -> autoStopEnabled = c; onAutoStopEnabledChanged?.invoke(c) } })
        })
        contentContainer.addView(spacer(dp(2)))
        contentContainer.addView(buildStepperSlider("Y偏移", triggerOffsetYRatio, -2f, 0f, "%.0f%%") { triggerOffsetYRatio = it; onTriggerOffsetYRatioChanged?.invoke(it) })
        if (classMap.size > 1) {
            contentContainer.addView(spacer(dp(6)))
            contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
            contentContainer.addView(MaterialTextView(context).apply { text = "Y偏移(各类别)"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(clOnSurface) })
            contentContainer.addView(spacer(dp(4)))
            for ((id, name) in classMap.entries.sortedBy { it.key }) {
                val offset = classTriggerOffsets[id] ?: 0f
                contentContainer.addView(buildStepperSlider(name, offset, -2f, 0f, "%.0f%%") { v -> classTriggerOffsets[id] = v; onClassTriggerOffsetChanged?.invoke(id, v) })
            }
        }
        if (classMap.isNotEmpty()) {
            contentContainer.addView(spacer(dp(6)))
            contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
            contentContainer.addView(MaterialTextView(context).apply { text = "目标类别"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(clOnSurface) })
            contentContainer.addView(spacer(dp(4)))
            val allIds = classMap.keys.sorted()
            val effectiveTrigger = if (triggerClasses.isEmpty()) allIds.toMutableSet() else triggerClasses
            for ((id, name) in classMap.entries.sortedBy { it.key }) {
                val checked = id in effectiveTrigger
                contentContainer.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(2))
                    addView(MaterialTextView(context).apply { text = name; textSize = 12f; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
                    addView(MaterialSwitch(context).apply {
                        isChecked = checked
                        if (allIds.size <= 1) { isEnabled = false; alpha = 0.5f }
                        setOnCheckedChangeListener { _, c ->
                            if (triggerClasses.isEmpty()) triggerClasses = allIds.toMutableSet()
                            if (!c && triggerClasses.size <= 1 && id in triggerClasses) { isChecked = true; return@setOnCheckedChangeListener }
                            if (c) triggerClasses.add(id) else triggerClasses.remove(id)
                            onTriggerClassesChanged?.invoke(triggerClasses.toMutableSet())
                        }
                    })
                })
            }
        }
    }

    private fun buildModelTab() {
        contentContainer.addView(MaterialTextView(context).apply { text = "模型选择"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(clOnSurface) })
        contentContainer.addView(spacer(dp(2))); contentContainer.addView(divider()); contentContainer.addView(spacer(dp(8)))
        if (modelNames.isEmpty()) {
            contentContainer.addView(MaterialTextView(context).apply { text = "还没有导入模型，请回到主界面导入"; textSize = 13f; setTextColor(clOnSurfaceVariant); setPadding(0, dp(16), 0, dp(16)) })
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
        contentContainer.addView(buildStepperSlider("阈值", confidence, 0.10f, 0.90f, "%.2f") { confidence = it; onConfidenceChanged?.invoke(it) })
        contentContainer.addView(MaterialTextView(context).apply { text = "低于此值的检测结果将被过滤"; textSize = 9f; setTextColor(clOnSurfaceVariant); setPadding(0, dp(2), 0, 0) })
    }

    private fun buildSystem() {
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(MaterialTextView(context).apply { text = "显示截取范围"; textSize = 11f; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(MaterialSwitch(context).apply { isChecked = showCaptureRange; setOnCheckedChangeListener { _, c -> showCaptureRange = c; onShowCaptureRangeChanged?.invoke(c) } })
        })
        contentContainer.addView(buildStepperSlider("截取范围", range.toFloat(), 48f, 800f, "0px") { v -> val iv = v.toInt(); range = iv; onRangeChanged?.invoke(iv) })
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
        contentContainer.addView(MaterialTextView(context).apply { text = "在屏幕左 1/4 区域慢速画一个圆"; textSize = 10f; setTextColor(clOnSurfaceVariant) })
        contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(MaterialTextView(context).apply { text = "区域设置"; textSize = 11f; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(MaterialSwitch(context).apply { isChecked = false; setOnCheckedChangeListener { _, c -> if (c) { onAreaSettingsToggle?.invoke(); isChecked = false } } })
        })
        contentContainer.addView(MaterialTextView(context).apply { text = "配置开火/触发/瞄准区域"; textSize = 10f; setTextColor(clOnSurfaceVariant) })
        contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(MaterialTextView(context).apply { text = "录屏"; textSize = 11f; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(MaterialSwitch(context).apply { isChecked = recordEnabled; setOnCheckedChangeListener { _, c -> recordEnabled = c; onRecordEnabledChanged?.invoke(c) } })
        })
        contentContainer.addView(MaterialTextView(context).apply { text = "H265编码，60帧，保存到Pictures/Screenshots"; textSize = 10f; setTextColor(clOnSurfaceVariant) })
        contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(MaterialTextView(context).apply { text = "自动保存数据集"; textSize = 11f; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(MaterialSwitch(context).apply { isChecked = autoSaveDataset; setOnCheckedChangeListener { _, c -> autoSaveDataset = c; onAutoSaveDatasetChanged?.invoke(c) } })
        })
        contentContainer.addView(MaterialTextView(context).apply { text = "检测到目标时自动截图+YOLO标注，保存到应用外部存储"; textSize = 10f; setTextColor(clOnSurfaceVariant) })
        contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(divider()); contentContainer.addView(spacer(dp(6)))
        contentContainer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(MaterialTextView(context).apply { text = "显示推理信息"; textSize = 11f; setTextColor(clOnSurface); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
            addView(MaterialSwitch(context).apply { isChecked = showInferInfo; setOnCheckedChangeListener { _, c -> showInferInfo = c; onShowInferInfoChanged?.invoke(c) } })
        })
        contentContainer.addView(MaterialTextView(context).apply { text = "屏幕上方显示推理时长/预处理/后处理耗时及检测数"; textSize = 10f; setTextColor(clOnSurfaceVariant) })
    }

    private fun stepSizeForFmt(fmt: String): Float {
        if (fmt.startsWith("0")) return 1f
        val m = Regex("""\.(\d+)f""").find(fmt) ?: return 1f
        val n = m.groupValues[1].toInt()
        // 内部值 v 对应 display：普通走 v；百分比走 v*100，1% 显示差 = 0.01 内部差
        val base = Math.pow(10.0, -n.toDouble()).toFloat()
        return if (fmt.endsWith("%%")) base * 0.01f else base
    }

    private fun displayValue(v: Float, fmt: String): String = when {
        fmt.startsWith("0") -> "${v.toInt()}${fmt.removePrefix("0")}"
        fmt.endsWith("%%") -> "${fmt.removeSuffix("%%").format(v * 100)}%"
        else -> fmt.format(v)
    }

    private fun buildStepperSlider(
        label: String,
        value: Float,
        min: Float,
        max: Float,
        fmt: String,
        onChange: (Float) -> Unit
    ): LinearLayout = buildStepperSlider(label, value, min, max, fmt, 0f, onChange)

    private fun buildStepperSlider(
        label: String,
        value: Float,
        min: Float,
        max: Float,
        fmt: String,
        explicitStep: Float,
        onChange: (Float) -> Unit
    ): LinearLayout {
        val step = if (explicitStep > 0f) explicitStep else stepSizeForFmt(fmt)
        val valueTv = MaterialTextView(context).apply {
            text = displayValue(value, fmt); textSize = 12f; setTextColor(clPrimary); typeface = Typeface.DEFAULT_BOLD
        }
        val slider = Slider(context).apply {
            valueFrom = min; valueTo = max
            this.stepSize = step
            this.value = ((value.coerceIn(min, max) / step).roundToInt() * step).coerceIn(min, max)
            trackHeight = dp(4)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            isTickVisible = false
            setLabelFormatter { displayValue(it, fmt) }
            addOnChangeListener { _, v, fu -> if (fu) { valueTv.text = displayValue(v, fmt); onChange(v) } }
        }
        val bump = { delta: Float ->
            val snapped = ((slider.value + delta) / step).roundToInt() * step
            val clamped = snapped.coerceIn(min, max)
            if (clamped != slider.value) {
                slider.value = clamped
                valueTv.text = displayValue(clamped, fmt)
                onChange(clamped)
            }
        }
        val btnStyle = android.R.attr.borderlessButtonStyle
        val btnLp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        val minusBtn = MaterialButton(context, null, btnStyle).apply {
            text = "−"; textSize = 18f; isAllCaps = false
            insetTop = 0; insetBottom = 0; minWidth = 0; minimumWidth = 0
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = btnLp
            setOnClickListener { bump(-step) }
        }
        val plusBtn = MaterialButton(context, null, btnStyle).apply {
            text = "+"; textSize = 18f; isAllCaps = false
            insetTop = 0; insetBottom = 0; minWidth = 0; minimumWidth = 0
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = btnLp
            setOnClickListener { bump(step) }
        }
        return LinearLayout(context).apply { orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(MaterialTextView(context).apply { text = label; textSize = 11f; setTextColor(clOnSurfaceVariant); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
                addView(valueTv) })
            addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(minusBtn)
                addView(slider)
                addView(plusBtn) })
        }
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
