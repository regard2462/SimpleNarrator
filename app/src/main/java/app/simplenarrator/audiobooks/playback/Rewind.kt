package app.simplenarrator.audiobooks.playback

/** Wie weit beim Fortsetzen nach einer Pause zurückgesprungen wird. */
object Rewind {

    /**
     * @param pauseMs Dauer der Pause
     * @param baseSec eingestellte Sekunden (0 = aus)
     * @param smart wenn an, wächst der Rücksprung mit der Pausendauer
     */
    fun resumeRewindMs(pauseMs: Long, baseSec: Int, smart: Boolean): Long {
        if (baseSec <= 0 || pauseMs < 3_000) return 0
        val base = baseSec * 1000L
        if (!smart) return base
        val ms = when {
            pauseMs < 60_000 -> base / 2
            pauseMs < 10 * 60_000 -> base
            pauseMs < 60 * 60_000 -> base * 2
            else -> base * 3
        }
        return ms.coerceIn(1_000, 60_000)
    }
}
