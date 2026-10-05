package com.agentpjt.shop.agent

import android.util.Log
import com.agentpjt.shop.llm.GemmaEngine
import com.agentpjt.shop.shop.Catalog
import com.agentpjt.shop.shop.ShopState
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "ShopAgent"

/** 한 발화당 모델 왕복 상한. 도구를 계속 부르며 빠져나오지 못하는 경우를 막는다. */
private const val MAX_STEPS = 4

/**
 * 수동 에이전트 루프. 모델이 도구를 고르면 ToolExecutor 로 실행하고 결과를 돌려준다.
 * 화면을 바꾸는 도구가 실행되면 바로 돌아가고(읽기는 화면 대본이 맡는다), 도구 결과 전달은 뒤에서 마저 끝낸다.
 *
 * Conversation 은 동시에 쓰면 안 되므로 reset·handle 은 [lock] 으로 순서를 지킨다.
 */
class ShopAgent(private val engine: GemmaEngine, private val catalog: Catalog, private val scope: CoroutineScope) {

    data class Reply(val state: ShopState, val say: String?, val navigated: Boolean)

    private val executor = ToolExecutor(catalog)
    private var conversation: Conversation? = null
    private var tail: Job? = null
    private val lock = Mutex()

    /** 대화를 새로 연다. 쇼핑 세션 시작(처음 화면)마다 부른다. */
    suspend fun reset(thinking: Boolean) {
        conversation?.cancelProcess() // 진행 중인 생성을 먼저 끊어야 lock 을 오래 기다리지 않는다
        lock.withLock { withContext(Dispatchers.IO) { resetLocked(thinking) } }
    }

    private suspend fun resetLocked(thinking: Boolean) {
        conversation?.let { tail?.join(); it.close() }
        tail = null
        conversation = engine.newConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt(catalog.categories())),
                tools = listOf(tool(ShopTools())),
                automaticToolCalling = false,
                thinkingConfig = ThinkingConfig(enableThinking = thinking, thinkingTokenBudget = 256),
            ),
        )
        Log.i(TAG, "reset thinking=$thinking")
    }

    /** 사용자 발화 하나를 처리한다. [state] 는 말하기를 시작한 시점의 화면 상태. */
    suspend fun handle(heard: String, state: ShopState): Reply = lock.withLock { withContext(Dispatchers.IO) { handleLocked(heard, state) } }

    private suspend fun handleLocked(heard: String, state: ShopState): Reply {
        tail?.join() // 앞 발화의 도구 결과 전달이 끝나야 대화를 이어 쓸 수 있다
        val conv = checkNotNull(conversation) { "reset() 을 먼저 불러야 한다" }
        val started = System.nanoTime()
        var s = state
        var msg = Message.user("${describeScreen(s)}\n$heard")
        Log.i(TAG, "heard \"$heard\" ${describeScreen(s)}")

        repeat(MAX_STEPS) { step ->
            val t0 = System.nanoTime()
            val resp = conv.sendMessage(msg)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val text = resp.text()
            val calls = resp.toolCalls

            if (calls.isEmpty()) {
                Log.i(TAG, "step=$step ${ms}ms text \"$text\" total=${elapsed(started)}ms tokens=${conv.getTokenCount()}")
                return Reply(s, text.ifBlank { null }, navigated = false)
            }

            var navigated = false
            val responses = calls.map { call ->
                val out = executor.execute(call.name, call.arguments, s)
                s = out.state
                navigated = navigated || out.navigated
                Log.i(TAG, "step=$step ${ms}ms tool ${call.name}${call.arguments} -> ${out.response}")
                Content.ToolResponse(call.name, out.response)
            }
            msg = Message.tool(Contents.of(responses))

            if (navigated) {
                // 화면이 바뀌었으면 사용자를 기다리게 하지 않는다. 모델의 마무리 응답은 뒤에서 받아 로그만 남긴다.
                val toolMsg = msg
                tail = scope.launch(Dispatchers.IO) {
                    runCatching { conv.sendMessage(toolMsg) }
                        .onSuccess { Log.i(TAG, "tail text \"${it.text()}\" calls=${it.toolCalls.map { c -> c.name }}") }
                        .onFailure { Log.w(TAG, "tail failed", it) }
                }
                Log.i(TAG, "navigated -> ${s.screen} total=${elapsed(started)}ms")
                return Reply(s, null, navigated = true)
            }
        }
        Log.w(TAG, "step limit reached total=${elapsed(started)}ms")
        return Reply(s, null, navigated = false)
    }

    fun close() {
        conversation?.cancelProcess()
        conversation?.close()
        conversation = null
    }

    private fun Message.text(): String = contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000
}
