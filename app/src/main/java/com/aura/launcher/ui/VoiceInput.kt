package com.aura.launcher.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Inline speech-to-text for the Gemini panel: no dialog, no other app. Prefers the private
 * on-device recognizer and falls back to the system recognizer if no on-device model is ready.
 */
class VoiceInput(private val context: Context) {
    var listening by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var recognizer: SpeechRecognizer? = null
    private var triedFallback = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(onFinal: (String) -> Unit) {
        stop()
        error = null
        partial = ""
        triedFallback = false
        begin(onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context), onFinal = onFinal)
    }

    private fun begin(onDevice: Boolean, onFinal: (String) -> Unit) {
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { level = 0f }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                finish()
                if (text.isNotBlank()) onFinal(text)
            }

            override fun onError(code: Int) {
                val recoverable = code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                    code == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                    code == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT
                if (onDevice && recoverable && !triedFallback) {
                    triedFallback = true
                    destroyRecognizer()
                    begin(onDevice = false, onFinal = onFinal)
                    return
                }
                finish()
                error = when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No connection for speech"
                    else -> "Voice input unavailable"
                }
            }
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        )
        listening = true
    }

    fun stop() {
        recognizer?.stopListening()
        finish()
    }

    private fun finish() {
        listening = false
        level = 0f
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }
}
