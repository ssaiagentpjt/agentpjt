package com.agentpjt.shop.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "ShopStt"

/**
 * 말이 끊긴 뒤 이만큼 새 말이 없으면 다 말한 것으로 본다. 어르신은 말이 느리고 중간에 쉰다.
 * 인식기에 무음 대기를 요청하는 extras(EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS 3000)는
 * 이 기기의 Google 인식기가 따르지 않았다 — 마지막 말 0.47초 뒤 최종 결과(8단계 A-1 실측). 그래서 앱이 기다린다.
 */
private const val PAUSE_MS = 2500L

/** 아직 아무 말도 들리지 않았을 때 기다리는 시간. 말을 꺼내기까지 머뭇거리는 경우 */
private const val FIRST_WORD_MS = 8000L

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
 *
 * 인식기는 짧은 쉼에도 한 마디를 끝내 버리므로, 한 마디가 끝나면 앱이 바로 다시 듣기 시작해 글을 이어 붙인다.
 * 끝내는 때: [PAUSE_MS] 동안 새 말이 없을 때, 사용자가 "다 말했어요"([stop])를 누를 때.
 */
class Listener(private val context: Context) {

    private val _state = MutableStateFlow<ListenState>(ListenState.Idle)
    val state: StateFlow<ListenState> = _state

    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())

    /** 지금까지 끝난 마디들 */
    private val said = StringBuilder()
    private var active = false
    /** 사용자가 "다 말했어요"를 눌렀다 — 지금 마디가 끝나면 바로 낸다 */
    private var finishing = false
    private var startedAt = 0L
    /** 마지막으로 말이 들린 시각. 쉼의 길이를 여기서부터 잰다(빈 마디가 끼어도 늘어나지 않게) */
    private var lastSpeechAt = 0L

    private val finishByPause = Runnable { finish("pause") }

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        said.clear()
        active = true
        finishing = false
        startedAt = System.currentTimeMillis()
        _state.value = ListenState.Idle
        main.removeCallbacks(finishByPause)
        listen()
    }

    /** 말을 다 했다고 알린다. 듣는 중인 마디가 있으면 그 결과까지 붙여서 낸다. */
    fun stop() {
        if (!active) return
        finishing = true
        main.removeCallbacks(finishByPause)
        recognizer?.stopListening() // onResults 또는 onError 에서 finish 한다
        main.postDelayed({ finish("button timeout") }, 1500) // 인식기가 답하지 않을 때를 대비
    }

    fun cancel() {
        active = false
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        _state.value = ListenState.Idle
    }

    fun destroy() {
        cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(callbacks)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(intent)
    }

    /** 한 마디가 끝났다. 더 말할 수 있으니 다시 듣고, 쉼이 길어지면 낸다 */
    private fun segmentDone(text: String) {
        if (text.isNotBlank()) {
            if (said.isNotEmpty()) said.append(' ')
            said.append(text.trim())
            lastSpeechAt = System.currentTimeMillis()
            _state.value = ListenState.Partial(said.toString())
        }
        when {
            finishing -> finish("button")
            said.isEmpty() && elapsed() > FIRST_WORD_MS -> finish("no speech")
            else -> {
                if (said.isNotEmpty()) {
                    main.removeCallbacks(finishByPause)
                    main.postDelayed(finishByPause, (PAUSE_MS - (System.currentTimeMillis() - lastSpeechAt)).coerceAtLeast(0))
                }
                listen()
            }
        }
    }

    private fun finish(why: String) {
        if (!active) return
        active = false
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        val text = said.toString()
        Log.i(TAG, "final($why) +${elapsed()}ms \"$text\"")
        _state.value = if (text.isBlank()) ListenState.Error(SpeechRecognizer.ERROR_NO_MATCH) else ListenState.Final(text)
    }

    private fun elapsed() = System.currentTimeMillis() - startedAt

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (active && said.isEmpty()) _state.value = ListenState.Ready
        }

        override fun onBeginningOfSpeech() {
            main.removeCallbacks(finishByPause) // 다시 말하기 시작했다 — 끝내지 않는다
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults.firstResult() ?: return
            if (!active || text.isBlank()) return
            main.removeCallbacks(finishByPause)
            lastSpeechAt = System.currentTimeMillis()
            _state.value = ListenState.Partial(if (said.isEmpty()) text else "$said $text")
        }

        override fun onResults(results: Bundle?) {
            if (!active) return
            val text = results.firstResult().orEmpty()
            Log.i(TAG, "segment +${elapsed()}ms \"$text\"")
            segmentDone(text)
        }

        override fun onError(error: Int) {
            if (!active) return
            Log.i(TAG, "segment error $error +${elapsed()}ms")
            when (error) {
                // 말이 없었거나 알아듣지 못한 마디: 쉬는 중으로 보고 계속 듣는다
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> segmentDone("")
                else -> if (said.isNotEmpty()) finish("error $error") else {
                    active = false
                    main.removeCallbacks(finishByPause)
                    _state.value = ListenState.Error(error)
                }
            }
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}
