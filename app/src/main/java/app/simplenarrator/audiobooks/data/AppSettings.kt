package app.simplenarrator.audiobooks.data

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Ein Bibliotheksordner. [single] = der Ordner selbst ist ein einziges Hörbuch. */
@Serializable
data class LibraryRoot(val uri: String, val single: Boolean)

enum class SortOrder { RECENT, TITLE, AUTHOR, ADDED, LENGTH, PROGRESS }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Farbschemata; NIGHT passt zum App-Symbol, WALLPAPER nimmt die Farben des Hintergrundbilds (ab Android 12). */
enum class ColorPalette { NIGHT, PETROL, VIOLET, FOREST, RASPBERRY, GRAPHITE, SKY, SAGE, AMBER, WALLPAPER }

@Serializable
data class AppSettings(
    val roots: List<LibraryRoot> = emptyList(),
    val skipBackSec: Int = 15,
    val skipForwardSec: Int = 30,
    val resumeRewindSec: Int = 5,
    val smartRewind: Boolean = true,
    val defaultSpeed: Float = 1f,
    val sleepMinutes: Int = 30,
    val sleepFade: Boolean = true,
    val shakeToExtend: Boolean = true,
    val headsetChapters: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val palette: ColorPalette = ColorPalette.NIGHT,
    /** Dunkles Design mit schwarzem statt nachtblauem Hintergrund. */
    val pureBlack: Boolean = false,
    val grid: Boolean = true,
    val sort: SortOrder = SortOrder.RECENT,
    val hideFinished: Boolean = false,
    /** Titelbilder für Bücher ohne eigenes aus dem Internet laden. */
    val webCovers: Boolean = true,
)

/** Einstellungen, beobachtbar; gespeichert wird je nach Plattform anders. */
interface SettingsStore {
    val flow: StateFlow<AppSettings>
    val value: AppSettings get() = flow.value
    fun update(change: (AppSettings) -> AppSettings)
}
