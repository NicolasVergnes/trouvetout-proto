package app.trouvetout.android.capture

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import app.trouvetout.core.capture.Lang
import app.trouvetout.core.capture.SpeechAvailability
import app.trouvetout.core.capture.SpeechPolicy
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** What the recogniser reports during one listening run. */
sealed interface SpeechEvent {
    data class Partial(
        val text: String,
    ) : SpeechEvent

    data class Final(
        val text: String,
    ) : SpeechEvent

    data class Failed(
        val error: SpeechError,
    ) : SpeechEvent
}

enum class SpeechError { NO_MATCH, LANGUAGE_UNAVAILABLE, PERMISSION, BUSY, OTHER }

/**
 * On-device speech recognition (B2). Only [SpeechRecognizer.createOnDeviceSpeechRecognizer] is ever used: the
 * « prefer offline » hint of older systems does not keep the audio on the phone, so Android 10–11 get text.
 * The audio is never stored; the transcript goes to the core parser like a typed sentence.
 */
class SpeechController(
    private val context: Context,
) {
    fun availability(): SpeechAvailability {
        val onDevice =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            } else {
                null
            }
        return SpeechPolicy.decide(onDevice, onDevice == true || SpeechRecognizer.isRecognitionAvailable(context))
    }

    /** One run, ended by a final result or an error; cancelling the collector stops the microphone. Main thread. */
    @RequiresApi(Build.VERSION_CODES.S)
    fun listen(lang: Lang): Flow<SpeechEvent> = callbackFlow {
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults.firstText()?.let { trySend(SpeechEvent.Partial(it)) }
                }

                override fun onResults(results: Bundle?) {
                    val text = results.firstText()
                    trySend(
                        if (text.isNullOrBlank()) SpeechEvent.Failed(SpeechError.NO_MATCH) else SpeechEvent.Final(text),
                    )
                    close()
                }

                override fun onError(error: Int) {
                    trySend(SpeechEvent.Failed(errorOf(error)))
                    close()
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) = Unit
            },
        )
        recognizer.startListening(
            android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang == Lang.FR) "fr-FR" else "en-US")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            },
        )
        awaitClose {
            recognizer.cancel()
            recognizer.destroy()
        }
    }

    private fun Bundle?.firstText(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun errorOf(code: Int): SpeechError = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NO_MATCH
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            SpeechError.LANGUAGE_UNAVAILABLE
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.PERMISSION
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> SpeechError.BUSY
        else -> SpeechError.OTHER
    }
}
