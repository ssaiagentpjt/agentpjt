package com.agentpjt.shop.shop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agentpjt.shop.voice.Speaker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen { Home, Listening, Results, Detail, Confirm, Done, DevLlm }

data class ShopState(
    val screen: Screen = Screen.Home,
    val products: List<Product> = MOCK_PRODUCTS,
    val keyword: String = MOCK_KEYWORD,
    val picked: Int = 0,
    val qty: Int = 1,
    /** 말하기 화면에 지금까지 채워진 받아쓰기 */
    val heard: String = "",
    val searching: Boolean = false,
) {
    val product: Product get() = products[picked]
}

class ShopViewModel(app: Application) : AndroidViewModel(app) {

    val speaker = Speaker(app)

    private val _state = MutableStateFlow(ShopState())
    val state: StateFlow<ShopState> = _state

    private var listenJob: Job? = null

    // ViewModel 은 회전·폴드 펼침에서도 살아남으므로 여기서 한 번만 읽기 시작한다.
    init {
        speaker.speak(Scripts.home())
    }

    /** 말하기 연출. STT 가 붙으면 실제 받아쓰기로 바뀐다. */
    fun startListening() {
        listenJob?.cancel()
        speaker.stop()
        _state.update { it.copy(screen = Screen.Listening, heard = "", searching = false) }
        listenJob = viewModelScope.launch {
            for (i in 1..MOCK_HEARD.length) {
                delay(70)
                _state.update { it.copy(heard = MOCK_HEARD.take(i)) }
            }
            delay(1_500) // 말을 멈추면 1.5초 뒤 자동으로 넘어간다
            search()
        }
    }

    /** "다 말했어요" 또는 자동 진행. Gemma 실측 응답(약 2.5초) 동안 "찾고 있어요"를 보여 준다. */
    fun finishListening() {
        listenJob?.cancel()
        listenJob = viewModelScope.launch { search() }
    }

    private suspend fun search() {
        _state.update { it.copy(heard = MOCK_HEARD, searching = true) }
        speaker.speak(Scripts.searching())
        delay(2_500)
        go(Screen.Results)
    }

    fun pick(index: Int) {
        _state.update { it.copy(picked = index, qty = 1) }
        go(Screen.Detail)
    }

    fun changeQty(delta: Int) = _state.update { it.copy(qty = (it.qty + delta).coerceIn(1, 9)) }

    fun stopReading() = speaker.stop()

    fun openDevMenu() {
        listenJob?.cancel()
        speaker.stop()
        _state.update { it.copy(screen = Screen.DevLlm) }
    }

    /** 화면을 바꾸고 그 화면의 대본을 읽는다. */
    fun go(screen: Screen) {
        if (screen != Screen.Listening) listenJob?.cancel()
        _state.update {
            it.copy(screen = screen, searching = false, qty = if (screen == Screen.Home) 1 else it.qty)
        }
        val s = _state.value
        when (screen) {
            Screen.Home -> speaker.speak(Scripts.home())
            Screen.Listening -> startListening()
            Screen.Results -> speaker.speak(Scripts.results(s.keyword, s.products))
            Screen.Detail -> speaker.speak(Scripts.detail(s.product))
            Screen.Confirm -> speaker.speak(Scripts.confirm(s.product, s.qty))
            Screen.Done -> speaker.speak(Scripts.done(s.product))
            Screen.DevLlm -> speaker.stop()
        }
    }

    /** 시스템 뒤로 가기. 처음 화면에서는 false 를 돌려 앱이 닫히게 둔다. */
    fun back(): Boolean {
        val prev = when (_state.value.screen) {
            Screen.Home -> return false
            Screen.Listening, Screen.Results, Screen.Done, Screen.DevLlm -> Screen.Home
            Screen.Detail -> Screen.Results
            Screen.Confirm -> Screen.Detail
        }
        go(prev)
        return true
    }

    override fun onCleared() {
        speaker.shutdown()
    }
}
