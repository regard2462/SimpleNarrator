package app.simplenarrator.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Das Farbschema der ganzen App steht in audiobooks/ui/theme/Theme.kt (AppTheme).

/** Fließtext im Reader: Serif, großzügige Zeilenhöhe, angenehme Zeilenlänge. */
val ReaderBody = TextStyle(fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 30.sp)
val ReaderHeading = TextStyle(fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
