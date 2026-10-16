package app.simplenarrator.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/**
 * Sprache der Oberfläche unabhängig von der Systemsprache. Ab Android 13 verwaltet das System die Wahl
 * (auch unter Einstellungen > Apps > Sprache sichtbar), davor merkt sich die App sie selbst.
 * Die Sprache der Stimmen hängt davon nicht ab, sie folgt dem Buchtext.
 */
object AppLanguage {

    /** Leerer Text bedeutet: wie das System. */
    val OPTIONS = listOf("", "de", "en")

    private const val PREFS = "app_language"
    private const val KEY = "language"

    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            return if (locales.isEmpty) "" else locales[0].language
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
    }

    fun set(activity: Activity, tag: String) {
        if (tag == current(activity)) return
        if (Build.VERSION.SDK_INT >= 33) {
            // Das System baut die sichtbaren Seiten danach selbst neu auf.
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, tag) }
            Locale.setDefault(if (tag.isEmpty()) Resources.getSystem().configuration.locales[0] else Locale.forLanguageTag(tag))
            activity.recreate()
        }
    }

    /** Kontext in einer bestimmten Sprache, etwa für Meldungen kurz vor einem Sprachwechsel. */
    fun contextFor(base: Context, tag: String): Context {
        val locale = if (tag.isEmpty()) Resources.getSystem().configuration.locales[0] else Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /**
     * Vor Android 13: Kontext in der gewählten Sprache, für Activity, Dienst und für Texte aus
     * Hintergrundarbeit (Export, Modell-Download). Ab Android 13 sorgt das System selbst dafür.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
