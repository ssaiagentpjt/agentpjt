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
import com.agentpjt.shop.api.ApiResult
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
    private val api = HttpShopApi(BuildConfig.SHOP_API_BASE_URL, BuildConfig.SHOP_API_KEY)
    private val executor = ToolExecutor(api)

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
        loadCatalog()
        when {
            installer.isInstalled() -> {
                loadModel(installer.modelFile)
                setupDone()
            }
            installer.hasDownload() -> watchDownload()
            else -> needModel()
        }
    }

    /** 매장 분류는 대화를 열기 전에 받는다(지시문에 들어간다). 못 받으면 분류 없이 동작한다 */
    private suspend fun loadCatalog() {
        val mains = (api.categories() as? ApiResult.Ok)?.value.orEmpty()
        val needs = (api.needs() as? ApiResult.Ok)?.value.orEmpty().filter { it.productCount > 0 }.map { it.name }
        if (mains.isEmpty()) Log.w(TAG, "categories 를 받지 못했다")
        _state.update { it.copy(catalog = Catalog(mains, needs)) }
    }

    private fun needModel(error: String? = null) {
        val (connected, unmetered) = installer.network()
        _state.update {
            it.copy(setup = it.setup.copy(
                phase = if (error != null) SetupPhase.FAILED else SetupPhase.NEED, error = error,
                freeBytes = installer.freeBytes(), connected = connected, unmetered = unmetered,
            ))
        }
        say(if (error != null) Scripts.setupFailed(error) else Scripts.setupNeeded(), log = false)
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
            val d = LiteRtDecider(e) { systemPrompt(_state.value.catalog) }
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
        logUser(heard, _state.value.heardTyped)
        say(Scripts.thinking(), log = false)
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
                if (st.screen == Screen.Home && returnTo != Screen.Home) logRestart()
                _state.value = st.copy(log = _state.value.log)
                speakScreen()
                if (st.screen == Screen.Home) viewModelScope.launch { agent?.reset() } // 쇼핑 세션마다 대화를 새로 연다
            }
            is AgentTurn.Said -> {
                _state.value = st.copy(bubble = SpeechText.clean(turn.text))
                say(Scripts.say(turn.text), ask = true)
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

    private var msgId = 0L

    /**
     * 읽고, 첫 문장을 대화 띠와 처음 화면 대화 기록에 남긴다. 들리는 말과 보이는 말이 같게 한다.
     * [link] 는 그 말풍선에서 돌아갈 화면, [ask] 는 묻거나 답하는 말(노란 말풍선), [log] 가 false 면 기록에 남기지 않는다(“알아볼게요” 같은 추임새).
     */
    private fun say(lines: List<Utterance>, link: ChatLink? = null, ask: Boolean = false, log: Boolean = true) {
        val first = lines.firstOrNull()?.text.orEmpty()
        _state.update {
            val s = it.copy(said = first)
            if (log && first.isNotEmpty()) s.withMessage(ChatMsg(++msgId, ChatMsg.From.AI, first, ask = ask, link = link)) else s
        }
        speaker.speak(lines)
    }

    private fun logUser(text: String, typed: Boolean) =
        _state.update { it.withMessage(ChatMsg(++msgId, ChatMsg.From.USER, text, typed = typed)) }

    /** 처음으로 돌아갈 때: 모델 대화가 새로 열리니 기록에 구분선을 둔다(보이는 기록과 모델 기억이 다르다는 것을 숨기지 않는다) */
    private fun logRestart() = _state.update { it.withMessage(ChatMsg(++msgId, ChatMsg.From.SYSTEM, "새로 시작했어요")) }

    fun openHelp() {
        speaker.stop()
        _state.update { it.copy(helpOpen = true) }
    }

    fun closeHelp() = _state.update { it.copy(helpOpen = false) }

    /** 도움말 예시를 누르면 시트를 닫고 그 말로 시작한다 */
    fun tryExample(text: String) {
        closeHelp()
        submitText(text)
    }

    /** 말풍선의 "다시 보기". 추천은 그때 목록을 그대로 다시 연다(서버를 다시 부르지 않는다) */
    fun openLink(link: ChatLink) {
        when (link) {
            is ChatLink.Results -> {
                _state.update { it.copy(screen = Screen.Results, shown = link.products, label = link.label, bubble = "") }
                speakScreen()
            }
            is ChatLink.Product -> touch(Action.Open(link.id), null)
            ChatLink.Cart -> touch(Action.ShowCart, null)
            ChatLink.History -> touch(Action.ShowHistory, null)
        }
    }

    /**
     * 개발용 일괄 시험(BuildConfig.DEV_HOOKS). 앱 폴더의 eval.txt 한 줄에 문장 하나.
     * 문장마다 처음 화면으로 새 세션을 열고 실제 에이전트 루프를 돌린 뒤 끝나기를 기다린다. 결과는 ShopAgent·ShopApi 로그에 남는다.
     */
    fun runDevEval() {
        if (!BuildConfig.DEV_HOOKS) return
        val file = File(getApplication<Application>().getExternalFilesDir(null), "eval.txt")
        val lines = file.takeIf { it.exists() }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        viewModelScope.launch {
            while (_state.value.ai != AiStatus.READY) delay(500)
            lines.forEachIndexed { i, line ->
                go(Screen.Home, speak = false)
                delay(1500) // 대화 재시작(reset)이 끝나기를 기다린다
                Log.i(TAG, "EVAL ${i + 1}/${lines.size} \"$line\"")
                submitText(line)
                delay(300)
                while (_state.value.agentBusy) delay(300)
                val s = _state.value
                Log.i(TAG, "EVAL-END ${i + 1} screen=${s.screen} shown=${s.shown.map { it.name }} said=\"${s.bubble.ifEmpty { s.said }}\"")
                speaker.stop()
            }
            Log.i(TAG, "EVAL-DONE ${lines.size}")
        }
    }

    fun openDevMenu() {
        speaker.stop()
        _state.update { it.copy(screen = Screen.DevLlm) }
    }

    /** 화면을 바꾸고 그 화면의 대본을 읽는다. */
    fun go(screen: Screen, speak: Boolean = true) {
        if (screen == Screen.Home) {
            if (_state.value.screen != Screen.Home) logRestart()
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
            Screen.Results -> say(Scripts.results(s.label, s.shown), link = ChatLink.Results(s.label, s.shown))
            Screen.Detail -> p?.let { say(Scripts.detail(it, s.selected), link = ChatLink.Product(it.id, it.name)) }
            Screen.Cart -> say(Scripts.cart(s.cart), link = ChatLink.Cart)
            Screen.Confirm -> say(Scripts.confirm(s.cart))
            Screen.Done -> say(Scripts.done(s.orderIds.size))
            Screen.History -> say(Scripts.history(s.pastOrders), link = ChatLink.History)
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
