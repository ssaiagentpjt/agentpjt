package com.agentpjt.shop.ui

import android.app.Application
import android.os.Debug
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agentpjt.shop.llm.BackendKind
import com.agentpjt.shop.llm.GemmaEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "GemmaTest"

data class Preset(val label: String, val prompt: String)

// 다음 단계 쇼핑 에이전트에서 실제로 시킬 일을 미리 시켜본다.
val PRESETS = listOf(
    Preset(
        "의도→JSON",
        """
        너는 노인용 쇼핑 앱의 도우미다. 사용자 말을 아래 JSON 하나로만 바꿔라. 설명은 쓰지 마라.
        {"action":"search","keyword":"<검색어>","maxPrice":<원 단위 숫자 또는 null>}
        사용자: 무릎 아플 때 붙이는 파스 2만원 이하로 찾아줘
        """.trimIndent(),
    ),
    Preset(
        "서수 이해",
        """
        화면에 상품이 이렇게 떠 있다.
        1. 신신파스 아렉스 20매 9,900원
        2. 케토톱 플라스타 34매 18,500원
        3. 제일 쿨파프 30매 12,000원
        사용자: 아까 두 번째 거 주문해줘
        사용자가 고른 상품 번호와 상품명을 {"action":"order","index":<번호>,"name":"<상품명>"} JSON 하나로만 답해라.
        """.trimIndent(),
    ),
    Preset(
        "상품 소개",
        """
        70대 어르신께 아래 상품 3개를 소리 내어 읽어드릴 거다. 쉬운 말로, 상품마다 한 문장씩, 가격은 "만 이천 원"처럼 읽기 쉽게 써라.
        1. 신신파스 아렉스 20매 9,900원 로켓배송
        2. 케토톱 플라스타 34매 18,500원
        3. 제일 쿨파프 30매 12,000원 무료배송
        """.trimIndent(),
    ),
)

data class UiState(
    val modelFiles: List<File> = emptyList(),
    val selectedModel: File? = null,
    val backend: BackendKind = BackendKind.GPU,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val loadMs: Long? = null,
    val generating: Boolean = false,
    val output: String = "",
    val metrics: String = "",
    val error: String? = null,
)

class LlmTestViewModel(app: Application) : AndroidViewModel(app) {

    /** adb push 대상 폴더: /sdcard/Android/data/<패키지>/files */
    val modelDir: File = app.getExternalFilesDir(null) ?: app.filesDir

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var engine: GemmaEngine? = null
    private var genJob: Job? = null

    init {
        refreshModels()
    }

    fun refreshModels() {
        val files = modelDir.listFiles { f -> f.extension == "litertlm" }?.sortedBy { it.name }.orEmpty()
        _state.update { s -> s.copy(modelFiles = files, selectedModel = s.selectedModel?.takeIf { it in files } ?: files.firstOrNull()) }
    }

    fun selectModel(f: File) = _state.update { it.copy(selectedModel = f) }

    fun selectBackend(b: BackendKind) = _state.update { it.copy(backend = b) }

    fun load() {
        val s = _state.value
        val model = s.selectedModel ?: return
        genJob?.cancel()
        engine?.close()
        _state.update { it.copy(loading = true, loaded = false, loadMs = null, error = null) }
        viewModelScope.launch {
            val e = GemmaEngine(model, getApplication<Application>().cacheDir)
            try {
                val ms = e.load(s.backend)
                engine = e
                Log.i(TAG, "load model=${model.name} backend=${s.backend} ms=$ms ${memory()}")
                _state.update { it.copy(loading = false, loaded = true, loadMs = ms) }
            } catch (t: Throwable) {
                Log.e(TAG, "load failed model=${model.name} backend=${s.backend}", t)
                _state.update { it.copy(loading = false, error = "로드 실패: ${t.message ?: t::class.simpleName}") }
            }
        }
    }

    fun run(prompt: String, label: String) {
        val e = engine ?: return
        if (prompt.isBlank()) return
        genJob?.cancel()
        _state.update { it.copy(generating = true, output = "", metrics = "", error = null) }
        genJob = viewModelScope.launch {
            try {
                e.resetConversation()
                val tokensBefore = e.tokenCount()
                val start = System.nanoTime()
                var firstMs: Long? = null
                val sb = StringBuilder()
                e.generate(prompt).collect { chunk ->
                    if (firstMs == null && chunk.isNotEmpty()) firstMs = (System.nanoTime() - start) / 1_000_000
                    sb.append(chunk)
                    _state.update { it.copy(output = sb.toString()) }
                }
                val totalMs = (System.nanoTime() - start) / 1_000_000
                val tokens = e.tokenCount() - tokensBefore
                val decodeSec = (totalMs - (firstMs ?: 0)).coerceAtLeast(1) / 1000.0
                val metrics = "첫 토큰 ${firstMs ?: "-"}ms · 전체 ${totalMs}ms · 대화 토큰 +$tokens · " +
                    "출력 ${sb.length}자 (%.1f자/s) · ${memory()}".format(sb.length / decodeSec)
                Log.i(TAG, "run preset=$label ttft=${firstMs}ms total=${totalMs}ms tokens=$tokens chars=${sb.length} ${memory()}")
                Log.i(TAG, "output preset=$label: $sb")
                _state.update { it.copy(generating = false, metrics = metrics) }
            } catch (t: Throwable) {
                Log.e(TAG, "run failed preset=$label", t)
                _state.update { it.copy(generating = false, error = "생성 실패: ${t.message ?: t::class.simpleName}") }
            }
        }
    }

    private fun memory(): String {
        val rt = Runtime.getRuntime()
        val javaMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
        val nativeMb = Debug.getNativeHeapAllocatedSize() / 1_048_576
        return "javaHeap=${javaMb}MB nativeHeap=${nativeMb}MB"
    }

    override fun onCleared() {
        genJob?.cancel()
        engine?.close()
    }
}
