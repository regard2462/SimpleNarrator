package app.simplenarrator.audiobooks.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Player-Hülle mit Hörbuch-Verhalten: Spulen rechnet über Dateigrenzen hinweg,
 * die Kopfhörertasten "weiter"/"zurück" spulen statt Dateien zu wechseln,
 * und beim Fortsetzen nach einer Pause wird ein Stück zurückgesprungen.
 */
@UnstableApi
class AudiobookPlayer(player: Player, private val host: Host) : ForwardingPlayer(player) {

    interface Host {
        val skipBackMs: Long
        val skipForwardMs: Long
        fun skipBy(deltaMs: Long)
        fun headsetNext()
        fun headsetPrevious()
        fun beforeResume()

        /** Lädt das zuletzt gehörte Buch, falls nichts geladen ist. Liefert false, wenn es keines gibt. */
        fun ensureLoaded(): Boolean

        /**
         * Übernimmt den Start selbst, wenn der Dienst erst in den Vordergrund muss (App nicht sichtbar,
         * Dienst nicht im Vordergrund). Liefert true, wenn der Start dadurch erledigt ist.
         */
        fun deferPlay(): Boolean

        /** Merkt sich den Zeitpunkt eines Startwunsches, um einen verweigerten Audiofokus zu erkennen. */
        fun notePlayRequest()
    }

    override fun seekBack() = host.skipBy(-host.skipBackMs)
    override fun seekForward() = host.skipBy(host.skipForwardMs)
    override fun getSeekBackIncrement(): Long = host.skipBackMs
    override fun getSeekForwardIncrement(): Long = host.skipForwardMs

    override fun seekToNext() = host.headsetNext()
    override fun seekToPrevious() = host.headsetPrevious()

    override fun play() {
        host.notePlayRequest()
        if (!host.ensureLoaded()) return
        if (!playWhenReady && host.deferPlay()) return
        if (!playWhenReady) host.beforeResume()
        super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) {
            host.notePlayRequest()
            if (!host.ensureLoaded()) return
            if (!this.playWhenReady && host.deferPlay()) return
            if (!this.playWhenReady) host.beforeResume()
        }
        super.setPlayWhenReady(playWhenReady)
    }
}
