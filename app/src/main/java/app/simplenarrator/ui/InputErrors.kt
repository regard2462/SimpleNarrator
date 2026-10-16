package app.simplenarrator.ui

import android.content.Context
import app.simplenarrator.R
import app.simplenarrator.export.AbsException
import app.simplenarrator.input.GutenbergException
import app.simplenarrator.input.WebArticleException

/**
 * Fehler aus Webartikel, Gutenberg und Audiobookshelf als Text in der App-Sprache.
 * Für Snackbars beim Import (z. B. R.string.import_failed mit diesem Text) und für die eigenen Dialoge.
 */
fun inputErrorText(context: Context, e: Throwable): String {
    val r = context.resources
    fun other() = r.getString(R.string.in_err_other, e.message ?: e.javaClass.simpleName)
    return when (e) {
        is WebArticleException -> when (e.reason) {
            WebArticleException.Reason.INVALID_URL -> r.getString(R.string.in_url_invalid)
            WebArticleException.Reason.NETWORK -> r.getString(R.string.in_err_network, e.message.orEmpty())
            WebArticleException.Reason.HTTP -> r.getString(R.string.in_err_http, e.httpCode)
            WebArticleException.Reason.NOT_HTML -> r.getString(R.string.in_err_not_html, e.message.orEmpty().substringAfter(": ", ""))
            WebArticleException.Reason.TOO_LARGE -> r.getString(R.string.in_err_too_large)
            WebArticleException.Reason.NO_TEXT -> r.getString(R.string.in_err_no_text)
        }
        is GutenbergException -> when (e.reason) {
            GutenbergException.Reason.NETWORK -> r.getString(R.string.in_err_network, e.message.orEmpty())
            GutenbergException.Reason.HTTP -> r.getString(R.string.in_err_http, e.httpCode)
            GutenbergException.Reason.BAD_RESPONSE -> r.getString(R.string.in_err_bad_response)
            GutenbergException.Reason.NO_EPUB -> r.getString(R.string.in_gb_no_epub)
            GutenbergException.Reason.NOT_EPUB -> r.getString(R.string.in_err_not_epub)
        }
        is AbsException -> when (e.reason) {
            AbsException.Reason.INVALID_URL -> r.getString(R.string.in_url_invalid)
            AbsException.Reason.CONNECTION -> r.getString(R.string.in_err_network, e.message.orEmpty())
            AbsException.Reason.CLEARTEXT -> r.getString(R.string.in_err_cleartext)
            AbsException.Reason.UNAUTHORIZED -> r.getString(R.string.in_err_unauthorized)
            AbsException.Reason.FORBIDDEN -> r.getString(R.string.in_err_forbidden)
            AbsException.Reason.NOT_FOUND -> r.getString(R.string.in_err_not_found)
            AbsException.Reason.HTTP -> r.getString(R.string.in_err_http, e.httpCode)
            AbsException.Reason.BAD_RESPONSE -> r.getString(R.string.in_err_bad_response)
        }
        else -> other()
    }
}
