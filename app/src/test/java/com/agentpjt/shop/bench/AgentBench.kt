package com.agentpjt.shop.bench

import com.agentpjt.shop.agent.AgentLoop
import com.agentpjt.shop.agent.AgentTurn
import com.agentpjt.shop.agent.Decider
import com.agentpjt.shop.agent.ToolExecutor
import com.agentpjt.shop.agent.systemPrompt
import com.agentpjt.shop.api.ApiResult
import com.agentpjt.shop.api.HttpShopApi
import com.agentpjt.shop.shop.AiStatus
import com.agentpjt.shop.shop.Catalog
import com.agentpjt.shop.shop.ShopState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * PC 에이전트 벤치: 앱의 에이전트 코드(지시문·행동 스키마·루프·실행기·서버 호출)를 그대로 쓰고,
 * 모델만 PC 의 다리(tools/agent-bench/bridge.py, 같은 LiteRT-LM·같은 모델 파일)에 묻는다. 폰이 필요 없다.
 *
 * 실행: 다리를 띄운 뒤
 *   $env:AGENT_BENCH='1'; .\gradlew.bat testDebugUnitTest --tests "*AgentBench*"
 * 문장: app/eval/bench.txt (한 줄에 한 세션. " / " 로 나누면 같은 세션의 다음 발화)
 * 결과: app/build/agent-bench.txt
 */
class AgentBench {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.MINUTES).build()
    private val bridge = System.getenv("AGENT_BENCH_URL") ?: "http://127.0.0.1:8765"
    private val out = StringBuilder()

    private fun post(path: String, body: JsonObject): JsonObject {
        val req = Request.Builder().url(bridge + path).post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        http.newCall(req).execute().use { res ->
            val text = res.body.string()
            check(res.isSuccessful) { "bridge $path ${res.code}: $text" }
            return json.parseToJsonElement(text).jsonObject
        }
    }

    /** 앱의 LiteRtDecider 와 같은 일을 다리에 시킨다. 단계마다 보낸 메시지·고른 행동·시간을 남긴다 */
    private inner class BridgeDecider : Decider {
        var tokens = 0
        override suspend fun reset() { post("/reset", buildJsonObject { put("system", systemPrompt()) }) }
        override suspend fun next(message: String, schema: JsonObject): JsonObject {
            val r = post("/next", buildJsonObject { put("message", message); put("schema", schema) })
            val text = r["text"]!!.jsonPrimitive.content
            out.appendLine("    > ${message.lines().first().take(160)}${if (message.lines().size > 1) " …(${message.lines().size}줄)" else ""}")
            out.appendLine("    < ${r["ms"]}ms ${text.replace("\n", "")}")
            return json.parseToJsonElement(text).jsonObject
        }
        override fun tokenCount() = tokens
    }

    @Test
    fun run() = runBlocking {
        assumeTrue("AGENT_BENCH=1 일 때만 돈다(다리가 떠 있어야 한다)", System.getenv("AGENT_BENCH") == "1")
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val props = Properties().apply { File(root, "local.properties").inputStream().use { load(it) } }
        // AGENT_BENCH_API 로 로컬 서버(검색 개선 시험)를 쓸 수 있다
        val api = HttpShopApi(
            System.getenv("AGENT_BENCH_API") ?: props.getProperty("shop.apiBaseUrl", "https://shop-api.bomun.dev"),
            System.getenv("AGENT_BENCH_KEY") ?: props.getProperty("shop.apiKey"),
        )
        val catalog = (api.categories() as? ApiResult.Ok)?.value?.let { Catalog(it) } ?: Catalog()
        val decider = BridgeDecider()
        val loop = AgentLoop(decider, ToolExecutor(api))
        val sessions = File(root, "app/eval/bench.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        sessions.forEachIndexed { i, line ->
            loop.reset()
            var s = ShopState(ai = AiStatus.READY, catalog = catalog)
            line.split(" / ").forEach { heard ->
                out.appendLine("[${i + 1}] \"$heard\"")
                val t0 = System.nanoTime()
                val turn = loop.handle(heard, s)
                s = turn.state.let { st -> if (st.screen == com.agentpjt.shop.shop.Screen.Listening) st.copy(screen = st.returnTo) else st }
                    .copy(returnTo = turn.state.effectiveScreen)
                val result = when (turn) {
                    is AgentTurn.Moved -> "화면=${turn.state.screen} 띄운 상품=${turn.state.shown.map { "${it.name}(${catalog.pathOf(it.sub)})" }} 장바구니=${turn.state.cart.map { it.name + "×" + it.qty }}"
                    is AgentTurn.Said -> "말=\"${turn.text}\""
                    is AgentTurn.Failed -> "실패=${turn.reason}"
                }
                out.appendLine("  = ${(System.nanoTime() - t0) / 1_000_000}ms $result")
            }
        }
        File(root, "app/build/agent-bench.txt").writeText(out.toString())
    }
}
