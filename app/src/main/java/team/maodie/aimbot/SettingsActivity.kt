package team.maodie.aimbot

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class SettingsActivity : AppCompatActivity() {

    private val MD3_SURFACE = Color.parseColor("#FFFBFE")
    private val MD3_ON_SURFACE = Color.parseColor("#1C1B1F")

    private val displayDensity: Float by lazy { resources.displayMetrics.density }
    private fun dp(value: Int): Int = (value * displayDensity + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        scrollView.addView(content)
        root.addView(scrollView)
        return root
    }
}
