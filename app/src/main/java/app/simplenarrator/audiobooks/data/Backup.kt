package app.simplenarrator.audiobooks.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class BackupBook(
    val id: String,
    val path: String? = null,
    val title: String,
    val author: String? = null,
    val state: BookState,
)

/**
 * Einstellungen in einer Exportdatei. Die Bibliotheksordner fehlen bewusst: Ihre Zugriffsrechte gelten nur für die
 * Installation, die sie erhalten hat. Fehlende Felder (ältere Dateien) bekommen die Werte ab Werk.
 */
@Serializable
data class SettingsBackup(
    val skipBackSec: Int = 15,
    val skipForwardSec: Int = 30,
    val resumeRewindSec: Int = 5,
    val smartRewind: Boolean = true,
    val defaultSpeed: Float = 1f,
    val sleepMinutes: Int = 30,
    val sleepFade: Boolean = true,
    val shakeToExtend: Boolean = true,
    val headsetChapters: Boolean = false,
    val theme: String = ThemeMode.SYSTEM.name,
    /** Nur noch zum Lesen von Dateien aus 1.2; seitdem zählt [palette]. */
    val dynamicColor: Boolean = false,
    val palette: String? = null,
    val pureBlack: Boolean = false,
    val grid: Boolean = true,
    val sort: String = SortOrder.RECENT.name,
    val hideFinished: Boolean = false,
    val webCovers: Boolean = true,
    /** Sprache der App, leer heißt wie das System; null: nicht in der Datei. */
    val language: String? = null,
) {
    /** Übernimmt die Werte, behält die Bibliotheksordner und begrenzt Unsinniges aus fremden Dateien. */
    fun applyTo(s: AppSettings): AppSettings = s.copy(
        skipBackSec = skipBackSec.coerceIn(1, 300),
        skipForwardSec = skipForwardSec.coerceIn(1, 300),
        resumeRewindSec = resumeRewindSec.coerceIn(0, 120),
        smartRewind = smartRewind,
        defaultSpeed = defaultSpeed.takeIf { it.isFinite() }?.coerceIn(0.5f, 3f) ?: s.defaultSpeed,
        sleepMinutes = sleepMinutes.coerceIn(1, 600),
        sleepFade = sleepFade,
        shakeToExtend = shakeToExtend,
        headsetChapters = headsetChapters,
        theme = ThemeMode.entries.firstOrNull { it.name == theme } ?: s.theme,
        palette = palette?.let { name -> ColorPalette.entries.firstOrNull { it.name == name } }
            ?: if (dynamicColor) ColorPalette.WALLPAPER else s.palette,
        pureBlack = pureBlack,
        grid = grid,
        sort = SortOrder.entries.firstOrNull { it.name == sort } ?: s.sort,
        hideFinished = hideFinished,
        webCovers = webCovers,
    )

    companion object {
        fun from(s: AppSettings, language: String) = SettingsBackup(
            skipBackSec = s.skipBackSec,
            skipForwardSec = s.skipForwardSec,
            resumeRewindSec = s.resumeRewindSec,
            smartRewind = s.smartRewind,
            defaultSpeed = s.defaultSpeed,
            sleepMinutes = s.sleepMinutes,
            sleepFade = s.sleepFade,
            shakeToExtend = s.shakeToExtend,
            headsetChapters = s.headsetChapters,
            theme = s.theme.name,
            dynamicColor = s.palette == ColorPalette.WALLPAPER,
            palette = s.palette.name,
            pureBlack = s.pureBlack,
            grid = s.grid,
            sort = s.sort.name,
            hideFinished = s.hideFinished,
            webCovers = s.webCovers,
            language = language,
        )
    }
}

/** Exportdatei. Version 1 (ältere Fassungen von Simple Audiobooks) kannte noch keine Einstellungen. */
@Serializable
data class BackupFile(
    val app: String = "SimpleAudiobooks",
    val version: Int = 2,
    val exportedAt: Long,
    val books: List<BackupBook>,
    val stats: StatsData? = null,
    val settings: SettingsBackup? = null,
)

/** Export und Import von Hörständen, Lesezeichen, Statistik und Einstellungen als JSON. */
object Backup {

    /** Lesbar formatiert und mit allen Feldern, damit eine Datei auch spätere Änderungen der Vorgaben übersteht. */
    private val json = Json(AppJson) {
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    /** Liest eine Exportdatei; null, wenn die Datei keine ist. */
    fun decode(text: String): BackupFile? = runCatching { json.decodeFromString(BackupFile.serializer(), text) }.getOrNull()

    /** Ergebnis der Zuordnung: neue Stände je Buch-ID und die Einträge ohne passendes Buch. */
    data class Match(val states: Map<String, BookState>, val unmatched: List<BackupBook>)

    /**
     * Ordnet gesicherte Stände vorhandenen Büchern zu: über die Kennung, sonst über den Pfad, sonst über Titel und Autor.
     * Titelbilder aus einer anderen Installation fehlen hier; dann bleiben die Bilder dieses Geräts.
     */
    fun match(books: Collection<Book>, items: List<BackupBook>, current: (String) -> BookState): Match {
        val byId = books.associateBy { it.id }
        val byPath = books.groupBy { it.path }
        val byTitle = books.groupBy { it.title.lowercase() }
        val result = HashMap<String, BookState>()
        val unmatched = ArrayList<BackupBook>()
        for (b in items) {
            val target = byId[b.id]
                ?: b.path?.let { p -> byPath[p]?.singleOrNull() }
                ?: byTitle[b.title.lowercase()]?.let { list ->
                    list.singleOrNull() ?: list.firstOrNull { it.author.equals(b.author, ignoreCase = true) }
                }
            if (target == null) {
                unmatched += b
                continue
            }
            val here = current(target.id)
            val cover = b.state.customCover?.takeIf { File(it).exists() }
            val web = b.state.webCover?.takeIf { File(it).exists() }
            result[target.id] = b.state.copy(
                pathHint = target.root + "\n" + target.path,
                customCover = cover ?: here.customCover,
                customColor = if (cover != null) b.state.customColor else here.customColor,
                webCover = web ?: here.webCover,
                webColor = if (web != null) b.state.webColor else here.webColor,
                webQuery = if (web != null) b.state.webQuery else here.webQuery,
                webTriedAt = if (web != null) b.state.webTriedAt else here.webTriedAt,
                webBlocked = b.state.webBlocked || here.webBlocked,
            )
        }
        return Match(result, unmatched)
    }

    /** Tageswerte zusammenführen; derselbe Tag zählt einmal mit dem größeren Wert. */
    fun mergeStats(current: StatsData, imported: StatsData): StatsData {
        val merged = current.days.toMutableMap()
        for ((k, v) in imported.days) merged[k] = maxOf(merged[k] ?: 0L, v)
        return StatsData(merged)
    }
}
