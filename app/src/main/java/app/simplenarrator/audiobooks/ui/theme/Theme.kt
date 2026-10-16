package app.simplenarrator.audiobooks.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.simplenarrator.audiobooks.data.ColorPalette
import app.simplenarrator.audiobooks.data.ThemeMode
import app.simplenarrator.audiobooks.ui.theme.Palettes.pureBlack

private val BaseType = Typography()

private val AppType = Typography(
    displaySmall = BaseType.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineLarge = BaseType.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = BaseType.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = BaseType.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseType.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseType.titleSmall.copy(fontWeight = FontWeight.SemiBold, lineHeight = 18.sp),
    labelLarge = BaseType.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun AppTheme(mode: ThemeMode, palette: ColorPalette, pureBlack: Boolean, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    val context = LocalContext.current
    val base: ColorScheme = when {
        palette == ColorPalette.WALLPAPER && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> Palettes.scheme(palette, dark)
    }
    val scheme = if (dark && pureBlack) base.pureBlack() else base
    MaterialTheme(colorScheme = scheme, typography = AppType, content = content)
}
