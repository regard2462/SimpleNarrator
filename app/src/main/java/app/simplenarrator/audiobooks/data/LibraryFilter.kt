package app.simplenarrator.audiobooks.data

import app.simplenarrator.audiobooks.scan.NaturalOrder

/** Filter der Bibliothek. */
enum class Filter { ALL, STARTED, NEW, FINISHED }

/** Passt ein Buch zum Filter; [hideFinished] blendet gehörte Bücher in „Alle“ aus. */
fun Entry.matches(f: Filter, hideFinished: Boolean): Boolean = when (f) {
    Filter.ALL -> !(hideFinished && status == Status.FINISHED)
    Filter.STARTED -> status == Status.STARTED
    Filter.NEW -> status == Status.NEW
    Filter.FINISHED -> status == Status.FINISHED
}

/** Alle Wörter der Suche kommen in Titel, Autor, Sprecher oder Pfad vor. */
fun Entry.matchesQuery(q: String): Boolean {
    if (q.isBlank()) return true
    val words = q.lowercase().split(' ').filter { it.isNotBlank() }
    val hay = listOfNotNull(title, author, narrator, book.path).joinToString(" ").lowercase()
    return words.all { hay.contains(it) }
}

fun sortEntries(list: List<Entry>, order: SortOrder): List<Entry> = when (order) {
    SortOrder.RECENT -> list.sortedWith(
        compareByDescending<Entry> { it.state.lastPlayedAt }.thenComparator { a, b -> NaturalOrder.compare(a.title, b.title) },
    )
    SortOrder.TITLE -> list.sortedWith { a, b -> NaturalOrder.compare(a.title, b.title) }
    SortOrder.AUTHOR -> list.sortedWith(
        compareBy<Entry> { it.author == null }
            .thenComparator { a, b -> NaturalOrder.compare(a.author.orEmpty(), b.author.orEmpty()) }
            .thenComparator { a, b -> NaturalOrder.compare(a.title, b.title) },
    )
    SortOrder.ADDED -> list.sortedByDescending { it.book.addedAt }
    SortOrder.LENGTH -> list.sortedByDescending { it.durationMs }
    SortOrder.PROGRESS -> list.sortedWith(compareByDescending<Entry> { if (it.status == Status.FINISHED) -1f else it.progress })
}
