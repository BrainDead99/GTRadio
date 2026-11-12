package com.sc.gtradio

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.card.MaterialCardView

data class SeriesPalette(
    val toolbarBg: Int,
    val statusBarBg: Int,
    val toolbarText: Int,
    val screenBg: Int,
    val cardBg: Int,
    val cardStroke: Int,
    val primaryText: Int,
    val accentText: Int,
    val playerBg: Int,
    val lightStatusBar: Boolean,
    val headerFontRes: Int,
    val stationFontRes: Int
)

object GtaThemeHelper {
    var activeSeries: String = ""

    fun getPalette(seriesName: String): SeriesPalette {
        val u = seriesName.uppercase()
        return when {
            u.contains("SAN ANDREAS") || u.contains("GTA SA") || u.contains("GTASA") -> SeriesPalette(
                toolbarBg = Color.parseColor("#101010"),
                statusBarBg = Color.parseColor("#000000"),
                toolbarText = Color.parseColor("#F5B82E"), // SA HUD Gold
                screenBg = Color.parseColor("#0A0E0A"),
                cardBg = Color.parseColor("#182018"),
                cardStroke = Color.parseColor("#3A7D2C"), // Grove Street Green
                primaryText = Color.parseColor("#FFFFFF"),
                accentText = Color.parseColor("#66BB6A"),
                playerBg = Color.parseColor("#121812"),
                lightStatusBar = false,
                headerFontRes = R.font.diploma,
                stationFontRes = R.font.bankgothic_bold
            )
            u.contains("VICE CITY") || u.contains("GTA VC") || u.contains("GTAVC") || u.contains("VCS") -> SeriesPalette(
                toolbarBg = Color.parseColor("#1B0636"),
                statusBarBg = Color.parseColor("#110322"),
                toolbarText = Color.parseColor("#FF2A85"), // 80s Neon Pink
                screenBg = Color.parseColor("#120424"),
                cardBg = Color.parseColor("#240B42"),
                cardStroke = Color.parseColor("#00E5FF"), // Vice Cyan
                primaryText = Color.parseColor("#FFFFFF"),
                accentText = Color.parseColor("#00E5FF"),
                playerBg = Color.parseColor("#1E0738"),
                lightStatusBar = false,
                headerFontRes = R.font.pricedown,
                stationFontRes = R.font.bankgothic_bold
            )
            u.contains("III") || u.contains("GTA 3") || u.contains("LIBERTY") -> SeriesPalette(
                toolbarBg = Color.parseColor("#181510"),
                statusBarBg = Color.parseColor("#0D0B08"),
                toolbarText = Color.parseColor("#FFA726"),
                screenBg = Color.parseColor("#100E0B"),
                cardBg = Color.parseColor("#1F1B14"),
                cardStroke = Color.parseColor("#FFA726"),
                primaryText = Color.parseColor("#FFF8E1"),
                accentText = Color.parseColor("#FFB74D"),
                playerBg = Color.parseColor("#18140F"),
                lightStatusBar = false,
                headerFontRes = R.font.pricedown,
                stationFontRes = R.font.bankgothic_bold
            )
            else -> SeriesPalette(
                toolbarBg = Color.parseColor("#FCB017"),
                statusBarBg = Color.parseColor("#FCBD1C"),
                toolbarText = Color.parseColor("#000000"),
                screenBg = Color.parseColor("#121212"),
                cardBg = Color.parseColor("#1E1E1E"),
                cardStroke = Color.parseColor("#333333"),
                primaryText = Color.parseColor("#FFFFFF"),
                accentText = Color.parseColor("#FCB017"),
                playerBg = Color.parseColor("#181818"),
                lightStatusBar = true,
                headerFontRes = R.font.pricedown,
                stationFontRes = R.font.bankgothic_bold
            )
        }
    }

    fun loadFont(context: Context, fontRes: Int, fallback: Typeface = Typeface.DEFAULT_BOLD): Typeface {
        return try {
            ResourcesCompat.getFont(context, fontRes) ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    fun applyToolbarAndScreen(activity: Activity, toolbar: Toolbar, rootView: View, seriesName: String = activeSeries) {
        val p = getPalette(seriesName)
        toolbar.setBackgroundColor(p.toolbarBg)
        toolbar.setTitleTextColor(p.toolbarText)
        toolbar.navigationIcon?.setTint(p.toolbarText)
        toolbar.overflowIcon?.setTint(p.toolbarText)
        rootView.setBackgroundColor(p.screenBg)
        activity.window.statusBarColor = p.statusBarBg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val decor = activity.window.decorView
            decor.systemUiVisibility = if (p.lightStatusBar) {
                decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        }
        val headerFont = loadFont(activity, p.headerFontRes)
        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(i)
            if (child is TextView) {
                child.typeface = headerFont
                child.textSize = 22f
            }
        }
    }

    fun styleCard(card: MaterialCardView, titleView: TextView, seriesName: String = activeSeries) {
        val p = getPalette(seriesName)
        card.setCardBackgroundColor(p.cardBg)
        card.strokeColor = p.cardStroke
        card.strokeWidth = 3
        titleView.setTextColor(p.primaryText)
        titleView.typeface = loadFont(card.context, p.stationFontRes)
    }

    fun stylePlayerBar(
        root: View,
        divider: View,
        titleView: TextView,
        subtitleView: TextView,
        buttons: List<ImageButton>,
        seriesName: String = activeSeries
    ) {
        val p = getPalette(seriesName)
        root.setBackgroundColor(p.playerBg)
        divider.setBackgroundColor(p.cardStroke)
        titleView.setTextColor(p.primaryText)
        titleView.typeface = loadFont(root.context, p.stationFontRes)
        subtitleView.setTextColor(p.accentText)
        val tint = ColorStateList.valueOf(p.toolbarText)
        buttons.forEach { it.imageTintList = tint }
    }
}
