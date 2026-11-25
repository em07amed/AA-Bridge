package dev.caraalink.road

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.caraalink.R

/**
 * Tap the limit sign on the car screen, say the limit ("ستين", "80",
 * "مية وعشرين", or "امسح" to forget it), and it is saved for the road under
 * the car -- for roads OpenStreetMap has no limit for, or has wrong.
 * Uses the phone's speech recognizer; call from the main thread.
 */
object VoiceLimit {
    private var recognizer: SpeechRecognizer? = null

    fun listen(context: Context) {
        val app = context.applicationContext
        if (!SpeechRecognizer.isRecognitionAvailable(app) ||
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            RoadAlerts.showVoiceNote(R.string.road_voice_unavailable)
            return
        }
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(app)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                when {
                    said.any { RoadMath.isClearCommand(it) } -> RoadAlerts.setUserLimit(null)
                    else -> {
                        val limit = said.firstNotNullOfOrNull { RoadMath.parseSpokenLimit(it) }
                        if (limit != null) RoadAlerts.setUserLimit(limit)
                        else RoadAlerts.showVoiceNote(R.string.road_voice_not_understood)
                    }
                }
                stop()
            }

            override fun onError(error: Int) {
                RoadAlerts.showVoiceNote(R.string.road_voice_not_understood)
                stop()
            }

            override fun onReadyForSpeech(params: Bundle?) = RoadAlerts.showVoiceNote(R.string.road_voice_prompt)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val lang = app.resources.configuration.locales[0]
        val tag = if (lang.language == "ar") "ar-EG" else lang.toLanguageTag()
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5),
        )
    }

    private fun stop() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
