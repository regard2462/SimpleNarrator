package app.simplenarrator.audiobooks.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Einstellungen in SharedPreferences, beobachtbar als StateFlow. */
class Settings(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences(AudiobookFiles.PREFS, Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(read())
    override val flow: StateFlow<AppSettings> = _flow.asStateFlow()
    override val value: AppSettings get() = _flow.value

    private fun read(): AppSettings {
        val d = AppSettings()
        val roots = (prefs.getString("roots", null) ?: "")
            .split('\n')
            .filter { it.isNotBlank() }
            .map { line ->
                val single = line.startsWith("1|")
                LibraryRoot(line.substringAfter('|'), single)
            }
        return AppSettings(
            roots = roots,
            skipBackSec = prefs.getInt("skipBack", d.skipBackSec),
            skipForwardSec = prefs.getInt("skipForward", d.skipForwardSec),
            resumeRewindSec = prefs.getInt("resumeRewind", d.resumeRewindSec),
            smartRewind = prefs.getBoolean("smartRewind", d.smartRewind),
            defaultSpeed = prefs.getFloat("defaultSpeed", d.defaultSpeed),
            sleepMinutes = prefs.getInt("sleepMinutes", d.sleepMinutes),
            sleepFade = prefs.getBoolean("sleepFade", d.sleepFade),
            shakeToExtend = prefs.getBoolean("shake", d.shakeToExtend),
            headsetChapters = prefs.getBoolean("headsetChapters", d.headsetChapters),
            theme = enumOr(prefs.getString("theme", null), d.theme),
            // Bis 1.3 gab es nur den Schalter für die Farben des Hintergrundbilds
            palette = if (!prefs.contains("palette") && prefs.getBoolean("dynamicColor", false)) ColorPalette.WALLPAPER
            else enumOr(prefs.getString("palette", null), d.palette),
            pureBlack = prefs.getBoolean("pureBlack", d.pureBlack),
            grid = prefs.getBoolean("grid", d.grid),
            sort = enumOr(prefs.getString("sort", null), d.sort),
            hideFinished = prefs.getBoolean("hideFinished", d.hideFinished),
            webCovers = prefs.getBoolean("webCovers", d.webCovers),
        )
    }

    override fun update(change: (AppSettings) -> AppSettings) {
        _flow.update(change)
        val s = _flow.value
        prefs.edit {
            putString("roots", s.roots.joinToString("\n") { (if (it.single) "1|" else "0|") + it.uri })
            putInt("skipBack", s.skipBackSec)
            putInt("skipForward", s.skipForwardSec)
            putInt("resumeRewind", s.resumeRewindSec)
            putBoolean("smartRewind", s.smartRewind)
            putFloat("defaultSpeed", s.defaultSpeed)
            putInt("sleepMinutes", s.sleepMinutes)
            putBoolean("sleepFade", s.sleepFade)
            putBoolean("shake", s.shakeToExtend)
            putBoolean("headsetChapters", s.headsetChapters)
            putString("theme", s.theme.name)
            putString("palette", s.palette.name)
            putBoolean("pureBlack", s.pureBlack)
            remove("dynamicColor")
            putBoolean("grid", s.grid)
            putString("sort", s.sort.name)
            putBoolean("hideFinished", s.hideFinished)
            putBoolean("webCovers", s.webCovers)
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
