package app.simplenarrator.audiobooks.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Kreispfeil mit Sekundenzahl, rückwärts oder vorwärts. */
@Composable
fun SkipIcon(seconds: Int, forward: Boolean, size: Dp = 32.dp, contentDescription: String? = null) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Rounded.Replay,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(size)
                .graphicsLayer { if (forward) scaleX = -1f },
            tint = LocalContentColor.current,
        )
        val fontSize = with(LocalDensity.current) { (size * 0.29f).toSp() }
        Text(
            seconds.toString(),
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            color = LocalContentColor.current,
            modifier = Modifier.graphicsLayer { translationY = size.toPx() * 0.06f },
        )
    }
}
