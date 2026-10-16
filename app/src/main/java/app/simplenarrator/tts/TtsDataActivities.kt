package app.simplenarrator.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import app.simplenarrator.MainActivity
import app.simplenarrator.R

/*
 * Die kleinen, unsichtbaren Activities, die das System bei einer Sprachausgabe-Engine aufruft.
 * Alle beenden sich sofort in onCreate (Theme.NoDisplay im Manifest).
 */

/** CHECK_TTS_DATA: meldet die Stimmen "deu-DEU" und "eng-USA" (heruntergeladen wird in der App selbst). */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Seit API 21 veraltet, ältere Aufrufer schicken die Wunschliste aber noch mit
        @Suppress("DEPRECATION")
        val requested = intent?.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_CHECK_VOICE_DATA_FOR)
        val (available, unavailable) = SystemTtsSupport.checkVoices(requested)
        val data = Intent()
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, ArrayList(available))
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList(unavailable))
        val result = if (available.isNotEmpty()) TextToSpeech.Engine.CHECK_VOICE_DATA_PASS else TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL
        setResult(result, data)
        finish()
    }
}

/** GET_SAMPLE_TEXT: ein Beispielsatz in der angefragten Sprache (Extra "language", ISO 639). */
class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sample = when (SystemTtsSupport.appLang(intent?.getStringExtra("language"))) {
            "de" -> getString(R.string.sp_sample_de)
            "en" -> getString(R.string.sp_sample_en)
            else -> null
        }
        if (sample == null) {
            setResult(TextToSpeech.LANG_NOT_SUPPORTED)
        } else {
            setResult(TextToSpeech.LANG_AVAILABLE, Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sample))
        }
        finish()
    }
}

/** INSTALL_TTS_DATA: öffnet die App, damit dort Stimmen geladen werden können. */
class InstallVoiceDataActivity : Activity() {
    companion object {
        /** Wunsch an MainActivity, gleich die Stimmenverwaltung zu zeigen (wird dort noch nicht ausgewertet). */
        const val EXTRA_OPEN_VOICES = "app.simplenarrator.extra.OPEN_VOICES"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_OPEN_VOICES, true),
        )
        finish()
    }
}
