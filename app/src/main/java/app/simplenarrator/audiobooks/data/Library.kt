package app.simplenarrator.audiobooks.data

import app.simplenarrator.audiobooks.scan.BookBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** Fortschritt beim Einlesen: [done] von [total] Büchern im Ordner [rootIndex] von [rootCount]. */
data class ScanState(val rootIndex: Int, val rootCount: Int, val done: Int, val total: Int)

/**
 * Bücher, Hörstände und Einlesen. Einzige Quelle der Wahrheit für Oberfläche und Wiedergabe. Daten liegen als JSON
 * in [dir]; Ordnerzugriff, Löschen und Titelbilder liefert die [platform].
 */
class Library(
    dir: File,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val platform: LibraryPlatform,
) {
    private val booksFile = JsonFile(File(dir, "books.json"), ListSerializer(Book.serializer()))
    private val statesFile = JsonFile(
        File(dir, "states.json"),
        MapSerializer(String.serializer(), BookState.serializer()),
    )
    /** Einträge eines Imports, deren Bücher noch nicht eingelesen sind; sie werden nach dem Einlesen zugeordnet. */
    private val pendingFile = JsonFile(File(dir, "pending-import.json"), ListSerializer(BackupBook.serializer()))
    private val pendingLock = Any()
    private var pending: List<BackupBook> = emptyList()
    val covers: CoverStore get() = platform.covers
    private val scanner get() = platform.scanner

    private val _books = MutableStateFlow<Map<String, Book>>(emptyMap())
    private val _states = MutableStateFlow<Map<String, BookState>>(emptyMap())
    private val _ready = MutableStateFlow(false)
    private val _scan = MutableStateFlow<ScanState?>(null)
    private val _unreachable = MutableStateFlow<Set<String>>(emptySet())

    val books: StateFlow<Map<String, Book>> = _books.asStateFlow()
    val states: StateFlow<Map<String, BookState>> = _states.asStateFlow()
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    val scan: StateFlow<ScanState?> = _scan.asStateFlow()
    val unreachableRoots: StateFlow<Set<String>> = _unreachable.asStateFlow()

    private val webCovers get() = settings.value.webCovers

    val entries: StateFlow<List<Entry>> = combine(_books, _states, settings.flow.map { it.webCovers }.distinctUntilChanged()) { b, s, web ->
        b.values.filter { s[it.id]?.hidden != true }.map { Entry(it, s[it.id] ?: BookState(), web) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Aus der Bibliothek entfernte Bücher, deren Dateien noch vorhanden sind. */
    val hiddenEntries: StateFlow<List<Entry>> = combine(_books, _states, settings.flow.map { it.webCovers }.distinctUntilChanged()) { b, s, web ->
        b.values.filter { s[it.id]?.hidden == true }.map { Entry(it, s[it.id]!!, web) }.sortedBy { it.title.lowercase() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val scanMutex = Mutex()
    private var scanJob: Job? = null
    private var saveStatesJob: Job? = null

    init {
        // Synchron laden: Ein über eine Kopfhörertaste gestarteter Dienst braucht den letzten Stand sofort.
        booksFile.read()?.let { list -> _books.value = list.associateBy { it.id } }
        statesFile.read()?.let { _states.value = it }
        pending = pendingFile.read().orEmpty()
        _ready.value = true
        rescan()
    }

    fun book(id: String?): Book? = id?.let { _books.value[it] }
    fun state(id: String?): BookState = id?.let { _states.value[it] } ?: BookState()
    fun entry(id: String?): Entry? = book(id)?.let { Entry(it, state(it.id), webCovers) }

    /** Das zuletzt gehörte, noch nicht beendete Buch. */
    fun lastPlayed(): Entry? {
        val states = _states.value
        return _books.value.values
            .filter { (states[it.id]?.lastPlayedAt ?: 0) > 0 && states[it.id]?.hidden != true }
            .maxByOrNull { states[it.id]!!.lastPlayedAt }
            ?.let { Entry(it, states[it.id]!!, webCovers) }
    }

    /** Aktuelle Einträge ohne Umweg über den Flow, auch direkt nach dem Start verlässlich. */
    fun currentEntries(): List<Entry> {
        val states = _states.value
        return _books.value.values.filter { states[it.id]?.hidden != true }.map { Entry(it, states[it.id] ?: BookState(), webCovers) }
    }

    /** Bestes Buch zu einem Suchtext (Titel, Autor, Sprecher); leerer Text liefert null. */
    fun search(query: String): Entry? {
        val q = query.lowercase().trim()
        val words = q.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        return currentEntries()
            .map { e ->
                val title = e.title.lowercase()
                val hay = listOfNotNull(e.title, e.author, e.narrator).joinToString(" ").lowercase()
                val hits = words.count { hay.contains(it) }
                val score = hits * 10 + (if (title == q) 50 else 0) + (if (title.contains(q)) 20 else 0)
                e to score
            }
            .filter { it.second > 0 }
            .maxWithOrNull(compareBy<Pair<Entry, Int>> { it.second }.thenBy { it.first.state.lastPlayedAt })
            ?.first
    }

    // ------------------------------------------------------------------ Einlesen

    fun rescan(): Job {
        scanJob?.takeIf { it.isActive }?.let { return it }
        return scope.launch(Dispatchers.IO) {
            scanMutex.withLock { scanAll() }
        }.also { scanJob = it }
    }

    private suspend fun scanAll() {
        val roots = settings.value.roots
        val result = HashMap<String, Book>()
        val unreachable = HashSet<String>()
        val old = _books.value
        try {
            roots.forEachIndexed { i, root ->
                _scan.value = ScanState(i, roots.size, 0, 0)
                val found = scanner.scanRoot(root, old) { p -> _scan.value = ScanState(i, roots.size, p.done, p.total) }
                if (found == null) {
                    unreachable += root.uri
                    old.values.filter { it.root == root.uri }.forEach { result[it.id] = it }
                } else {
                    found.forEach { result[it.id] = it }
                }
            }
        } finally {
            _scan.value = null
        }
        // Ordner, die während des Einlesens entfernt wurden, nicht wieder aufnehmen
        val currentRoots = settings.value.roots.map { it.uri }.toSet()
        val finalBooks = result.filterValues { it.root in currentRoots }
        _books.value = finalBooks
        _unreachable.value = unreachable
        _states.update { states ->
            val m = states.toMutableMap()
            for (b in finalBooks.values) {
                val s = m[b.id] ?: BookState()
                val hint = b.root + "\n" + b.path
                if (s.pathHint != hint) m[b.id] = s.copy(pathHint = hint)
            }
            m
        }
        applyPending()
        withContext(Dispatchers.IO) {
            booksFile.write(finalBooks.values.toList())
            statesFile.write(_states.value)
            val keep = HashSet<String>()
            finalBooks.values.forEach { b -> b.cover?.let(keep::add) }
            _states.value.values.forEach { s ->
                s.customCover?.let(keep::add)
                s.webCover?.let(keep::add)
            }
            covers.prune(keep)
        }
    }

    /** Nimmt einen Bibliotheksordner auf; [uri] ist je Plattform eine Ordner-URI oder ein Pfad. */
    fun addRoot(uri: String, single: Boolean) {
        settings.update { st ->
            if (st.roots.any { it.uri == uri }) st.copy(roots = st.roots.map { if (it.uri == uri) it.copy(single = single) else it })
            else st.copy(roots = st.roots + LibraryRoot(uri, single))
        }
        scope.launch(Dispatchers.IO) {
            scanJob?.join()
            rescan()
        }
    }

    fun removeRoot(uri: String) {
        settings.update { st -> st.copy(roots = st.roots.filterNot { it.uri == uri }) }
        _books.update { m -> m.filterValues { it.root != uri } }
        runCatching { platform.releaseRoot(uri) }
        scope.launch(Dispatchers.IO) { booksFile.write(_books.value.values.toList()) }
    }

    // ------------------------------------------------------------------ Hörstand

    fun updateState(id: String, change: (BookState) -> BookState) {
        _states.update { m -> m + (id to change(m[id] ?: BookState())) }
        scheduleSave()
    }

    /** Merkt sich die Position nur im Speicher; geschrieben wird mit [flush] oder der nächsten Änderung. */
    fun savePosition(id: String, positionMs: Long, listenedDeltaMs: Long = 0) {
        _states.update { m ->
            val it = m[id] ?: BookState()
            m + (id to it.copy(
                positionMs = positionMs,
                lastPlayedAt = System.currentTimeMillis(),
                listenedMs = it.listenedMs + listenedDeltaMs,
            ))
        }
    }

    fun markFinished(id: String, finished: Boolean) {
        val duration = book(id)?.durationMs ?: 0
        updateState(id) {
            if (finished) it.copy(finished = true, finishedAt = System.currentTimeMillis(), positionMs = duration)
            else it.copy(finished = false, finishedAt = 0, positionMs = if (it.positionMs >= duration) 0 else it.positionMs)
        }
    }

    /** Nimmt ein Buch aus der Bibliothek, ohne Dateien anzufassen. */
    fun hideBook(id: String) = updateState(id) { it.copy(hidden = true) }

    fun restoreBook(id: String) = updateState(id) { it.copy(hidden = false) }

    /**
     * Löscht die Dateien eines Buches vom Gerät und nimmt es aus der Bibliothek.
     * Liefert false, wenn der Speicher das Löschen verweigert (etwa ohne Schreibrecht für den Ordner).
     */
    suspend fun deleteBookFiles(id: String): Boolean = withContext(Dispatchers.IO) {
        val book = book(id) ?: return@withContext false
        val deleted = runCatching { platform.deleteBookFiles(book) }.getOrDefault(false)
        if (!deleted) return@withContext false
        _books.update { it - id }
        _states.update { it - id }
        booksFile.write(_books.value.values.toList())
        statesFile.write(_states.value)
        true
    }

    fun resetProgress(id: String) {
        updateState(id) { it.copy(positionMs = 0, finished = false, finishedAt = 0, lastPlayedAt = 0) }
    }

    fun setSpeed(id: String, speed: Float) = updateState(id) { it.copy(speed = speed) }
    fun setSkipSilence(id: String, on: Boolean) = updateState(id) { it.copy(skipSilence = on) }
    fun setBoost(id: String, db: Int) = updateState(id) { it.copy(boostDb = db) }

    fun addBookmark(id: String, positionMs: Long, note: String = ""): Bookmark {
        // Ein zweites Lesezeichen an derselben Stelle ergibt keinen Sinn
        state(id).bookmarks.firstOrNull { kotlin.math.abs(it.positionMs - positionMs) < 1500 }?.let { return it }
        val bm = Bookmark(System.currentTimeMillis(), positionMs, note.trim(), System.currentTimeMillis())
        updateState(id) { it.copy(bookmarks = (it.bookmarks + bm).sortedBy { b -> b.positionMs }) }
        return bm
    }

    fun editBookmark(id: String, bookmarkId: Long, note: String) = updateState(id) {
        it.copy(bookmarks = it.bookmarks.map { b -> if (b.id == bookmarkId) b.copy(note = note.trim()) else b })
    }

    fun deleteBookmark(id: String, bookmarkId: Long) = updateState(id) {
        it.copy(bookmarks = it.bookmarks.filterNot { b -> b.id == bookmarkId })
    }

    fun editMeta(id: String, title: String, author: String, narrator: String) {
        val b = book(id) ?: return
        updateState(id) {
            it.copy(
                title = title.trim().takeIf { t -> t.isNotEmpty() && t != b.title },
                author = author.trim().takeIf { t -> t != (b.author ?: "") },
                narrator = narrator.trim().takeIf { t -> t != (b.narrator ?: "") },
            )
        }
    }

    /** Übernimmt ein Bild (aus einer Datei oder online gewählt) als eigenes Titelbild. */
    suspend fun setCustomCoverBytes(id: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val saved = covers.saveBytes("$id-c${System.currentTimeMillis()}", bytes) ?: return@withContext false
        val previous = state(id).customCover
        updateState(id) { it.copy(customCover = saved.path, customColor = saved.color) }
        previous?.let { File(it).delete() }
        true
    }

    /** Ergebnis einer automatischen Online-Suche; [saved] null heißt: nichts Passendes gefunden. */
    fun setWebCover(id: String, saved: SavedCover?, query: String, at: Long) {
        if (book(id) == null) {
            saved?.let { File(it.path).delete() }
            return
        }
        val previous = state(id).webCover
        updateState(id) { it.copy(webCover = saved?.path, webColor = saved?.color, webQuery = query, webTriedAt = at) }
        if (previous != null && previous != saved?.path) File(previous).delete()
    }

    /** Entfernt das Online-Titelbild eines Buches und sucht dafür nicht mehr automatisch. */
    fun dropWebCover(id: String) {
        val previous = state(id).webCover
        updateState(id) { it.copy(webCover = null, webColor = null, webBlocked = true) }
        previous?.let { scope.launch(Dispatchers.IO) { File(it).delete() } }
    }

    /** Zahl der gespeicherten Online-Titelbilder. */
    fun webCoverCount(): Int = _states.value.values.count { it.webCover != null }

    /** Löscht alle Online-Titelbilder; ist die Einstellung wieder an, wird neu gesucht. */
    fun clearWebCovers() {
        val files = _states.value.values.mapNotNull { it.webCover }
        _states.update { m ->
            m.mapValues { (_, s) ->
                if (s.webCover == null && s.webQuery == null) s
                else s.copy(webCover = null, webColor = null, webQuery = null, webTriedAt = 0)
            }
        }
        scheduleSave()
        scope.launch(Dispatchers.IO) { files.forEach { File(it).delete() } }
    }

    fun clearCustomCover(id: String) {
        val previous = state(id).customCover
        updateState(id) { it.copy(customCover = null, customColor = null) }
        previous?.let { scope.launch(Dispatchers.IO) { File(it).delete() } }
    }

    /** Übernimmt die tatsächliche Länge einer Datei, wenn der Player sie genauer kennt als die Tags. */
    fun correctDuration(id: String, trackIndex: Int, actualMs: Long) {
        val b = book(id) ?: return
        val t = b.tracks.getOrNull(trackIndex) ?: return
        if (actualMs <= 0 || kotlin.math.abs(t.durationMs - actualMs) < 1500) return
        val tracks = b.tracks.toMutableList()
        tracks[trackIndex] = t.copy(durationMs = actualMs)
        val nb = b.copy(tracks = tracks, chapters = BookBuilder.chapters(tracks))
        _books.update { it + (id to nb) }
        scope.launch(Dispatchers.IO) { booksFile.write(_books.value.values.toList()) }
    }

    /** Schreibt die Hörstände in Kürze, mehrere Aufrufe kurz hintereinander werden zusammengefasst. */
    fun saveSoon() = scheduleSave()

    private fun scheduleSave() {
        saveStatesJob?.cancel()
        saveStatesJob = scope.launch(Dispatchers.IO) {
            delay(800)
            statesFile.write(_states.value)
        }
    }

    /** Schreibt ausstehende Hörstände sofort. */
    fun flush() {
        saveStatesJob?.cancel()
        runCatching { statesFile.write(_states.value) }
    }

    /** Ersetzt Hörstände aus einer Sicherung. */
    fun importStates(imported: Map<String, BookState>) {
        _states.update { it + imported }
        scope.launch(Dispatchers.IO) { statesFile.write(_states.value) }
    }

    // ------------------------------------------------------------------ Export und Import

    /** Alle Bücher mit Stand für eine Exportdatei, auch entfernte und die noch wartenden eines früheren Imports. */
    fun exportBooks(): List<BackupBook> {
        val states = _states.value
        val known = _books.value.values.map { b -> BackupBook(b.id, b.path, b.title, b.author, states[b.id] ?: BookState()) }
        val ids = known.mapTo(HashSet()) { it.id }
        val waiting = synchronized(pendingLock) { pending }.filter { it.id !in ids }
        return known + waiting
    }

    /**
     * Übernimmt Stände aus einer Exportdatei. Was noch keinem Buch zugeordnet werden kann, etwa weil der Ordner
     * erst noch gewählt wird, wartet auf das nächste Einlesen. Liefert (übernommen, wartend).
     */
    fun importBooks(items: List<BackupBook>): Pair<Int, Int> {
        val m = Backup.match(_books.value.values, items, ::state)
        if (m.states.isNotEmpty()) importStates(m.states)
        synchronized(pendingLock) { pending = m.unmatched }
        pendingFile.write(m.unmatched)
        return m.states.size to m.unmatched.size
    }

    /** Ordnet wartende Einträge eines Imports den gerade eingelesenen Büchern zu. */
    private fun applyPending() {
        val waiting = synchronized(pendingLock) { pending }
        if (waiting.isEmpty()) return
        val m = Backup.match(_books.value.values, waiting, ::state)
        if (m.states.isEmpty()) return
        _states.update { it + m.states }
        synchronized(pendingLock) { pending = m.unmatched }
        pendingFile.write(m.unmatched)
    }
}
