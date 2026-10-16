package app.simplenarrator.audiobooks.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import app.simplenarrator.audiobooks.data.ColorPalette
import kotlin.math.abs

/**
 * Die Farbschemata der App. Nachtblau (wie das App-Symbol) und Bernstein sind von Hand abgestimmt, die übrigen
 * entstehen aus wenigen Leitfarben: Hauptfarbe und Auswahlfläche je Modus, dazu ein Farbton für die Flächen.
 */
object Palettes {

    /** Leitfarben eines berechneten Schemas. [neutralHue]/[neutralSat]: Tönung der Flächen. */
    private class Keys(
        val hue: Float,
        val lightPrimary: Long,
        val lightContainer: Long,
        val lightOnContainer: Long,
        val darkPrimary: Long,
        val darkOnPrimary: Long,
        val darkContainer: Long,
        val darkOnContainer: Long,
        val neutralHue: Float = hue,
        val neutralSat: Float = 1f,
    )

    private val KEYS = mapOf(
        ColorPalette.PETROL to Keys(172f, 0xFF0E6B5F, 0xFFB8EFE3, 0xFF00201B, 0xFF7FD8C6, 0xFF003731, 0xFF11524A, 0xFFB8EFE3),
        ColorPalette.VIOLET to Keys(252f, 0xFF5B45C9, 0xFFE5DEFF, 0xFF170065, 0xFFC8BFFF, 0xFF2C1A8C, 0xFF4330A9, 0xFFE5DEFF),
        ColorPalette.FOREST to Keys(140f, 0xFF2E6B42, 0xFFB2F1C0, 0xFF00210D, 0xFF96D5A5, 0xFF00391B, 0xFF13522D, 0xFFB2F1C0),
        ColorPalette.RASPBERRY to Keys(335f, 0xFFA3245E, 0xFFFFD9E3, 0xFF3E001C, 0xFFFFB0C8, 0xFF5E1133, 0xFF7E1847, 0xFFFFD9E3),
        ColorPalette.GRAPHITE to Keys(205f, 0xFF2B5F7E, 0xFFC6E7FF, 0xFF001E2F, 0xFF7DD3FC, 0xFF00344A, 0xFF1F4A61, 0xFFC6E7FF, neutralHue = 215f, neutralSat = 0.3f),
        ColorPalette.SKY to Keys(210f, 0xFF1F65A8, 0xFFD3E4FF, 0xFF001C38, 0xFFA2C9FF, 0xFF00315C, 0xFF0F4A84, 0xFFD3E4FF),
        ColorPalette.SAGE to Keys(145f, 0xFF4A6656, 0xFFCCEBD7, 0xFF062016, 0xFFB0CFBB, 0xFF1C3528, 0xFF334D3F, 0xFFCCEBD7, neutralHue = 45f, neutralSat = 0.7f),
    )

    /** Schema für ein Farbschema; „Aus dem Hintergrundbild“ wählt der Aufrufer selbst, hier gilt dafür Nachtblau. */
    fun scheme(palette: ColorPalette, dark: Boolean): ColorScheme = when (palette) {
        ColorPalette.AMBER -> if (dark) AmberDark else AmberLight
        ColorPalette.NIGHT, ColorPalette.WALLPAPER -> if (dark) NightDark else NightLight
        else -> generated(KEYS.getValue(palette), dark)
    }

    /** Zwei Farben für das Auswahlfeld in den Einstellungen: Hauptfarbe hell und Hauptfarbe dunkel. */
    fun swatch(palette: ColorPalette): Pair<Color, Color> = scheme(palette, false).primary to scheme(palette, true).primary

    /** Schwarzer Hintergrund für OLED-Bildschirme; Flächen darüber behalten ihre Tönung, nur dunkler. */
    fun ColorScheme.pureBlack(): ColorScheme = copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = lerp(surfaceContainerLow, Color.Black, 0.5f),
        surfaceContainer = lerp(surfaceContainer, Color.Black, 0.45f),
        surfaceContainerHigh = lerp(surfaceContainerHigh, Color.Black, 0.35f),
        surfaceContainerHighest = lerp(surfaceContainerHighest, Color.Black, 0.3f),
    )

    /** HSL ohne Android-Klassen, damit die Schemata auch in Unit-Tests entstehen. */
    internal fun hsl(hue: Float, sat: Float, light: Float): Color {
        val s = sat.coerceIn(0f, 1f)
        val l = light.coerceIn(0f, 1f)
        val c = (1 - abs(2 * l - 1)) * s
        val hp = (((hue % 360f) + 360f) % 360f) / 60f
        val x = c * (1 - abs(hp % 2 - 1))
        val (r, g, b) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2
        return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
    }

    private fun generated(k: Keys, dark: Boolean): ColorScheme {
        fun n(s: Float, l: Float) = hsl(k.neutralHue, s * k.neutralSat, l)
        fun p(s: Float, l: Float) = hsl(k.hue, s * k.neutralSat.coerceAtLeast(0.5f), l)
        fun t(s: Float, l: Float) = hsl(k.hue + 40f, s * k.neutralSat.coerceAtLeast(0.5f), l)
        return if (!dark) {
            lightColorScheme(
                primary = Color(k.lightPrimary),
                onPrimary = Color.White,
                primaryContainer = Color(k.lightContainer),
                onPrimaryContainer = Color(k.lightOnContainer),
                secondary = p(0.25f, 0.36f),
                onSecondary = Color.White,
                secondaryContainer = p(0.45f, 0.9f),
                onSecondaryContainer = p(0.45f, 0.1f),
                tertiary = t(0.25f, 0.36f),
                onTertiary = Color.White,
                tertiaryContainer = t(0.45f, 0.9f),
                onTertiaryContainer = t(0.45f, 0.1f),
                background = n(0.5f, 0.985f),
                onBackground = n(0.12f, 0.11f),
                surface = n(0.5f, 0.985f),
                onSurface = n(0.12f, 0.11f),
                surfaceVariant = n(0.22f, 0.9f),
                onSurfaceVariant = n(0.1f, 0.29f),
                outline = n(0.07f, 0.48f),
                outlineVariant = n(0.12f, 0.79f),
                inverseSurface = n(0.08f, 0.19f),
                inverseOnSurface = n(0.4f, 0.95f),
                inversePrimary = Color(k.darkPrimary),
                surfaceDim = n(0.15f, 0.86f),
                surfaceBright = n(0.5f, 0.985f),
                surfaceContainerLowest = Color.White,
                surfaceContainerLow = n(0.35f, 0.965f),
                surfaceContainer = n(0.3f, 0.945f),
                surfaceContainerHigh = n(0.25f, 0.925f),
                surfaceContainerHighest = n(0.22f, 0.905f),
            )
        } else {
            darkColorScheme(
                primary = Color(k.darkPrimary),
                onPrimary = Color(k.darkOnPrimary),
                primaryContainer = Color(k.darkContainer),
                onPrimaryContainer = Color(k.darkOnContainer),
                secondary = p(0.35f, 0.78f),
                onSecondary = p(0.3f, 0.18f),
                secondaryContainer = p(0.25f, 0.27f),
                onSecondaryContainer = p(0.45f, 0.9f),
                tertiary = t(0.35f, 0.78f),
                onTertiary = t(0.3f, 0.18f),
                tertiaryContainer = t(0.25f, 0.27f),
                onTertiaryContainer = t(0.45f, 0.9f),
                background = n(0.38f, 0.075f),
                onBackground = n(0.3f, 0.91f),
                surface = n(0.38f, 0.075f),
                onSurface = n(0.3f, 0.91f),
                surfaceVariant = n(0.14f, 0.3f),
                onSurfaceVariant = n(0.16f, 0.8f),
                outline = n(0.08f, 0.6f),
                outlineVariant = n(0.14f, 0.3f),
                inverseSurface = n(0.3f, 0.91f),
                inverseOnSurface = n(0.15f, 0.2f),
                inversePrimary = Color(k.lightPrimary),
                surfaceDim = n(0.38f, 0.075f),
                surfaceBright = n(0.18f, 0.25f),
                surfaceContainerLowest = n(0.4f, 0.05f),
                surfaceContainerLow = n(0.3f, 0.115f),
                surfaceContainer = n(0.28f, 0.135f),
                surfaceContainerHigh = n(0.22f, 0.18f),
                surfaceContainerHighest = n(0.18f, 0.225f),
            )
        }
    }

    // ---------------------------------------------------------------- Nachtblau und Gold (wie das App-Symbol)

    /** Hell: nachtblaue Hauptfarbe, goldene Auswahlflächen. */
    private val NightLight = lightColorScheme(
        primary = Color(0xFF2D4A8C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFE6A3),
        onPrimaryContainer = Color(0xFF261A00),
        secondary = Color(0xFF4A5C8E),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDCE4FF),
        onSecondaryContainer = Color(0xFF001A43),
        tertiary = Color(0xFF5A5E72),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFDFE1F9),
        onTertiaryContainer = Color(0xFF171B2C),
        background = Color(0xFFFAF9FF),
        onBackground = Color(0xFF1A1B21),
        surface = Color(0xFFFAF9FF),
        onSurface = Color(0xFF1A1B21),
        surfaceVariant = Color(0xFFE1E2EF),
        onSurfaceVariant = Color(0xFF444652),
        outline = Color(0xFF757784),
        outlineVariant = Color(0xFFC5C6D3),
        inverseSurface = Color(0xFF2F3036),
        inverseOnSurface = Color(0xFFF1F0F7),
        inversePrimary = Color(0xFFF5C451),
        surfaceDim = Color(0xFFDAD9E2),
        surfaceBright = Color(0xFFFAF9FF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF4F3FB),
        surfaceContainer = Color(0xFFEEEDF6),
        surfaceContainerHigh = Color(0xFFE8E7F1),
        surfaceContainerHighest = Color(0xFFE2E2EC),
    )

    /** Dunkel: nachtblaue Flächen, Gold als Hauptfarbe. */
    private val NightDark = darkColorScheme(
        primary = Color(0xFFF5C451),
        onPrimary = Color(0xFF3D2E00),
        primaryContainer = Color(0xFF34497F),
        onPrimaryContainer = Color(0xFFFFE08A),
        secondary = Color(0xFFB6C6F2),
        onSecondary = Color(0xFF1E2F55),
        secondaryContainer = Color(0xFF2F4374),
        onSecondaryContainer = Color(0xFFDCE4FF),
        tertiary = Color(0xFFC3C6DD),
        onTertiary = Color(0xFF2C3042),
        tertiaryContainer = Color(0xFF424659),
        onTertiaryContainer = Color(0xFFDFE1F9),
        background = Color(0xFF0F1424),
        onBackground = Color(0xFFE2E4F0),
        surface = Color(0xFF0F1424),
        onSurface = Color(0xFFE2E4F0),
        surfaceVariant = Color(0xFF444859),
        onSurfaceVariant = Color(0xFFC5C7D9),
        outline = Color(0xFF8E91A3),
        outlineVariant = Color(0xFF444859),
        inverseSurface = Color(0xFFE2E4F0),
        inverseOnSurface = Color(0xFF2C3040),
        inversePrimary = Color(0xFF735B0C),
        surfaceDim = Color(0xFF0F1424),
        surfaceBright = Color(0xFF353A4C),
        surfaceContainerLowest = Color(0xFF0A0E1A),
        surfaceContainerLow = Color(0xFF171C2D),
        surfaceContainer = Color(0xFF1B2031),
        surfaceContainerHigh = Color(0xFF252A3C),
        surfaceContainerHighest = Color(0xFF303547),
    )

    // ---------------------------------------------------------------- Bernstein (die Farben bis 1.2)

    private val AmberLight = lightColorScheme(
        primary = Color(0xFF9A4A00),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDCC3),
        onPrimaryContainer = Color(0xFF321300),
        secondary = Color(0xFF765848),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDCC8),
        onSecondaryContainer = Color(0xFF2B160A),
        tertiary = Color(0xFF5E6135),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE3E6AE),
        onTertiaryContainer = Color(0xFF1B1D00),
        background = Color(0xFFFFF8F4),
        onBackground = Color(0xFF221A15),
        surface = Color(0xFFFFF8F4),
        onSurface = Color(0xFF221A15),
        surfaceVariant = Color(0xFFF4DED2),
        onSurfaceVariant = Color(0xFF52443B),
        outline = Color(0xFF85736A),
        outlineVariant = Color(0xFFD8C2B7),
        inverseSurface = Color(0xFF382E29),
        inverseOnSurface = Color(0xFFFFEDE4),
        inversePrimary = Color(0xFFFFB781),
        surfaceDim = Color(0xFFE8D7CE),
        surfaceBright = Color(0xFFFFF8F4),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFF1E9),
        surfaceContainer = Color(0xFFFBEBE2),
        surfaceContainerHigh = Color(0xFFF5E5DC),
        surfaceContainerHighest = Color(0xFFEFDFD7),
    )

    private val AmberDark = darkColorScheme(
        primary = Color(0xFFFFB781),
        onPrimary = Color(0xFF512400),
        primaryContainer = Color(0xFF733600),
        onPrimaryContainer = Color(0xFFFFDCC3),
        secondary = Color(0xFFE6BEAA),
        onSecondary = Color(0xFF432B1D),
        secondaryContainer = Color(0xFF5C4132),
        onSecondaryContainer = Color(0xFFFFDCC8),
        tertiary = Color(0xFFC7CA94),
        onTertiary = Color(0xFF30330B),
        tertiaryContainer = Color(0xFF464920),
        onTertiaryContainer = Color(0xFFE3E6AE),
        background = Color(0xFF19120D),
        onBackground = Color(0xFFF0DFD7),
        surface = Color(0xFF19120D),
        onSurface = Color(0xFFF0DFD7),
        surfaceVariant = Color(0xFF52443B),
        onSurfaceVariant = Color(0xFFD8C2B7),
        outline = Color(0xFFA08D83),
        outlineVariant = Color(0xFF52443B),
        inverseSurface = Color(0xFFF0DFD7),
        inverseOnSurface = Color(0xFF382E29),
        inversePrimary = Color(0xFF9A4A00),
        surfaceDim = Color(0xFF19120D),
        surfaceBright = Color(0xFF413732),
        surfaceContainerLowest = Color(0xFF130D09),
        surfaceContainerLow = Color(0xFF221A15),
        surfaceContainer = Color(0xFF261E19),
        surfaceContainerHigh = Color(0xFF312823),
        surfaceContainerHighest = Color(0xFF3C332E),
    )
}
