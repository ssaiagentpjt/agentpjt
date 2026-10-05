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
import com.agentpjt.shop.llm.ModelInstaller
import com.agentpjt.shop.voice.ListenState
import com.agentpjt.shop.voice.Listener
import com.agentpjt.shop.voice.Speaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ShopAgent"


class ShopViewModel(app: Application) : AndroidViewModel(app) {

    val speaker = Speaker(app)
    val listener = Listener(app)

    // 말(에이전트)과 터치가 같은 실행기를 쓴다. 주문 경로와 안전 검사가 한 곳에 있다
    private val executor = ToolExecutor(HttpShopApi(BuildConfig.SHOP_API_BASE_URL, BuildConfig.SHOP_API_KEY))

    private val cartStore = CartStore(File(app.filesDir, "cart.json"))
    // 1단계 실측: 이 기기(Adreno 730)에서 GPU 빌드는 출력이 깨져서 범용 파일을 CPU 로 돌린다
    private val installer = ModelInstaller(app)
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow(ShopState(cart = cartStore.load()))
    val state: StateFlow<ShopState> = _state

    private var engine: GemmaEngine? = null
    private var decider: LiteRtDecider? = null
    private var agent: AgentLoop? = null
    private var agentJob: Job? = null

    // ViewModel 은 회전·폴드 펼침에서도 살아남으므로 여기서 한 번만 읽기 시작한다.
    init {
        if (BuildConfig.SHOP_API_KEY.isBlank()) Log.w(TAG, "SHOP_API_KEY 가 비어 있다 — local.properties 에 shop.apiKey 를 넣어야 서버가 응답한다")
        viewModelScope.launch { boot() }
        viewModelScope.launch { listener.state.collect(::onListen) }
        // 장바구니가 바뀔 때마다 파일에 둔다. 말로 바꾸든 터치로 바꾸든 상태 하나만 보면 된다
        viewModelScope.launch {
            _state.map { it.cart }.distinctUntilChanged().drop(1).collect { cart -> withContext(Dispatchers.IO) { cartStore.save(cart) } }
        }
    }

    // ---- 첫 실행: 인트로 → (AI 파일 설치) → 처음 화면 ------------------------------

    /** 인트로는 실제로 준비되는 동안만 보인다. 일부러 늦추지 않는다 */
    private suspend fun boot() {
        when {
            installer.isInstalled() -> {
                loadModel(installer.modelFile)
                setupDone()
            }
            installer.hasDownload() -> watchDownload()
            else -> needModel()
        }
    }

    private fun needModel(error: String? = null) {
        val (connected, unmetered) = installer.network()
        _state.update {
            it.copy(setup = it.setup.copy(
                phase = if (error != null) SetupPhase.FAILED else SetupPhase.NEED, error = error,
                freeBytes = installer.freeBytes(), connected = connected, unmetered = unmetered,
            ))
        }
        say(if (error != null) Scripts.setupFailed(error) else Scripts.setupNeeded())
    }

    /** 설치 안내 화면의 "설치하기"·실패 화면의 "다시 받기" */
    fun installModel() {
        if (!installer.network().first) return needModel("인터넷에 연결되어 있지 않아요. 연결한 뒤 다시 눌러 주세요.")
        if (!installer.enoughSpace()) return needModel("저장 공간이 모자라요. 3GB 이상 비운 뒤 다시 눌러 주세요.")
        installer.start()
        speaker.stop()
        watchDownload()
    }

    /** 받는 동안 쇼핑 화면으로 나간다. 받기는 시스템이 계속하고, 다 받으면 여기서 이어서 AI 를 올린다 */
    fun installLater() {
        _state.update { it.copy(setup = it.setup.copy(later = true)) }
        speakScreen()
    }

    private fun watchDownload() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            _state.update { it.copy(setup = it.setup.copy(phase = SetupPhase.DOWNLOADING, error = null)) }
            var last: Pair<Long, Long>? = null // (시각 ms, 받은 바이트) — 남은 시간 어림용
            var speed = 0.0
            while (true) {
                when (val p = installer.progress()) {
                    is ModelInstaller.Progress.Running -> {
                        val now = System.currentTimeMillis()
                        last?.let { (t, b) -> if (now > t && p.done > b) speed = 0.8 * speed + 0.2 * ((p.done - b) * 1000.0 / (now - t)) }
                        last = now to p.done
                        val eta = if (speed > 0) ((p.total - p.done) / speed).toLong() else null
                        _state.update { it.copy(setup = it.setup.copy(phase = SetupPhase.DOWNLOADING, done = p.done, total = p.total, etaSec = eta, waiting = p.waiting)) }
                    }
                    ModelInstaller.Progress.Finished -> {
                        _state.update { it.copy(setup = it.setup.copy(phase = SetupPhase.VERIFYING, done = it.setup.total)) }
                        if (installer.verifyAndInstall()) {
                            loadModel(installer.modelFile)
                            setupDone()
                        } else {
                            needModel("받은 파일이 온전하지 않아요. 다시 받아 주세요.")
                        }
                        return@launch
                    }
                    is ModelInstaller.Progress.Failed -> {
                        installer.clear()
                        return@launch needModel("받다가 멈췄어요. 연결을 확인하고 다시 받아 주세요.")
                    }
                    ModelInstaller.Progress.Gone -> {
                        installer.clear()
                        return@launch needModel()
                    }
                }
                delay(500)
            }
        }
    }

    private fun setupDone() {
        val wasVisible = !_state.value.setup.later
        _state.update { it.copy(setup = it.setup.copy(phase = SetupPhase.DONE, later = false)) }
        if (wasVisible && _state.value.screen == Screen.Home) say(Scripts.home())
    }

    private suspend fun loadModel(file: File) {
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
            if (s.ai != AiStatus.READY) say(Scripts.aiUnavailable())
            return
        }
        speaker.stop() // TTS 소리가 마이크로 들어가지 않게
        _state.update { it.copy(screen = Screen.Listening, returnTo = s.effectiveScreen, heard = "", lastHeard = "", heardTyped = false, typing = false, agentBusy = false, bubble = "") }
        listener.start()
    }

    /** 처음 화면 예시 문장: 그 글을 말한 것처럼 에이전트에 넘긴다(STT 를 거치지 않음). */
    fun submitText(text: String) = submit(text, typed = false)

    // ---- 글자로 쓰기: 말과 같은 길로 에이전트에 간다(대답도 읽는다) ----------------

    fun openTyping() {
        if (_state.value.agentBusy) return
        speaker.stop()
        _state.update { it.copy(typing = true) }
    }

    fun closeTyping() = _state.update { it.copy(typing = false) }

    fun submitTyped(text: String) {
        if (text.isBlank()) return // 빈 글은 보내지 않는다
        submit(text.trim(), typed = true)
    }

    private fun submit(text: String, typed: Boolean) {
        val s = _state.value
        if (s.ai != AiStatus.READY || s.agentBusy) return
        speaker.stop()
        _state.update {
            it.copy(screen = Screen.Listening, returnTo = s.effectiveScreen, heard = text, lastHeard = text, heardTyped = typed, typing = false, bubble = "")
        }
        runAgent(text)
    }

    fun finishListening() = listener.stop()

    fun cancelListening() {
        listener.cancel()
        // 판단 중에 그만두면 모델 대화에는 행동만 남고 결과가 없다. 다음 턴에 그 사실을 알린다
        if (agentJob?.isActive == true) _state.update { it.copy(notes = it.notes + "앞의 요청은 어르신이 중단했다.") }
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
                say(
                    if (ls.code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) Scripts.aiUnavailable() else Scripts.notHeard(),
                )
            }
            ListenState.Idle, ListenState.Ready -> Unit
        }
    }

    private fun runAgent(heard: String) {
        val a = agent ?: return
        _state.update { it.copy(heard = heard, lastHeard = heard, agentBusy = true, steps = listOf(Step("말씀을 알아듣고 있어요", "말씀을 알아들었어요"))) }
        say(Scripts.thinking())
        agentJob = viewModelScope.launch {
            val before = _state.value
            val turn = try {
                a.handle(
                    heard, before,
                    onStep = { step -> _state.update { it.copy(steps = it.steps + step) } },
                    onCart = { c -> _state.update { it.copy(cart = c) } },
                )
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
            .copy(agentBusy = false, heard = "", steps = emptyList(), bubble = "", missingOption = null)
        when (turn) {
            is AgentTurn.Moved -> {
                _state.value = st
                speakScreen()
                if (st.screen == Screen.Home) viewModelScope.launch { agent?.reset() } // 쇼핑 세션마다 대화를 새로 연다
            }
            is AgentTurn.Said -> {
                _state.value = st.copy(bubble = SpeechText.clean(turn.text))
                say(Scripts.say(turn.text))
            }
            is AgentTurn.Failed -> {
                _state.value = st
                say(
                    when {
                        turn.reason == AgentTurn.Reason.OFFLINE -> Scripts.networkError()
                        st.cartProblem != null -> Scripts.orderBlocked(st.cart.firstOrNull { it.lineId == st.cartProblem })
                        else -> Scripts.agentFailed()
                    },
                )
            }
        }
    }

    // ---- 터치: 에이전트와 같은 도구를 거친다 ---------------------------------

    fun pick(index: Int) {
        val p = _state.value.shown.getOrNull(index) ?: return
        touch(Action.Open(p.id), "화면에서 ${index + 1}번 ${p.name} 상세를 열었다")
    }

    /** 추천 카드의 "담기". 옵션이 있으면 상세를 열어 고르게 한다 */
    fun addShown(index: Int) {
        val p = _state.value.shown.getOrNull(index) ?: return
        touch(Action.CartAdd(p.id), "화면에서 ${p.name}를 장바구니에 담았다")
    }

    /** 상세 화면 옵션 칩. 담기 전까지는 화면에만 둔다 */
    fun chooseOption(name: String, value: String) = _state.update { it.copy(selected = it.selected + (name to value), missingOption = null) }

    fun changeQty(delta: Int) = _state.update { it.copy(qty = (it.qty + delta).coerceIn(1, 9)) }

    /** 상세 화면 "장바구니에 담기" */
    fun addCurrent() {
        val s = _state.value
        val d = s.current ?: return
        touch(Action.CartAdd(d.id, s.qty, s.selected), "화면에서 ${d.name}를 장바구니에 담았다")
    }

    fun changeLineQty(lineId: String, delta: Int) {
        val line = _state.value.cart.firstOrNull { it.lineId == lineId } ?: return
        val count = line.qty + delta
        if (count in 1..9) touch(Action.CartQuantity(lineId, count), "화면에서 ${line.name} 수량을 ${count}개로 바꿨다")
    }

    fun removeLine(lineId: String) {
        val line = _state.value.cart.firstOrNull { it.lineId == lineId } ?: return
        touch(Action.CartRemove(lineId), "화면에서 ${line.name}를 장바구니에서 뺐다")
    }

    /** 주문 내역의 "또 담기". 그때 고른 옵션 그대로 담는다 */
    fun reorder(o: PastOrder) = touch(Action.CartAdd(o.productId, o.qty, o.options), "화면에서 지난번에 산 ${o.name}를 다시 담았다")

    fun openCart() = touch(Action.ShowCart, null)

    fun openHistory() = touch(Action.ShowHistory, null)

    /** 장바구니 "주문하기" */
    fun checkout() = touch(Action.Checkout, null)

    /** 확인 화면 "네, 주문" */
    fun placeOrder() = touch(Action.Place, null)

    /** 확인 화면 "아니요" */
    fun cancelOrder() = touch(Action.Cancel, null)

    /**
     * 터치 동작. 말과 같은 실행기를 거친다. [note] 는 다음 말하기 턴에 모델에게 알릴 사실이다
     * (터치로 한 일은 모델 대화에 남지 않아서, 그대로 두면 "방금 담은 거"를 모른다).
     */
    private fun touch(action: Action, note: String?) {
        val s = _state.value
        if (s.agentBusy) return
        _state.update { it.copy(agentBusy = true) }
        viewModelScope.launch {
            val out = executor.execute(action, s)
            Log.i(TAG, "touch $action ok=${out.ok} screen=${out.state.screen} ${out.result}")
            val notes = if (out.ok && note != null) out.state.notes + note else out.state.notes
            _state.value = out.state.copy(agentBusy = false, bubble = "", missingOption = null, notes = notes)
            when {
                out.ok -> if (out.state.screen != s.screen) speakScreen()
                out.offline -> say(Scripts.networkError())
                // 옵션을 골라야 담을 수 있다: 상세 화면에서 그 옵션을 강조하고 읽어 준다
                action is Action.CartAdd && out.choices.isNotEmpty() -> {
                    if (out.state.current?.id != action.productId) {
                        val opened = executor.execute(Action.Open(action.productId), out.state)
                        _state.value = opened.state.copy(agentBusy = false, notes = notes)
                    }
                    _state.update { it.copy(missingOption = out.choices.keys.first()) }
                    say(Scripts.askOption(out.choices))
                }
                out.state.cartProblem != null -> say(Scripts.orderBlocked(out.state.cart.firstOrNull { it.lineId == out.state.cartProblem }))
                else -> say(Scripts.agentFailed())
            }
        }
    }

    fun stopReading() = speaker.stop()

    /** 읽고, 첫 문장을 대화 띠에 남긴다. 들리는 말과 보이는 말이 같게 한다 */
    private fun say(lines: List<Utterance>) {
        _state.update { it.copy(said = lines.firstOrNull()?.text.orEmpty()) }
        speaker.speak(lines)
    }

    fun openDevMenu() {
        speaker.stop()
        _state.update { it.copy(screen = Screen.DevLlm) }
    }

    /** 화면을 바꾸고 그 화면의 대본을 읽는다. */
    fun go(screen: Screen, speak: Boolean = true) {
        if (screen == Screen.Home) {
            _state.update { it.freshSession() }
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
            Screen.Home -> say(Scripts.home())
            Screen.Results -> say(Scripts.results(s.label, s.shown))
            Screen.Detail -> p?.let { say(Scripts.detail(it, s.selected)) }
            Screen.Cart -> say(Scripts.cart(s.cart))
            Screen.Confirm -> say(Scripts.confirm(s.cart))
            Screen.Done -> say(Scripts.done(s.orderIds.size))
            Screen.History -> say(Scripts.history(s.pastOrders))
            Screen.Listening, Screen.DevLlm -> speaker.stop()
        }
    }

    /** 시스템 뒤로 가기. 처음 화면에서는 false 를 돌려 앱이 닫히게 둔다. */
    fun back(): Boolean {
        val s = _state.value
        if (s.typing) return true.also { closeTyping() } // 입력 칸부터 닫는다
        when (s.screen) {
            Screen.Home -> return false
            Screen.Listening -> cancelListening()
            Screen.Results, Screen.Done, Screen.History, Screen.DevLlm -> go(Screen.Home)
            Screen.Detail -> go(if (s.shown.isEmpty()) Screen.Home else Screen.Results)
            Screen.Cart -> go(if (s.shown.isNotEmpty()) Screen.Results else Screen.Home)
            Screen.Confirm -> go(Screen.Cart)
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
