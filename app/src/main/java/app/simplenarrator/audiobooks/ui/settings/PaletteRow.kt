package app.simplenarrator.audiobooks.ui.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.simplenarrator.R
import app.simplenarrator.audiobooks.data.ColorPalette
import app.simplenarrator.audiobooks.ui.theme.Palettes

/**
 * Auswahl des Farbschemas als Reihe runder Farbfelder: außen die helle, innen die dunkle Hauptfarbe.
 * [textInset]/[rowInset]: Abstand zum Rand für Text und Farbreihe, passend zur jeweiligen Einstellungsseite.
 */
@Composable
fun PaletteRow(selected: ColorPalette, textInset: Dp = 24.dp, rowInset: Dp = 20.dp, onSelect: (ColorPalette) -> Unit) {
    val context = LocalContext.current
    val options = ColorPalette.entries.filter { it != ColorPalette.WALLPAPER || Build.VERSION.SDK_INT >= 31 }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(stringResource(R.string.ab_color_scheme), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = textInset))
        Text(
            stringResource(selected.label()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = textInset),
        )
        Spacer(Modifier.size(12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = rowInset),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(options, key = { it.name }) { p ->
                val (outer, inner) = if (p == ColorPalette.WALLPAPER && Build.VERSION.SDK_INT >= 31) {
                    dynamicLightColorScheme(context).primary to dynamicDarkColorScheme(context).primary
                } else {
                    Palettes.swatch(p)
                }
                val name = stringResource(p.label())
                val isSelected = p == selected
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(outer)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        )
                        .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(p) }
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(inner), contentAlignment = Alignment.Center) {
                        if (isSelected) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = outer)
                    }
                }
            }
        }
    }
}

@StringRes
private fun ColorPalette.label(): Int = when (this) {
    ColorPalette.NIGHT -> R.string.ab_palette_night
    ColorPalette.PETROL -> R.string.ab_palette_petrol
    ColorPalette.VIOLET -> R.string.ab_palette_violet
    ColorPalette.FOREST -> R.string.ab_palette_forest
    ColorPalette.RASPBERRY -> R.string.ab_palette_raspberry
    ColorPalette.GRAPHITE -> R.string.ab_palette_graphite
    ColorPalette.SKY -> R.string.ab_palette_sky
    ColorPalette.SAGE -> R.string.ab_palette_sage
    ColorPalette.AMBER -> R.string.ab_palette_amber
    ColorPalette.WALLPAPER -> R.string.ab_palette_wallpaper
}
