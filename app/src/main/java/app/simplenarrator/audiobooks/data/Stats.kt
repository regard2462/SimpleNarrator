package app.simplenarrator.audiobooks.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate

/** Hörzeit je Kalendertag (yyyy-MM-dd) in Millisekunden echter Zeit. */
@Serializable
data class StatsData(val days: Map<String, Long> = emptyMap())

class Stats(dir: File, private val scope: CoroutineScope) {
    private val file = JsonFile(File(dir, "stats.json"), StatsData.serializer())
    private val _data = MutableStateFlow(StatsData())
    val data: StateFlow<StatsData> = _data.asStateFlow()
    private var saveJob: Job? = null

    init {
        scope.launch(Dispatchers.IO) { file.read()?.let { loaded -> _data.update { merge(loaded, it) } } }
    }

    fun add(ms: Long, day: LocalDate = LocalDate.now()) {
        if (ms <= 0) return
        val key = day.toString()
        _data.update { it.copy(days = it.days + (key to ((it.days[key] ?: 0L) + ms))) }
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(5_000)
            file.write(_data.value)
        }
    }

    fun flush() {
        saveJob?.cancel()
        runCatching { file.write(_data.value) }
    }

    fun replace(data: StatsData) {
        _data.value = data
        scope.launch(Dispatchers.IO) { file.write(data) }
    }

    private fun merge(a: StatsData, b: StatsData): StatsData {
        val m = a.days.toMutableMap()
        for ((k, v) in b.days) m[k] = (m[k] ?: 0L) + v
        return StatsData(m)
    }
}
