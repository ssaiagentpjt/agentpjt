package com.agentpjt.shop.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.agentpjt.shop.shop.Utterance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

private const val TAG = "ShopTts"

enum class SpeakerStatus { INITIALIZING, READY, NO_KOREAN, FAILED }

/** OS TTS 엔진을 감싼다. 문장 단위로 큐에 넣고, 지금 읽는 문장 id 를 [speaking] 으로 내보낸다. */
class Speaker(context: Context) {

    private val _status = MutableStateFlow(SpeakerStatus.INITIALIZING)
    val status: StateFlow<SpeakerStatus> = _status

    private val _speaking = MutableStateFlow<String?>(null)
    val speaking: StateFlow<String?> = _speaking

    // 초기화가 끝나기 전에 들어온 대본. 엔진 연결은 비동기라 첫 화면 대본이 여기서 기다린다.
    private var pending: List<Utterance>? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { code -> onInit(code) }

    private fun onInit(code: Int) {
        if (code != TextToSpeech.SUCCESS) {
            Log.e(TAG, "init failed code=$code")
            _status.value = SpeakerStatus.FAILED
            return
        }
        val lang = tts.setLanguage(Locale.KOREAN)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "korean unavailable lang=$lang engine=${tts.defaultEngine}")
            _status.value = SpeakerStatus.NO_KOREAN
            return
        }
        // 어르신 대상이라 기본보다 조금 느리게
        tts.setSpeechRate(0.85f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            // 콜백은 바인더 스레드에서 온다. StateFlow 는 스레드 안전하다.
            override fun onStart(utteranceId: String) {
                Log.i(TAG, "start $utteranceId")
                _speaking.value = utteranceId
            }

            override fun onDone(utteranceId: String) {
                Log.i(TAG, "done $utteranceId")
                if (_speaking.value == utteranceId) _speaking.value = null
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                if (_speaking.value == utteranceId) _speaking.value = null
            }

            @Deprecated("API 21 이전 콜백. onError(String, Int) 와 같이 처리한다.")
            override fun onError(utteranceId: String) {
                Log.e(TAG, "error $utteranceId")
                if (_speaking.value == utteranceId) _speaking.value = null
            }
        })
        Log.i(TAG, "ready engine=${tts.defaultEngine}")
        _status.value = SpeakerStatus.READY
        pending?.let { speak(it) }
        pending = null
    }

    /** 지금 읽던 것을 끊고 [lines] 를 차례로 읽는다. */
    fun speak(lines: List<Utterance>) {
        if (_status.value != SpeakerStatus.READY) {
            pending = lines
            return
        }
        tts.stop()
        _speaking.value = null
        lines.forEachIndexed { i, u ->
            tts.speak(u.text, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, u.id)
        }
    }

    fun stop() {
        pending = null
        if (_status.value == SpeakerStatus.READY) tts.stop()
        _speaking.value = null
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
