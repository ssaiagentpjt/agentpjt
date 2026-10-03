package com.agentpjt.shop.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "ShopStt"

sealed interface ListenState {
    data object Idle : ListenState
    data object Ready : ListenState
    data class Partial(val text: String) : ListenState
    data class Final(val text: String) : ListenState
    /** [code] 는 SpeechRecognizer.ERROR_* */
    data class Error(val code: Int) : ListenState
}

/**
 * OS 음성 인식(SpeechRecognizer)을 감싼다. SpeechRecognizer 는 메인 스레드에서만 만들고 불러야 한다.
 * Fold4 기본 인식기는 Google 이고 네트워크를 쓸 수 있다.
 */
class Listener(private val context: Context) {

    private val _state = MutableStateFlow<ListenState>(ListenState.Idle)
    val state: StateFlow<ListenState> = _state

    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(callbacks)
            recognizer = it
        }
        _state.value = ListenState.Idle
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(intent)
    }

    /** 말을 다 했다고 알린다. 인식기가 지금까지 들은 것으로 최종 결과를 낸다. */
    fun stop() = recognizer?.stopListening()

    fun cancel() {
        recognizer?.cancel()
        _state.value = ListenState.Idle
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = ListenState.Ready
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults.firstResult() ?: return
            if (text.isNotBlank()) _state.value = ListenState.Partial(text)
        }

        override fun onResults(results: Bundle?) {
            val text = results.firstResult().orEmpty()
            Log.i(TAG, "final \"$text\"")
            _state.value = if (text.isBlank()) ListenState.Error(SpeechRecognizer.ERROR_NO_MATCH) else ListenState.Final(text)
        }

        override fun onError(error: Int) {
            Log.w(TAG, "error $error")
            _state.value = ListenState.Error(error)
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}
