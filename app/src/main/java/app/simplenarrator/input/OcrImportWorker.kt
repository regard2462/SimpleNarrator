package app.simplenarrator.input

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.simplenarrator.MainActivity
import app.simplenarrator.R
import app.simplenarrator.SimpleNarratorApp
import app.simplenarrator.parser.PdfParser
import app.simplenarrator.util.AppLanguage

/**
 * Texterkennung für ein zwischengelagertes PDF im Hintergrund: fehlende Sprachdaten laden, gescannte Seiten
 * erkennen, danach das Buch anlegen. Läuft als Vordergrund-Worker mit Fortschritt in der Benachrichtigung.
 */
class OcrImportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val TAG = "ocr-import"
        const val KEY_STAGING = "staging"
        const val NAME = "name"
        const val PROGRESS = "progress"
        const val PAGE = "page"
        const val PAGES = "pages"
        const val BOOK = "book"
        const val ERROR = "error"
        const val CHANNEL = "ocr"
        private const val NOTIF_ID = 4712

        fun enqueue(context: Context, stagingId: String, name: String) {
            val req = OneTimeWorkRequestBuilder<OcrImportWorker>()
                .setInputData(workDataOf(KEY_STAGING to stagingId, NAME to name))
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("ocr-$stagingId", ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, stagingId: String) = WorkManager.getInstance(context).cancelUniqueWork("ocr-$stagingId")
    }

    private val res by lazy { AppLanguage.wrap(applicationContext) }

    override suspend fun doWork(): Result {
        val container = (applicationContext as SimpleNarratorApp).container
        val staging = inputData.getString(KEY_STAGING) ?: return Result.failure()
        val name = inputData.getString(NAME).orEmpty()
        val repo = container.repo
        val ocr = container.ocr
        return try {
            setForeground(foreground(name, res.getString(R.string.ocr_preparing), 0, 0))
            // Sprachdaten: was fehlt, wird geladen (Deutsch und Englisch zusammen gut 5 MB)
            if (ocr.installed.value.isEmpty()) {
                Ocr.Language.entries.forEach { lang ->
                    setForeground(foreground(name, res.getString(R.string.ocr_downloading, lang.code), 0, 0))
                    ocr.download(lang)
                }
            }
            val pdf = repo.stagedPdf(staging)
            val pages = PdfParser.scannedPages(PdfParser.pageTexts(applicationContext, pdf))
            val texts = ocr.recognize(pdf, pages) { done, total ->
                setProgress(workDataOf(NAME to name, PAGE to done, PAGES to total, PROGRESS to done * 100 / total.coerceAtLeast(1)))
                setForeground(foreground(name, res.getString(R.string.ocr_page, done, total), done, total))
            }
            val book = repo.finishPdfImport(staging, texts)
            notifyDone(res.getString(R.string.ocr_done, book.title))
            Result.success(workDataOf(BOOK to book.id, NAME to name))
        } catch (e: Exception) {
            if (isStopped) {
                repo.discardStaged(staging)
                return Result.failure(workDataOf(NAME to name))
            }
            Log.e("OcrImportWorker", "Texterkennung fehlgeschlagen", e)
            repo.discardStaged(staging)
            notifyDone(res.getString(R.string.ocr_failed, name))
            Result.failure(workDataOf(NAME to name, ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        applicationContext, 3, Intent(applicationContext, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_READER, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun foreground(name: String, text: String, done: Int, total: Int): ForegroundInfo {
        val n: Notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_app)
            .setContentTitle(res.getString(R.string.ocr_title, name))
            .setContentText(text)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    private fun notifyDone(text: String) {
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_app)
            .setContentTitle(res.getString(R.string.ocr_channel))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n) }
    }
}
