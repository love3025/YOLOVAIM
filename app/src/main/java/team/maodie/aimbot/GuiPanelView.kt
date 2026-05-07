package team.maodie.aimbot

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

/**
 * 悬浮 GUI 面板
 * 风格：暗色工业感，无衬线细字，红色强调
 *
 * 回调：
 *   onEnabledChanged(Boolean)
 *   onSpeedChanged(Float)       0.0 ~ 1.0
 *   onRangeChanged(Int)         像素半径
 */
class GuiPanelView(context: Context) : LinearLayout(context) {

    var onEnabledChanged: ((Boolean) -> Unit)? = null
    var onSpeedChanged:   ((Float)   -> Unit)? = null
    var onRangeChanged:   ((Int)     -> Unit)? = null
    var onClose:          (() -> Unit)?         = null

    // 当前值（外部可读）
    var aimbotEnabled = false
    var speed   = 0.3f
    var range   = 300

    private val COLOR_BG      = Color.parseColor("#0F0F0F")
    private val COLOR_PANEL   = Color.parseColor("#181818")
    private val COLOR_TAB_ACT = Color.parseColor("#CC2222")
    private val COLOR_TAB_OFF = Color.parseColor("#282828")
    private val COLOR_TEXT    = Color.WHITE
    private val COLOR_MUTED   = Color.parseColor("#888888")
    private val COLOR_DIVIDER = Color.parseColor("#2A2A2A")
    private val COLOR_TRACK   = Color.parseColor("#2A2A2A")
    private val COLOR_THUMB   = Color.parseColor("#CC2222")

    private var activeTab = 0   // 0=Aimbot 1=Triggerbot 2=AntiFlash

    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, 0)
        setBackgroundColor(COLOR_BG)
        // 圆角外框
        background = GradientDrawable().apply {
            setColor(COLOR_BG)
            cornerRadius = dp(14).toFloat()
        }
        elevation = dp(8).toFloat()
        buildUI()
    }

    // ─────────────────────────────────────────
    private fun buildUI() {
        removeAllViews()

        // ── 顶栏：标题 + 关闭 ──────────────────
        addView(buildTopBar())

        // ── Tab 行 ──────────────────────────────
        addView(buildTabRow())

        // ── 分割线 ──────────────────────────────
        addView(divider())

        // ── 内容区 ──────────────────────────────
        addView(buildContent())
    }

    // ─────────────────────────────────────────
    private fun buildTopBar(): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(10))
            setBackgroundColor(Color.TRANSPARENT)

            // 红色小方块装饰
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(4), dp(18)).apply { marginEnd = dp(10) }
                background = GradientDrawable().apply {
                    setColor(COLOR_TAB_ACT)
                    cornerRadius = dp(2).toFloat()
                }
            })

            // 标题
            addView(TextView(context).apply {
                text = "AIMBOT"
                textSize = 13f
                setTextColor(COLOR_TEXT)
                letterSpacing = 0.18f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })

            // 关闭按钮
            addView(TextView(context).apply {
                text = "✕"
                textSize = 16f
                setTextColor(COLOR_MUTED)
                setPadding(dp(8), dp(4), dp(4), dp(4))
                setOnClickListener { onClose?.invoke() }
            })
        }
    }

    // ─────────────────────────────────────────
    private fun buildTabRow(): LinearLayout {
        val tabs = listOf("AIMBOT", "TRIGGER", "ANTIFLASH")
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(COLOR_PANEL)

            tabs.forEachIndexed { idx, name ->
                addView(buildTab(name, idx))
                if (idx < tabs.size - 1) {
                    addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(1), MATCH_PARENT).apply {
                            marginStart = dp(4); marginEnd = dp(4)
                        }
                        setBackgroundColor(COLOR_DIVIDER)
                    })
                }
            }
        }
    }

    private fun buildTab(name: String, idx: Int): TextView {
        return TextView(context).apply {
            text = name
            textSize = 10f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            updateTabStyle(this, idx == activeTab)
            setOnClickListener {
                activeTab = idx
                // 重建 UI 刷新 tab 高亮
                buildUI()
            }
        }
    }

    private fun updateTabStyle(tv: TextView, active: Boolean) {
        tv.setTextColor(if (active) COLOR_TEXT else COLOR_MUTED)
        tv.background = GradientDrawable().apply {
            setColor(if (active) COLOR_TAB_ACT else COLOR_TAB_OFF)
            cornerRadius = dp(6).toFloat()
        }
    }

    // ─────────────────────────────────────────
    private fun buildContent(): View {
        return when (activeTab) {
            0    -> buildAimbotContent()
            else -> buildEmptyContent(if (activeTab == 1) "TRIGGERBOT" else "ANTI FLASH")
        }
    }

    // ── Aimbot 内容 ────────────────────────────
    private fun buildAimbotContent(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            setBackgroundColor(COLOR_BG)

            // 总开关行
            addView(buildSwitchRow())
            addView(divider())

            // Speed 滑条
            addView(buildSliderSection(
                label   = "SPEED",
                value   = "%.2f".format(speed),
                progress = (speed * 100).toInt(),
                max     = 100
            ) { v ->
                speed = v / 100f
                onSpeedChanged?.invoke(speed)
            })

            addView(spacer(dp(4)))

            // Range 滑条
            addView(buildSliderSection(
                label   = "RANGE",
                value   = "${range}px",
                progress = range,
                max     = 800
            ) { v ->
                range = v
                onRangeChanged?.invoke(range)
            })
        }
    }

    private fun buildSwitchRow(): LinearLayout {
        val statusTv = TextView(context).apply {
            text = if (aimbotEnabled) "ON" else "OFF"
            textSize = 10f
            letterSpacing = 0.1f
            setTextColor(if (aimbotEnabled) COLOR_TAB_ACT else COLOR_MUTED)
            typeface = Typeface.DEFAULT_BOLD
        }

        val toggle = buildToggleButton()

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(12))

            addView(TextView(context).apply {
                text = "AIM ASSIST"
                textSize = 12f
                setTextColor(COLOR_TEXT)
                letterSpacing = 0.08f
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(statusTv)
            addView(spacer(dp(10)))
            addView(toggle.apply {
                setOnClickListener {
                    aimbotEnabled = !aimbotEnabled
                    statusTv.text = if (aimbotEnabled) "ON" else "OFF"
                    statusTv.setTextColor(if (aimbotEnabled) COLOR_TAB_ACT else COLOR_MUTED)
                    updateToggleStyle(this, aimbotEnabled)
                    onEnabledChanged?.invoke(aimbotEnabled)
                }
            })
        }
    }

    private fun buildToggleButton(): TextView {
        return TextView(context).apply {
            text = if (aimbotEnabled) "●" else "○"
            textSize = 22f
            setTextColor(if (aimbotEnabled) COLOR_TAB_ACT else COLOR_MUTED)
            setPadding(dp(4), 0, dp(4), 0)
        }
    }

    private fun updateToggleStyle(tv: TextView, on: Boolean) {
        tv.text = if (on) "●" else "○"
        tv.setTextColor(if (on) COLOR_TAB_ACT else COLOR_MUTED)
    }

    private fun buildSliderSection(
        label: String, value: String,
        progress: Int, max: Int,
        onChange: (Int) -> Unit
    ): LinearLayout {
        val valueTv = TextView(context).apply {
            text = value
            textSize = 10f
            setTextColor(COLOR_TAB_ACT)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        }

        val seekBar = SeekBar(context).apply {
            this.max = max
            this.progress = progress
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(28))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                    onChange(v)
                    valueTv.text = if (max == 100) "%.2f".format(v / 100f) else "${v}px"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

        return LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(6), 0, dp(6))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(6))
                addView(TextView(context).apply {
                    text = label
                    textSize = 10f
                    setTextColor(COLOR_MUTED)
                    letterSpacing = 0.15f
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                })
                addView(valueTv)
            })
            addView(seekBar)
        }
    }

    private fun buildSeekTrack(): android.graphics.drawable.Drawable {
        return GradientDrawable().apply {
            setColor(COLOR_TRACK)
            cornerRadius = dp(3).toFloat()
            setSize(MATCH_PARENT, dp(4))
        }
    }

    private fun buildSeekThumb(): android.graphics.drawable.Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_THUMB)
            setSize(dp(16), dp(16))
        }
    }

    // ── 空白 Tab 内容 ──────────────────────────
    private fun buildEmptyContent(name: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(40), dp(16), dp(40))
            setBackgroundColor(COLOR_BG)

            addView(TextView(context).apply {
                text = name
                textSize = 11f
                setTextColor(COLOR_MUTED)
                letterSpacing = 0.2f
                gravity = Gravity.CENTER
            })
            addView(spacer(dp(6)))
            addView(TextView(context).apply {
                text = "— COMING SOON —"
                textSize = 9f
                setTextColor(Color.parseColor("#444444"))
                letterSpacing = 0.15f
                gravity = Gravity.CENTER
            })
        }
    }

    // ─────────────────────────────────────────
    private fun divider() = View(context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, dp(1))
        setBackgroundColor(COLOR_DIVIDER)
    }

    private fun spacer(h: Int) = View(context).apply {
        layoutParams = LayoutParams(1, h)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}