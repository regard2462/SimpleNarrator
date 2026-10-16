package app.simplenarrator.audiobooks.scan

/** Datei im eingelesenen Ordnerbaum. */
data class FsFile(val id: String, val name: String, val size: Long = 0, val modified: Long = 0)

/** Ordner im eingelesenen Ordnerbaum. */
data class FsDir(
    val id: String,
    val name: String,
    val files: List<FsFile> = emptyList(),
    val dirs: List<FsDir> = emptyList(),
)

/** Eine Audiodatei mit ihrem Pfad relativ zum Buch. */
data class AudioRef(val path: String, val file: FsFile)

/** Ein gefundenes Hörbuch, bevor die Dateien untersucht werden. */
data class Candidate(
    /** Pfad relativ zum Bibliotheksordner ("" = der Bibliotheksordner selbst). */
    val path: String,
    /** Name des Ordners oder der Datei. */
    val name: String,
    val audio: List<AudioRef>,
    val images: List<FsFile>,
    val cues: List<FsFile>,
    /** Name des übergeordneten Ordners, wenn das Buch in einem Autoren- oder Reihenordner liegt. */
    val parentName: String?,
    val singleFile: Boolean,
)

/**
 * Entscheidet, welche Dateien zusammen ein Hörbuch bilden.
 *
 * - Ein Ordner mit Audiodateien ist ein Buch. Unterordner gehören dazu (etwa "CD 1", "Bonus").
 * - Liegen in einem Ordner nur mehrere M4B-Dateien, ist jede davon ein eigenes Buch.
 * - Ein Ordner ohne eigene Audiodateien, dessen Unterordner alle wie "CD 1", "CD 2" heißen, ist ein Buch.
 * - Andere Ordner ohne eigene Audiodateien sind Autoren- oder Reihenordner; ihre Unterordner werden einzeln betrachtet.
 * - Lose Audiodateien direkt im Bibliotheksordner sind je ein Buch.
 */
object Grouping {

    fun group(root: FsDir, rootIsBook: Boolean): List<Candidate> {
        val out = ArrayList<Candidate>()
        if (rootIsBook) {
            if (containsAudio(root)) out += folderBook(root, "", parentName = null)
            return out
        }
        for (f in root.files.filter { Names.isAudio(it.name) }) {
            out += singleFileBook(f, dirPath = "", dir = root, parentName = null)
        }
        for (d in root.dirs) visit(d, d.name, parentName = null, out)
        return out
    }

    private fun visit(dir: FsDir, path: String, parentName: String?, out: MutableList<Candidate>) {
        if (!containsAudio(dir)) return
        val audio = dir.files.filter { Names.isAudio(it.name) }
        if (audio.isNotEmpty()) {
            if (audio.size > 1 && audio.all { Names.isSingleFileBookFormat(it.name) }) {
                for (f in audio) out += singleFileBook(f, path, dir, dir.name)
                for (d in dir.dirs) visit(d, "$path/${d.name}", dir.name, out)
            } else {
                out += folderBook(dir, path, parentName)
            }
            return
        }
        val withAudio = dir.dirs.filter { containsAudio(it) }
        if (withAudio.size >= 2 && withAudio.all { Names.isDiscFolder(it.name) }) {
            out += folderBook(dir, path, parentName)
            return
        }
        for (d in dir.dirs) visit(d, "$path/${d.name}", dir.name, out)
    }

    private fun folderBook(dir: FsDir, path: String, parentName: String?): Candidate {
        val audio = ArrayList<AudioRef>()
        val images = ArrayList<FsFile>()
        val cues = ArrayList<FsFile>()
        collect(dir, "", audio, images, cues, depth = 0)
        audio.sortWith { a, b -> NaturalOrder.compare(a.path, b.path) }
        return Candidate(path, dir.name, audio, images, cues, parentName, singleFile = false)
    }

    private fun singleFileBook(file: FsFile, dirPath: String, dir: FsDir, parentName: String?): Candidate {
        val base = file.name.substringBeforeLast('.')
        // Bilder und Cue-Dateien nur zuordnen, wenn sie eindeutig zu dieser Datei gehören.
        val images = dir.files.filter { Names.isImage(it.name) && it.name.substringBeforeLast('.').equals(base, true) }
        val cues = dir.files.filter { Names.isCue(it.name) && it.name.substringBeforeLast('.').equals(base, true) }
        val path = if (dirPath.isEmpty()) file.name else "$dirPath/${file.name}"
        return Candidate(path, file.name, listOf(AudioRef(file.name, file)), images, cues, parentName, singleFile = true)
    }

    private fun collect(
        dir: FsDir,
        prefix: String,
        audio: MutableList<AudioRef>,
        images: MutableList<FsFile>,
        cues: MutableList<FsFile>,
        depth: Int,
    ) {
        for (f in dir.files) {
            val rel = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
            when {
                Names.isAudio(f.name) -> audio += AudioRef(rel, f)
                Names.isImage(f.name) && depth <= 1 -> images += f
                Names.isCue(f.name) && depth == 0 -> cues += f
            }
        }
        if (depth >= 6) return
        for (d in dir.dirs) collect(d, if (prefix.isEmpty()) d.name else "$prefix/${d.name}", audio, images, cues, depth + 1)
    }

    private fun containsAudio(dir: FsDir): Boolean =
        dir.files.any { Names.isAudio(it.name) } || dir.dirs.any { containsAudio(it) }

    /** Bevorzugtes Titelbild unter den Bilddateien eines Buches. */
    fun pickCoverImage(images: List<FsFile>): FsFile? {
        if (images.isEmpty()) return null
        val preferred = listOf("cover", "folder", "front", "albumart", "album", "poster")
        for (p in preferred) {
            images.firstOrNull { it.name.substringBeforeLast('.').lowercase().startsWith(p) }?.let { return it }
        }
        // Rückseiten nur nehmen, wenn es nichts anderes gibt
        return images.filterNot { it.name.lowercase().startsWith("back") }.maxByOrNull { it.size }
            ?: images.maxByOrNull { it.size }
    }
}
