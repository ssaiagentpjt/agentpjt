package com.agentpjt.shop.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

enum class BackendKind { GPU, CPU }

/** LiteRT-LM 엔진 하나와 대화 하나를 들고 있는 얇은 래퍼. 스레드 안전하지 않다 — ViewModel 한 곳에서만 쓴다. */
class GemmaEngine(private val modelFile: File, private val cacheDir: File) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    val isLoaded: Boolean get() = conversation != null

    /** 모델을 올리고 걸린 시간(ms)을 돌려준다. 공식 문서상 initialize() 는 최대 10초가량 걸린다. */
    suspend fun load(kind: BackendKind): Long = withContext(Dispatchers.IO) {
        close()
        val start = System.nanoTime()
        val backend = when (kind) {
            BackendKind.GPU -> Backend.GPU()
            BackendKind.CPU -> Backend.CPU()
        }
        val e = Engine(EngineConfig(modelPath = modelFile.absolutePath, backend = backend, cacheDir = cacheDir.absolutePath))
        try {
            e.initialize()
            conversation = e.createConversation(ConversationConfig())
            engine = e
        } catch (t: Throwable) {
            e.close()
            throw t
        }
        (System.nanoTime() - start) / 1_000_000
    }

    /** 대화 맥락을 비운다. 프리셋끼리 서로 영향을 주지 않게 매 실행 전에 부른다. */
    suspend fun resetConversation() = withContext(Dispatchers.IO) {
        val e = engine ?: return@withContext
        conversation?.close()
        conversation = e.createConversation(ConversationConfig())
    }

    /** 응답을 조각 단위로 흘려보낸다. */
    fun generate(prompt: String): Flow<String> {
        val c = checkNotNull(conversation) { "모델이 로드되지 않았다" }
        return c.sendMessageAsync(prompt)
            .map { msg -> msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text } }
            .flowOn(Dispatchers.IO)
    }

    /** 지금까지 대화에 쌓인 토큰 수. 생성 전후 차이로 초당 토큰을 계산한다. */
    fun tokenCount(): Int = conversation?.getTokenCount() ?: 0

    fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }
}
