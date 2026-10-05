package com.agentpjt.shop.shop

import android.app.Application
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agentpjt.shop.agent.ShopAgent
import com.agentpjt.shop.llm.BackendKind
import com.agentpjt.shop.llm.GemmaEngine
import com.agentpjt.shop.voice.ListenState
import com.agentpjt.shop.voice.Listener
import com.agentpjt.shop.voice.Speaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "ShopAgent"

// 1단계 실측: 이 기기(Adreno 730)에서 GPU 빌드는 출력이 깨져서 범용 파일을 CPU 로 돌린다
private const val MODEL_FILE = "gemma-4-E2B-it.litertlm"

class ShopViewModel(app: Application) : AndroidViewModel(app) {

    val speaker = Speaker(app)
    val listener = Listener(app)
    private val catalog: Catalog = MockCatalog()

    private val _state = MutableStateFlow(ShopState())
    val state: StateFlow<ShopState> = _state

    private var engine: GemmaEngine? = null
    private var agent: ShopAgent? = null
    private var agentJob: Job? = null

    // ViewModel 은 회전·폴드 펼침에서도 살아남으므로 여기서 한 번만 읽기 시작한다.
    init {
        speaker.speak(Scripts.home())
        viewModelScope.launch { loadModel() }
        viewModelScope.launch { listener.state.collect(::onListen) }
    }

    private suspend fun loadModel() {
        val dir = getApplication<Application>().getExternalFilesDir(null)
        val file = dir?.let { File(it, MODEL_FILE) }
        if (file == null || !file.exists()) {
            Log.w(TAG, "model missing: ${file?.absolutePath}")
            _state.update { it.copy(ai = AiStatus.UNAVAILABLE) }
            return
        }
        try {
            val e = GemmaEngine(file, getApplication<Application>().cacheDir)
            val ms = e.load(BackendKind.CPU)
            engine = e
            agent = ShopAgent(e, catalog, viewModelScope).also { it.reset(_state.value.thinkingMode) }
            Log.i(TAG, "model ready ${ms}ms")
            _state.update { it.copy(ai = AiStatus.READY) }
        } catch (t: Throwable) {
            Log.e(TAG, "model load failed", t)
            _state.update { it.copy(ai = AiStatus.UNAVAILABLE) }
        }
    }

    // ---- 말하기 ----------------------------------------------------------

    fun startListening() {
        val s = _state.value
        if (s.ai != AiStatus.READY || agentJob?.isActive == true) {
            if (s.ai != AiStatus.READY) speaker.speak(Scripts.aiUnavailable())
            return
        }
        speaker.stop() // TTS 소리가 마이크로 들어가지 않게
        val from = if (s.screen == Screen.Listening) s.returnTo else s.screen
        _state.update { it.copy(screen = Screen.Listening, returnTo = from, heard = "", agentBusy = false) }
        listener.start()
    }

    /** 처음 화면 예시 문장: 그 글을 말한 것처럼 에이전트에 넘긴다(STT 를 거치지 않음). */
    fun submitText(text: String) {
        val s = _state.value
        if (s.ai != AiStatus.READY || agentJob?.isActive == true) return
        speaker.stop()
        _state.update { it.copy(screen = Screen.Listening, returnTo = s.screen, heard = text) }
        runAgent(text)
    }

    fun finishListening() = listener.stop()

    fun cancelListening() {
        listener.cancel()
        agentJob?.cancel()
        go(_state.value.returnTo, speak = false)
    }

    fun setMicDenied(denied: Boolean) = _state.update { it.copy(micDenied = denied) }

    private fun onListen(ls: ListenState) {
        if (_state.value.screen != Screen.Listening) return
        when (ls) {
            is ListenState.Partial -> _state.update { it.copy(heard = ls.text) }
            is ListenState.Final -> if (!_state.value.agentBusy) runAgent(ls.text)
            is ListenState.Error -> {
                // OS 인식 오류 코드 처리. 말 내용을 해석하는 규칙이 아니다.
                if (_state.value.agentBusy) return
                Log.w(TAG, "stt error ${ls.code}")
                go(_state.value.returnTo, speak = false)
                speaker.speak(
                    if (ls.code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) Scripts.aiUnavailable() else Scripts.notHeard(),
                )
            }
            ListenState.Idle, ListenState.Ready -> Unit
        }
    }

    private fun runAgent(heard: String) {
        val a = agent ?: return
        _state.update { it.copy(heard = heard, agentBusy = true) }
        speaker.speak(Scripts.thinking())
        agentJob = viewModelScope.launch {
            val before = _state.value
            try {
                val reply = a.handle(heard, before)
                // 그사이 사용자가 "그만하기"나 뒤로 가기로 떠났으면 결과를 버린다
                if (_state.value.screen != Screen.Listening) return@launch
                if (reply.navigated) {
                    _state.value = reply.state.copy(agentBusy = false, heard = "")
                    speakScreen()
                    if (reply.state.screen == Screen.Home) a.reset(_state.value.thinkingMode)
                } else {
                    // 화면을 바꾸지 않았으면(되묻기, 수량 변경 등) 말을 시작한 화면으로 돌아가 모델의 말을 읽는다
                    _state.value = reply.state.copy(screen = before.returnTo, agentBusy = false, heard = "")
                    speaker.speak(reply.say?.let(Scripts::say) ?: Scripts.agentFailed())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "agent failed", t)
                if (_state.value.screen != Screen.Listening) return@launch
                _state.update { it.copy(screen = before.returnTo, agentBusy = false, heard = "") }
                speaker.speak(Scripts.agentFailed())
            }
        }
    }

    // ---- 터치 ------------------------------------------------------------

    fun pick(index: Int) {
        val p = _state.value.shown.getOrNull(index) ?: return
        _state.update { it.copy(current = p, qty = 1) }
        go(Screen.Detail)
    }

    fun changeQty(delta: Int) = _state.update { it.copy(qty = (it.qty + delta).coerceIn(1, 9)) }

    fun placeOrder() {
        _state.update { it.copy(orderId = "M-20261003-" + (1000..9999).random()) }
        go(Screen.Done)
    }

    fun stopReading() = speaker.stop()

    /** 처음 화면 AI 칩 길게 누르기: 생각 모드를 켜고 끈다(실측 비교용). */
    fun toggleThinking() {
        val on = !_state.value.thinkingMode
        _state.update { it.copy(thinkingMode = on) }
        viewModelScope.launch { agent?.reset(on) }
    }

    fun openDevMenu() {
        speaker.stop()
        _state.update { it.copy(screen = Screen.DevLlm) }
    }

    /** 화면을 바꾸고 그 화면의 대본을 읽는다. */
    fun go(screen: Screen, speak: Boolean = true) {
        if (screen == Screen.Home) {
            _state.update { ShopState(ai = it.ai, thinkingMode = it.thinkingMode, micDenied = it.micDenied) }
            viewModelScope.launch { agent?.reset(_state.value.thinkingMode) } // 쇼핑 세션마다 대화를 새로 연다
        } else {
            _state.update { it.copy(screen = screen) }
        }
        if (speak) speakScreen() else if (screen == Screen.DevLlm) speaker.stop()
    }

    private fun speakScreen() {
        val s = _state.value
        val p = s.current
        when (s.screen) {
            Screen.Home -> speaker.speak(Scripts.home())
            Screen.Results -> speaker.speak(Scripts.results(s.label, s.shown))
            Screen.Detail -> p?.let { speaker.speak(Scripts.detail(it)) }
            Screen.Confirm -> p?.let { speaker.speak(Scripts.confirm(it, s.qty)) }
            Screen.Done -> p?.let { speaker.speak(Scripts.done(it)) }
            Screen.Listening, Screen.DevLlm -> speaker.stop()
        }
    }

    /** 시스템 뒤로 가기. 처음 화면에서는 false 를 돌려 앱이 닫히게 둔다. */
    fun back(): Boolean {
        val s = _state.value
        when (s.screen) {
            Screen.Home -> return false
            Screen.Listening -> cancelListening()
            Screen.Results, Screen.Done, Screen.DevLlm -> go(Screen.Home)
            Screen.Detail -> go(if (s.shown.isEmpty()) Screen.Home else Screen.Results)
            Screen.Confirm -> go(Screen.Detail)
        }
        return true
    }

    override fun onCleared() {
        agentJob?.cancel()
        listener.destroy()
        speaker.shutdown()
        agent?.close()
        engine?.close()
    }
}
