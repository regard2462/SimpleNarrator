package app.simplenarrator.audiobooks

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.simplenarrator.audiobooks.data.ColorPalette
import app.simplenarrator.audiobooks.ui.theme.Palettes
import app.simplenarrator.audiobooks.ui.theme.Palettes.pureBlack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }

    @Test
    fun everySchemeIsReadable() {
        for (p in ColorPalette.entries) for (dark in listOf(false, true)) {
            val s = Palettes.scheme(p, dark)
            val name = "$p ${if (dark) "dunkel" else "hell"}"
            assertTrue("$name Text auf Fläche", contrast(s.onSurface, s.surface) >= 7.0)
            assertTrue("$name Nebentext", contrast(s.onSurfaceVariant, s.surface) >= 4.5)
            assertTrue("$name Hauptfarbe", contrast(s.onPrimary, s.primary) >= 4.5)
            assertTrue("$name Auswahlfläche", contrast(s.onPrimaryContainer, s.primaryContainer) >= 4.5)
            assertTrue("$name Zweitfläche", contrast(s.onSecondaryContainer, s.secondaryContainer) >= 4.5)
            assertTrue("$name Hauptfarbe auf Fläche", contrast(s.primary, s.surface) >= 3.0)
            assertTrue("$name Karten", contrast(s.onSurface, s.surfaceContainerHigh) >= 7.0)
            if (dark) assertTrue("$name dunkel genug", s.surface.luminance() < 0.02f) else assertTrue("$name hell genug", s.surface.luminance() > 0.9f)
        }
    }

    @Test
    fun pureBlackKeepsTextReadable() {
        for (p in ColorPalette.entries) {
            val s = Palettes.scheme(p, true).pureBlack()
            assertEquals(Color.Black, s.background)
            assertEquals(Color.Black, s.surface)
            assertTrue(contrast(s.onSurface, s.surfaceContainerHighest) >= 7.0)
        }
    }

    @Test
    fun hslMatchesKnownColours() {
        assertEquals(Color(1f, 0f, 0f), Palettes.hsl(0f, 1f, 0.5f))
        assertEquals(Color(0f, 0f, 1f), Palettes.hsl(240f, 1f, 0.5f))
        assertEquals(Color(1f, 1f, 1f), Palettes.hsl(123f, 0.4f, 1f))
        assertEquals(Palettes.hsl(-30f, 1f, 0.5f), Palettes.hsl(330f, 1f, 0.5f))
    }
}
