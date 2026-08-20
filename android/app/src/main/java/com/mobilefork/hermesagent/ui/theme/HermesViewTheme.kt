package com.mobilefork.hermesagent.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/** Theme snapshot for Android Views, RemoteViews, overlays, and notifications. */
data class HermesViewPalette(
    val primary: Int,
    val secondary: Int,
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val onPrimary: Int,
    val onBackground: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val lightCanvas: Boolean,
    val cardCornerRadiusDp: Float,
    val fontScale: Float,
)

fun hermesViewPalette(context: Context): HermesViewPalette {
    val settings = AppSettingsStore(context.applicationContext).load()
    val primary = parseOpaqueColor(settings.themePrimaryHex, AppSettings.DEFAULT_THEME_PRIMARY_HEX)
    val secondary = parseOpaqueColor(settings.themeSecondaryHex, AppSettings.DEFAULT_THEME_SECONDARY_HEX)
    val background = parseOpaqueColor(settings.themeBackgroundHex, AppSettings.DEFAULT_THEME_BACKGROUND_HEX)
    val surface = parseOpaqueColor(settings.themeSurfaceHex, AppSettings.DEFAULT_THEME_SURFACE_HEX)
    val surfaceVariant = parseOpaqueColor(settings.themeSurfaceVariantHex, AppSettings.DEFAULT_THEME_SURFACE_VARIANT_HEX)
    return HermesViewPalette(
        primary = primary,
        secondary = secondary,
        background = background,
        surface = surface,
        surfaceVariant = surfaceVariant,
        onPrimary = readableViewColor(primary),
        onBackground = readableViewColor(background),
        onSurface = readableViewColor(surface),
        onSurfaceVariant = readableViewColor(surfaceVariant),
        lightCanvas = viewRelativeLuminance(background) >= 0.46,
        cardCornerRadiusDp = hermesViewCornerRadiusDp(settings.themeCardShape),
        fontScale = AppSettings.normalizeUiFontScale(settings.uiFontScale),
    )
}

/**
 * Gives non-Compose activities the same persisted language contract as the app shell.
 * This avoids making Tasker/provider pages depend on the device-wide locale.
 */
fun Context.hermesLocalizedContext(): Context {
    val language = AppLanguage.fromTag(AppSettingsStore(applicationContext).load().languageTag)
    val locale = Locale.forLanguageTag(language.tag)
    val configuration = Configuration(resources.configuration).apply {
        setLocale(locale)
        setLayoutDirection(locale)
    }
    return createConfigurationContext(configuration)
}

fun Context.hermesDp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

internal fun hermesViewCornerRadiusDp(cardShape: String): Float = when (cardShape.trim().lowercase()) {
    "square", "squared" -> 4f
    "soft" -> 14f
    else -> 22f
}

internal fun hermesViewHorizontalPaddingDp(widthDp: Int): Float = when {
    widthDp >= 840 -> 32f
    widthDp >= 600 -> 24f
    widthDp <= 360 -> 12f
    else -> 16f
}

/** A dependency-free gradient matching the Compose HermesBackdrop colour relationships. */
fun hermesViewBackdropDrawable(palette: HermesViewPalette): GradientDrawable {
    return GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            ColorUtils.blendARGB(palette.background, palette.primary, if (palette.lightCanvas) 0.08f else 0.16f),
            palette.background,
            ColorUtils.blendARGB(palette.background, palette.secondary, if (palette.lightCanvas) 0.05f else 0.09f),
        ),
    )
}

fun hermesViewPanelDrawable(
    context: Context,
    palette: HermesViewPalette,
    elevated: Boolean = false,
): GradientDrawable {
    val panelBase = if (elevated) palette.surfaceVariant else palette.surface
    val panelAlpha = if (palette.lightCanvas) {
        if (elevated) 232 else 214
    } else {
        if (elevated) 224 else 196
    }
    val border = ColorUtils.setAlphaComponent(
        ColorUtils.blendARGB(palette.surfaceVariant, palette.onSurface, if (palette.lightCanvas) 0.24f else 0.34f),
        184,
    )
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(ColorUtils.setAlphaComponent(panelBase, panelAlpha))
        cornerRadius = context.hermesDp(palette.cardCornerRadiusDp).toFloat()
        setStroke(maxOf(1, context.hermesDp(1f)), border)
    }
}

fun hermesViewButtonDrawable(context: Context, palette: HermesViewPalette): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(palette.primary)
        cornerRadius = context.hermesDp((palette.cardCornerRadiusDp * 0.72f).coerceAtLeast(3f)).toFloat()
    }
}

@Suppress("DEPRECATION")
fun Activity.applyHermesViewWindowTheme(palette: HermesViewPalette) {
    window.setBackgroundDrawable(hermesViewBackdropDrawable(palette))
    window.statusBarColor = palette.background
    window.navigationBarColor = palette.surface
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = palette.lightCanvas
        isAppearanceLightNavigationBars = palette.lightCanvas
    }
}

/**
 * Applies persisted colour, shape and font-scale settings to framework widgets. The traversal
 * deliberately leaves WebView content alone; only Hermes-owned Android Views are styled.
 */
fun applyHermesViewTree(root: View, palette: HermesViewPalette) {
    when (root) {
        is Button -> {
            root.setTextColor(palette.onPrimary)
            root.background = hermesViewButtonDrawable(root.context, palette)
            root.isAllCaps = false
            root.minHeight = maxOf(root.minHeight, root.context.hermesDp(44f))
        }

        is EditText -> {
            root.setTextColor(palette.onSurface)
            root.setHintTextColor(ColorUtils.setAlphaComponent(palette.onSurface, 168))
            root.backgroundTintList = ColorStateList.valueOf(palette.primary)
        }

        is TextView -> root.setTextColor(palette.onSurfaceVariant)
        is Spinner -> {
            root.background = hermesViewPanelDrawable(root.context, palette, elevated = true)
            root.setPopupBackgroundDrawable(hermesViewPanelDrawable(root.context, palette, elevated = true))
        }
    }
    if (root is TextView) {
        root.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, root.textSize * palette.fontScale)
    }
    if (root is ViewGroup) {
        repeat(root.childCount) { index -> applyHermesViewTree(root.getChildAt(index), palette) }
    }
}

/** Responsive, centred container for Hermes-owned framework-View pages. */
fun Context.hermesScrollablePage(
    content: View,
    palette: HermesViewPalette,
    topInsetPx: Int = 0,
): ScrollView {
    val widthDp = resources.configuration.screenWidthDp
    val horizontalPaddingDp = hermesViewHorizontalPaddingDp(widthDp)
    val outerPadding = hermesDp(horizontalPaddingDp)
    val maxContentWidth = hermesDp(760f)
    val availableWidth = (resources.displayMetrics.widthPixels - outerPadding * 2).coerceAtLeast(1)
    val contentWidth = minOf(availableWidth, maxContentWidth)
    val frame = FrameLayout(this).apply {
        setPadding(outerPadding, topInsetPx + outerPadding, outerPadding, outerPadding)
        addView(
            content,
            FrameLayout.LayoutParams(contentWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            },
        )
    }
    return ScrollView(this).apply {
        isFillViewport = true
        background = hermesViewBackdropDrawable(palette)
        addView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}

class HermesChoiceAdapter<T>(
    context: Context,
    values: List<T>,
    private val palette: HermesViewPalette,
) : ArrayAdapter<T>(context, android.R.layout.simple_spinner_item, values) {
    init {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return style(super.getView(position, convertView, parent))
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        return style(super.getDropDownView(position, convertView, parent))
    }

    private fun style(view: View): View {
        (view as? TextView)?.apply {
            setTextColor(palette.onSurfaceVariant)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f * palette.fontScale)
            setPadding(context.hermesDp(12f), context.hermesDp(10f), context.hermesDp(12f), context.hermesDp(10f))
        }
        return view
    }
}

@ColorInt
private fun parseOpaqueColor(value: String, fallback: String): Int {
    val resolved = runCatching { Color.parseColor(value.trim()) }
        .getOrElse { Color.parseColor(fallback) }
    return ColorUtils.setAlphaComponent(resolved, 255)
}

@ColorInt
internal fun readableViewColor(@ColorInt color: Int): Int {
    // Literal ARGB values keep this pure helper usable in local JVM tests;
    // android.graphics.Color channel helpers are framework stubs outside Robolectric.
    val darkCandidate = 0xFF111318.toInt()
    val lightCandidate = 0xFFF8FAFC.toInt()
    val background = color or 0xFF000000.toInt()
    val darkContrast = viewContrastRatio(darkCandidate, background)
    val lightContrast = viewContrastRatio(lightCandidate, background)
    return if (darkContrast >= lightContrast) darkCandidate else lightCandidate
}

private fun viewContrastRatio(first: Int, second: Int): Double {
    val lighter = maxOf(viewRelativeLuminance(first), viewRelativeLuminance(second))
    val darker = minOf(viewRelativeLuminance(first), viewRelativeLuminance(second))
    return (lighter + 0.05) / (darker + 0.05)
}

private fun viewRelativeLuminance(color: Int): Double {
    fun linearChannel(shift: Int): Double {
        val encoded = ((color ushr shift) and 0xFF) / 255.0
        return if (encoded <= 0.04045) encoded / 12.92 else ((encoded + 0.055) / 1.055).pow(2.4)
    }

    return 0.2126 * linearChannel(16) +
        0.7152 * linearChannel(8) +
        0.0722 * linearChannel(0)
}
