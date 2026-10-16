package app.simplenarrator.audiobooks.scan

/**
 * Vergleicht Zeichenketten so, wie Menschen nummerierte Dateien lesen:
 * "Teil 2" kommt vor "Teil 10", "CD1/01" vor "CD2/01". Groß- und Kleinschreibung zählt nicht.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                // Gleicher Zahlenwert: weniger führende Nullen zuerst, damit die Ordnung stabil bleibt.
                val lenDiff = (i - si) - (j - sj)
                if (lenDiff != 0) return lenDiff
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) {
                    // Trennzeichen vor Buchstaben, damit "a/b" vor "a b" und "a-b" sortiert.
                    val pa = rank(la)
                    val pb = rank(lb)
                    if (pa != pb) return pa - pb
                    return la.compareTo(lb)
                }
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    private fun rank(c: Char): Int = when (c) {
        '/' -> 0
        ' ', '_', '-', '.' -> 1
        else -> 2
    }
}
