package app.simplenarrator.testutil.sa

/**
 * Kopie aus Simple Audiobooks (data/Models.kt), nur für Tests: ein in eine Datei eingebettetes Kapitel,
 * Startzeit relativ zum Dateianfang. Ohne @Serializable, weil die Tests das nicht brauchen.
 */
data class Mark(val title: String, val startMs: Long)
