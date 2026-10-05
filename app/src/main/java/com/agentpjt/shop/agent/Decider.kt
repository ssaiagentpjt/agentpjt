package com.agentpjt.shop.agent

import android.util.Log
import com.agentpjt.shop.llm.GemmaEngine
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ResponseFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private const val TAG = "ShopAgent"

/**
 * 모델에게 메시지 하나를 보내고 행동 JSON 하나를 받는다. 출력은 [schema] 밖으로 나갈 수 없다(ResponseFormat 제약 디코딩).
 * 대화는 이어진다 — 앞 메시지와 응답이 맥락으로 남는다. 테스트에서는 가짜로 바꿔 끼운다.
 */
interface Decider {
    /** 대화를 새로 연다. 쇼핑 세션이 시작될 때(처음 화면) 부른다. */
    suspend fun reset()

    suspend fun next(message: String, schema: JsonObject): JsonObject

    /** 대화에 쌓인 토큰 수. 임계값을 넘으면 reset 한다. */
    fun tokenCount(): Int
}

class LiteRtDecider(private val engine: GemmaEngine, private val systemPrompt: () -> String) : Decider {

    private var conversation: Conversation? = null

    override suspend fun reset() = withContext(Dispatchers.IO) {
        conversation?.close()
        // 생각 모드는 넣지 않는다: thinking budget 과 ResponseFormat 을 함께 쓰면 매 턴 실패한다(LiteRT-LM #3463)
        conversation = engine.newConversation(
            ConversationConfig(systemInstruction = Contents.of(systemPrompt()), enableResponseFormat = true),
        )
    }

    override suspend fun next(message: String, schema: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val conv = conversation ?: run { reset(); checkNotNull(conversation) }
        val t0 = System.nanoTime()
        val resp = conv.sendMessage(Message.user(message), responseFormat = ResponseFormat.json(schema.toString()))
        val text = resp.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
        Log.i(TAG, "decide ${(System.nanoTime() - t0) / 1_000_000}ms tokens=${conv.getTokenCount()} -> $text")
        Json.parseToJsonElement(text).jsonObject
    }

    override fun tokenCount(): Int = conversation?.getTokenCount() ?: 0

    fun close() {
        conversation?.close()
        conversation = null
    }
}
