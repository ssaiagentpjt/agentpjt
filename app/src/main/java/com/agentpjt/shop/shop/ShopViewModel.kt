package com.agentpjt.shop.shop

import android.app.Application
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agentpjt.shop.BuildConfig
import com.agentpjt.shop.agent.Action
import com.agentpjt.shop.agent.AgentLoop
import com.agentpjt.shop.agent.AgentTurn
import com.agentpjt.shop.agent.LiteRtDecider
import com.agentpjt.shop.agent.ToolExecutor
import com.agentpjt.shop.agent.systemPrompt
import com.agentpjt.shop.api.HttpShopApi
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

    // 말(에이전트)과 터치가 같은 실행기를 쓴다. 주문 경로와 안전 검사가 한 곳에 있다
    private val executor = ToolExecutor(HttpShopApi(BuildConfig.SHOP_API_BASE_URL, BuildConfig.SHOP_API_KEY))

    private val _state = MutableStateFlow(ShopState())
    val state: StateFlow<ShopState> = _state

    private var engine: GemmaEngine? = null
    private var decider: LiteRtDecider? = null
    private var agent: AgentLoop? = null
    private var agentJob: Job? = null

    // ViewModel 은 회전·폴드 펼침에서도 살아남으므로 여기서 한 번만 읽기 시작한다.
    init {
        if (BuildConfig.SHOP_API_KEY.isBlank()) Log.w(TAG, "SHOP_API_KEY 가 비어 있다 — local.properties 에 shop.apiKey 를 넣어야 서버가 응답한다")
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
            val d = LiteRtDecider(e) { systemPrompt() }
            decider = d
            agent = AgentLoop(d, executor).also { it.reset() }
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
        if (s.ai != AiStatus.READY || s.agentBusy) {
            if (s.ai != AiStatus.READY) speaker.speak(Scripts.aiUnavailable())
            return
        }
        speaker.stop() // TTS 소리가 마이크로 들어가지 않게
        _state.update { it.copy(screen = Screen.Listening, returnTo = s.effectiveScreen, heard = "", agentBusy = false, bubble = "") }
        listener.start()
    }

    /** 처음 화면 예시 문장: 그 글을 말한 것처럼 에이전트에 넘긴다(STT 를 거치지 않음). */
    fun submitText(text: String) {
        val s = _state.value
        if (s.ai != AiStatus.READY || s.agentBusy) return
        speaker.stop()
        _state.update { it.copy(screen = Screen.Listening, returnTo = s.effectiveScreen, heard = text, bubble = "") }
        runAgent(text)
    }

    fun finishListening() = listener.stop()

    fun cancelListening() {
        listener.cancel()
        agentJob?.cancel()
        _state.update { it.copy(agentBusy = false) }
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
        _state.update { it.copy(heard = heard, agentBusy = true, progress = "알아듣고 있어요") }
        speaker.speak(Scripts.thinking())
        agentJob = viewModelScope.launch {
            val before = _state.value
            val turn = try {
                a.handle(heard, before) { p -> _state.update { it.copy(progress = p) } }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "agent failed", t)
                AgentTurn.Failed(before, AgentTurn.Reason.ENGINE)
            }
            // 그사이 사용자가 "그만하기"나 뒤로 가기로 떠났으면 결과를 버린다
            if (_state.value.screen != Screen.Listening) return@launch
            render(turn, before.returnTo)
        }
    }

    /** 에이전트 한 턴의 결과를 화면과 음성으로 그린다. 들리는 말은 앱 대본이고, 모델 문장은 ask·answer 뿐이다 */
    private fun render(turn: AgentTurn, returnTo: Screen) {
        // 화면을 바꾸지 않은 결과는 말을 시작한 화면으로 돌아간다
        val st = turn.state.let { if (it.screen == Screen.Listening) it.copy(screen = returnTo) else it }
            .copy(agentBusy = false, heard = "", progress = "", bubble = "", missingOption = null)
        when (turn) {
            is AgentTurn.Moved -> {
                _state.value = st
                speakScreen()
                if (st.screen == Screen.Home) viewModelScope.launch { agent?.reset() } // 쇼핑 세션마다 대화를 새로 연다
            }
            is AgentTurn.NeedOption -> {
                _state.value = st.copy(missingOption = turn.choices.keys.first())
                speaker.speak(Scripts.askOption(turn.choices))
            }
            is AgentTurn.Said -> {
                _state.value = st.copy(bubble = SpeechText.clean(turn.text))
                speaker.speak(Scripts.say(turn.text))
            }
            is AgentTurn.Failed -> {
                _state.value = st
                speaker.speak(if (turn.reason == AgentTurn.Reason.OFFLINE) Scripts.networkError() else Scripts.agentFailed())
            }
        }
    }

    // ---- 터치: 에이전트와 같은 도구를 거친다 ---------------------------------

    fun pick(index: Int) {
        val p = _state.value.shown.getOrNull(index) ?: return
        touch(Action.Open(p.id))
    }

    fun chooseOption(name: String, value: String) = touch(Action.Option(name, value))

    fun changeQty(delta: Int) = _state.update { it.copy(qty = (it.qty + delta).coerceIn(1, 9)) }

    /** 상세 화면 "주문하기". 옵션이 빠졌으면 확인 화면으로 가지 않고 어떤 옵션을 골라야 하는지 읽어 준다 */
    fun order() = touch(Action.Order())

    /** 확인 화면 "네, 주문" */
    fun placeOrder() = touch(Action.Place)

    /** 확인 화면 "아니요" */
    fun cancelOrder() = touch(Action.Cancel)

    private fun touch(action: Action) {
        val s = _state.value
        if (s.agentBusy) return
        _state.update { it.copy(agentBusy = true) }
        viewModelScope.launch {
            val out = executor.execute(action, s)
            _state.value = out.state.copy(agentBusy = false, bubble = "", missingOption = if (out.choices.isEmpty()) null else out.choices.keys.first())
            when {
                // 터치로 옵션을 고른 것처럼 화면이 그대로면 읽지 않는다. 어르신이 방금 본 것을 되풀이하지 않는다
                out.ok -> if (out.state.screen != s.screen) speakScreen()
                out.offline -> speaker.speak(Scripts.networkError())
                out.choices.isNotEmpty() -> speaker.speak(Scripts.askOption(out.choices))
                else -> speaker.speak(Scripts.agentFailed())
            }
        }
    }

    fun stopReading() = speaker.stop()

    fun openDevMenu() {
        speaker.stop()
        _state.update { it.copy(screen = Screen.DevLlm) }
    }

    /** 화면을 바꾸고 그 화면의 대본을 읽는다. */
    fun go(screen: Screen, speak: Boolean = true) {
        if (screen == Screen.Home) {
            _state.update { ShopState(ai = it.ai, micDenied = it.micDenied) }
            viewModelScope.launch { agent?.reset() } // 쇼핑 세션마다 대화를 새로 연다
        } else {
            _state.update { it.copy(screen = screen, bubble = "", missingOption = null) }
        }
        if (speak) speakScreen() else if (screen == Screen.DevLlm) speaker.stop()
    }

    private fun speakScreen() {
        val s = _state.value
        val p = s.current
        when (s.screen) {
            Screen.Home -> speaker.speak(Scripts.home())
            Screen.Results -> speaker.speak(Scripts.results(s.label, s.shown))
            Screen.Detail -> p?.let { speaker.speak(Scripts.detail(it, s.selected)) }
            Screen.Confirm -> p?.let { speaker.speak(Scripts.confirm(it, s.qty, s.selected)) }
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
        decider?.close()
        engine?.close()
    }
}
