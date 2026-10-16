package app.simplenarrator.audiobooks.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Schlaftimer. Der Wiedergabedienst prüft ihn regelmäßig und hält die Wiedergabe an. */
object SleepTimer {

    /** Monotone Uhr in Millisekunden; Android setzt elapsedRealtime, damit auch Tiefschlaf zählt. */
    var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    sealed interface State

    /** Läuft für [durationMs]; endet zur Zeit [endAt] (nach [clock]). */
    data class Countdown(val durationMs: Long, val endAt: Long) : State {
        fun remaining(now: Long = clock()): Long = (endAt - now).coerceAtLeast(0)
    }

    /** Hält am Ende des Kapitels an, das bei [chapterEndMs] (Gesamtposition) endet. */
    data class ChapterEnd(val bookId: String, val chapterIndex: Int, val chapterEndMs: Long) : State

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    fun startMinutes(minutes: Int) {
        val d = minutes * 60_000L
        _state.value = Countdown(d, clock() + d)
    }

    fun startChapterEnd(bookId: String, chapterIndex: Int, chapterEndMs: Long) {
        _state.value = ChapterEnd(bookId, chapterIndex, chapterEndMs)
    }

    /** Setzt einen laufenden Countdown auf seine volle Länge zurück. */
    fun restart(): Boolean {
        val s = _state.value as? Countdown ?: return false
        _state.value = s.copy(endAt = clock() + s.durationMs)
        return true
    }

    fun extend(ms: Long) {
        val s = _state.value as? Countdown ?: return
        _state.value = Countdown(s.durationMs + ms, s.endAt + ms)
    }

    fun cancel() {
        _state.value = null
    }
}
