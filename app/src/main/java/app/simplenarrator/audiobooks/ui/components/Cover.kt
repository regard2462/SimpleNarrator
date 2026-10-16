package app.simplenarrator.audiobooks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import coil.compose.AsyncImage
import app.simplenarrator.audiobooks.data.Entry
import java.io.File

/** Leitfarbe eines Buches: aus dem Titelbild oder aus dem Titel abgeleitet. */
fun bookColor(entry: Entry): Color {
    entry.color?.let { return Color(it) }
    return hashColor(entry.title)
}

fun hashColor(seed: String): Color {
    val h = ((seed.hashCode().toLong() and 0xFFFFFFFFL) % 360).toFloat()
    return Color(ColorUtils.HSLToColor(floatArrayOf(h, 0.42f, 0.42f)))
}

/** Farbe auf eine bestimmte Helligkeit bringen, Farbton und Sättigung bleiben. */
fun Color.withLightness(l: Float, maxSaturation: Float = 0.6f): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[1] = hsl[1].coerceAtMost(maxSaturation)
    hsl[2] = l
    return Color(ColorUtils.HSLToColor(hsl))
}

@Composable
fun Cover(
    entry: Entry,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    elevation: Dp = 0.dp,
) {
    val base = remember(entry.id, entry.color, entry.title) { bookColor(entry) }
    Box(
        modifier
            .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape) else Modifier)
            .clip(shape)
            .background(
                Brush.linearGradient(listOf(base.withLightness(0.46f), base.withLightness(0.26f))),
            ),
    ) {
        val cover = entry.cover
        if (cover != null) {
            AsyncImage(
                model = File(cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Placeholder(entry.title, entry.author)
        }
    }
}

@Composable
private fun Placeholder(title: String, author: String?) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val size = maxWidth
        val titleSize = (size.value * 0.105f).coerceIn(9f, 30f).sp
        Column(
            Modifier.padding(size * 0.09f),
            verticalArrangement = Arrangement.spacedBy(size * 0.03f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                color = Color.White,
                fontSize = titleSize,
                lineHeight = titleSize * 1.15f,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (!author.isNullOrBlank() && size > 70.dp) {
                Text(
                    author,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = titleSize * 0.62f,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
