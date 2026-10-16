package app.simplenarrator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.simplenarrator.R
import app.simplenarrator.input.OcrImportWorker

/** Laufende Texterkennungen über der Bibliothek, mit Fortschritt und Abbrechen. */
@Composable
fun OcrBanner() {
    val ctx = LocalContext.current
    val infos by WorkManager.getInstance(ctx).getWorkInfosByTagFlow(OcrImportWorker.TAG).collectAsState(initial = emptyList())
    infos.filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }.forEach { info ->
        val name = info.progress.getString(OcrImportWorker.NAME).orEmpty()
        val page = info.progress.getInt(OcrImportWorker.PAGE, 0)
        val pages = info.progress.getInt(OcrImportWorker.PAGES, 0)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer).padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ocr_running, name), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        if (pages > 0) stringResource(R.string.ocr_page, page, pages) else stringResource(R.string.ocr_preparing),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                TextButton(onClick = { WorkManager.getInstance(ctx).cancelWorkById(info.id) }) { Text(stringResource(R.string.cancel)) }
            }
            Spacer(Modifier.height(6.dp))
            if (pages > 0) LinearProgressIndicator(progress = { page.toFloat() / pages }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}
