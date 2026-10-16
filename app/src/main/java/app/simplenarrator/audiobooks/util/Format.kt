package app.simplenarrator.audiobooks.util

import android.content.Context
import android.text.format.DateUtils
import app.simplenarrator.R
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object Format {

    /** "1:02:03" oder "2:03". */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** "5 h 3 min", "42 min", "< 1 min". */
    fun duration(context: Context, ms: Long): String {
        val minutes = (ms.coerceAtLeast(0) + 30_000) / 60_000
        val h = minutes / 60
        val m = minutes % 60
        return when {
            minutes == 0L -> context.getString(R.string.ab_dur_less_than_min)
            h == 0L -> context.getString(R.string.ab_dur_min, m)
            m == 0L -> context.getString(R.string.ab_dur_h, h)
            else -> context.getString(R.string.ab_dur_h_min, h, m)
        }
    }

    /** "1,25×" in der Schreibweise der Sprache. */
    fun speed(speed: Float, locale: Locale = Locale.getDefault()): String {
        val df = DecimalFormat("0.0#", DecimalFormatSymbols.getInstance(locale))
        return df.format(speed.toDouble()) + "×"
    }

    fun percent(fraction: Float): String = "${(fraction * 100).toInt().coerceIn(0, 100)} %"

    fun date(context: Context, millis: Long): String =
        DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

    fun dateTime(context: Context, millis: Long): String =
        DateUtils.formatDateTime(
            context, millis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME,
        )
}
