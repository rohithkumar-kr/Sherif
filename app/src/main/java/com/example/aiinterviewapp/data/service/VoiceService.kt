package com.example.aiinterviewapp.data.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsState: TtsState = TtsState.STARTING

    /**
     * The most recent question asked for before the engine finished starting.
     *
     * `TextToSpeech` initialises asynchronously, and the very first question of
     * a session is asked almost immediately after the service is created. With
     * nothing held here, `speak()` saw a not-ready engine, returned without doing
     * anything, and the opening question was never spoken. One slot is enough: if
     * two questions are queued, the later one is the one the user needs to hear.
     */
    @Volatile
    private var pendingSpeech: String? = null

    @Volatile
    private var currentRecognizer: SpeechRecognizer? = null

    init {
        startTts()
    }

    /**
     * Brings the engine up, tolerating every way it can fail.
     *
     * Two things were not handled before and both are unrecoverable from the
     * caller's side:
     *
     *  * The [TextToSpeech] *constructor* throws on some devices when no engine
     *    can be bound. Uncaught, that propagates out of the Hilt graph and takes
     *    the whole app down at startup for a missing accessibility feature.
     *  * `setLanguage` is a request, not a command. It returns
     *    [TextToSpeech.LANG_NOT_SUPPORTED] when the engine has no voice for the
     *    locale, and the previous code ignored the result, so a device without
     *    the requested language played a silent app and reported success.
     *
     * The engine is deliberately never `shutdown()`. This is an app-scoped
     * singleton and there is no reliable "the last ViewModel went away" signal
     * to shut it down on; tearing it down per ViewModel would pay the
     * initialisation cost again on every navigation, which is the bug the
     * pending-speech slot exists to work around in the first place.
     */
    private fun startTts() {
        val engine = try {
            TextToSpeech(context.applicationContext) { status ->
                onTtsInitialised(status)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Text-to-speech could not be created; answers will not be spoken", e)
            ttsState = TtsState.UNAVAILABLE
            return
        }

        tts = engine
    }

    private fun onTtsInitialised(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Text-to-speech engine unavailable (status $status); answers will not be spoken")
            ttsState = TtsState.UNAVAILABLE
            return
        }

        val engine = tts
        if (engine == null) {
            ttsState = TtsState.UNAVAILABLE
            return
        }

        if (!applyBestAvailableLanguage(engine)) {
            ttsState = TtsState.UNAVAILABLE
            return
        }

        ttsState = TtsState.READY

        // Flush whatever was asked for while the engine was starting. Read
        // through to a local and clear first: this runs on the engine's thread,
        // and a second speak() arriving in between must not be overwritten by
        // one that has already been handed off.
        val queued = pendingSpeech
        pendingSpeech = null
        if (queued != null) {
            speakNow(queued)
        }
    }

    /**
     * Picks a voice the engine actually has.
     *
     * The device locale is tried first so questions are read in the language the
     * user is actually working in, with [Locale.US] as the fallback because
     * Android's default `TTS` engine ships an English voice on essentially every
     * device and that is a far better outcome than silence. A user on a
     * French device is better served by a French voice, and a user on a
     * Japanese device with no Japanese voice is still served by English rather
     * than by nothing.
     */
    private fun applyBestAvailableLanguage(engine: TextToSpeech): Boolean {
        val device = Locale.getDefault()
        val deviceResult = try {
            engine.setLanguage(device)
        } catch (e: Exception) {
            Log.w(TAG, "Setting the device language for speech failed", e)
            TextToSpeech.LANG_NOT_SUPPORTED
        }

        if (deviceResult >= TextToSpeech.LANG_AVAILABLE) return true

        Log.w(TAG, "No voice available for ${device.toLanguageTag()}; falling back to ${Locale.US.toLanguageTag()}")

        val fallbackResult = try {
            engine.setLanguage(Locale.US)
        } catch (e: Exception) {
            Log.w(TAG, "Setting the fallback language for speech failed", e)
            TextToSpeech.LANG_NOT_SUPPORTED
        }

        if (fallbackResult >= TextToSpeech.LANG_AVAILABLE) return true

        Log.w(TAG, "No usable voice is installed; answers will not be spoken")
        return false
    }

    /**
     * Speaks [text], or holds it until the engine is ready.
     *
     * `QUEUE_FLUSH` rather than `QUEUE_ADD`: a new question supersedes the one
     * being read, and letting them queue meant a user who skipped two questions
     * heard all three in order.
     */
    fun speak(text: String) {
        if (text.isBlank()) return

        when (ttsState) {
            TtsState.READY -> speakNow(text)
            // An engine that failed to start will not start later, so holding
            // the text here forever would be a silent leak. Dropping it is the
            // only honest outcome, and the log line says why.
            TtsState.UNAVAILABLE -> Log.d(TAG, "Dropping speech: no text-to-speech engine")
            TtsState.STARTING -> pendingSpeech = text
        }
    }

    private fun speakNow(text: String) {
        val engine = tts ?: return
        try {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        } catch (e: Exception) {
            // Some engines throw here instead of returning an error code, and a
            // question that cannot be read aloud must not abort the interview.
            Log.w(TAG, "Speaking failed; continuing without speech", e)
        }
    }

    /**
     * Stops speech and discards anything queued.
     *
     * Called when the interview view model is cleared. Without clearing
     * [pendingSpeech], quitting an interview during the first second would leave
     * the opening question queued and it would be spoken over the home screen
     * once the engine finished starting.
     */
    fun stopTts() {
        pendingSpeech = null
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Stopping speech failed", e)
        }
    }

    /**
     * Stops the active recogniser if one is running.
     *
     * Idempotent, and the only place a recogniser is released. Previously
     * `stopListening` and the flow's `awaitClose` both destroyed it, and the
     * listener callbacks cleared the field without destroying anything -- so a
     * recogniser could be destroyed twice, or destroyed while
     * `currentRecognizer` still named it and a fresh [startListening] was
     * refused as "already listening".
     */
    fun stopListening() {
        releaseRecognizer(stopFirst = true)
    }

    private fun releaseRecognizer(stopFirst: Boolean) {
        val recognizer = currentRecognizer ?: return
        currentRecognizer = null

        try {
            if (stopFirst) recognizer.stopListening()
        } catch (e: Exception) {
            Log.d(TAG, "Ignoring failure while stopping the recogniser", e)
        }

        try {
            recognizer.destroy()
        } catch (e: Exception) {
            Log.d(TAG, "Ignoring failure while destroying the recogniser", e)
        }
    }

    /**
     * One dictation attempt.
     *
     * Every way this can fail is handled before the flow is returned rather than
     * inside it. The reason is that a failure that only reports itself through
     * an exception leaves the caller waiting forever: the microphone is on, the
     * flow never emits, and the UI has no way to recover. The three pre-checks
     * below all cover devices where the recogniser is present but cannot work --
     * no service installed, no microphone permission, no free-form model.
     */
    fun startListening(): Flow<VoiceResult> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(VoiceResult.Error("Voice input isn't available on this device."))
            close()
            return@callbackFlow
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            trySend(VoiceResult.Error("Microphone permission is needed for voice input."))
            close()
            return@callbackFlow
        }

        if (currentRecognizer != null) {
            trySend(VoiceResult.Error("The microphone is already in use. Please try again."))
            close()
            return@callbackFlow
        }

        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            Log.w(TAG, "Creating the speech recogniser failed", e)
            trySend(VoiceResult.Error("Voice input isn't available right now. Please try again."))
            close()
            return@callbackFlow
        }

        currentRecognizer = recognizer

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            // No result is better than a wrong one: a partial transcript typed
            // into an interview answer is worse than asking the user to repeat
            // themselves, and the error path already has wording for that.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onError(error: Int) {
                // Release before emitting. The collector may immediately start
                // another attempt, and if the field were still set it would be
                // refused as "already listening" -- so the retry the error
                // message invites would fail.
                releaseRecognizer(stopFirst = false)
                trySend(VoiceResult.Error(error.messageForCode()))
                close()
            }

            override fun onResults(results: Bundle?) {
                releaseRecognizer(stopFirst = false)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches.isNullOrEmpty() || matches[0].isBlank()) {
                    trySend(VoiceResult.Error("I couldn't hear you. Please try again."))
                } else {
                    trySend(VoiceResult.Success(matches[0]))
                }
                close()
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        recognizer.setRecognitionListener(listener)

        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            // A device with no recogniser service throws here rather than
            // reporting through the listener, and the exception would otherwise
            // cancel the flow and surface as a generic failure.
            Log.w(TAG, "Starting dictation failed", e)
            releaseRecognizer(stopFirst = false)
            trySend(VoiceResult.Error("Voice input isn't available right now. Please try again."))
            close()
            return@callbackFlow
        }

        awaitClose {
            // Reached when the collector stops, when the user cancels, or when
            // the flow completes above. `releaseRecognizer` is a no-op if a
            // callback already released it, so exactly one destroy happens per
            // recogniser.
            releaseRecognizer(stopFirst = true)
        }
    }

    private fun Int.messageForCode(): String = when (this) {
        SpeechRecognizer.ERROR_NO_MATCH -> "I couldn't understand that. Please try again."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Please try again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed for voice input."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy. Please try again."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be read. Please try again."
        SpeechRecognizer.ERROR_CLIENT -> "Voice input was interrupted. Please try again."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "Voice input isn't available in this language. Please try again."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "A network error stopped voice input. Please try again."
        SpeechRecognizer.ERROR_SERVER -> "The voice service returned an error. Please try again."
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT ->
            "Voice input isn't downloaded yet. Please try again."
        else -> "Voice recognition failed. Please try again."
    }

    private enum class TtsState { STARTING, READY, UNAVAILABLE }

    private companion object {
        const val TAG = "SherifVoice"
    }
}

sealed class VoiceResult {
    data class Success(val text: String) : VoiceResult()
    data class Error(val message: String) : VoiceResult()
}
