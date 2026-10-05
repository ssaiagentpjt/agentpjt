package com.agentpjt.shop.agent

import android.util.Log
import com.agentpjt.shop.shop.ShopState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "ShopAgent"

/** 한 발화를 처리한 결과. ViewModel 이 이것을 화면과 음성으로 그린다 */
sealed interface AgentTurn {
    val state: ShopState

    /** 화면이 바뀌었거나(show·open·order·place·cancel·home) 같은 화면이 갱신됐다(option·quantity). 그 화면 대본을 읽는다 */
    data class Moved(override val state: ShopState) : AgentTurn

    /** 주문하려는데 옵션이 빠졌다. 상세 화면에서 그 옵션을 고르게 한다 */
    data class NeedOption(override val state: ShopState, val choices: Map<String, List<String>>) : AgentTurn

    /** 손주야가 묻거나(ask) 답했다(answer). 화면은 그대로 두고 그 문장을 보이고 읽는다 */
    data class Said(override val state: ShopState, val text: String) : AgentTurn

    data class Failed(override val state: ShopState, val reason: Reason) : AgentTurn

    enum class Reason { OFFLINE, STEP_LIMIT, ENGINE, REJECTED }
}

/**
 * 출력을 묶은 에이전트 루프. 한 발화 안에서 모델이 결과를 보고 다음 행동을 고른다 — 순서는 모델이 정한다.
 * 앱이 하는 일: 매 단계 고를 수 있는 행동을 상태로 계산해 스키마로 주고(Actions), 고른 행동을 실행하고(ToolExecutor),
 * 끝맺음 행동이 나오면 화면에 넘긴다. Decider 는 대화를 하나만 들고 있어서 handle·reset 은 [lock] 으로 순서를 지킨다.
 */
class AgentLoop(
    private val decider: Decider,
    private val executor: ToolExecutor,
    private val maxSteps: Int = 4,
    /** 대화 토큰이 이만큼을 넘으면 다음 발화 전에 새로 연다. 상태가 사실을 다 들고 있어서 이어 갈 수 있다 */
    private val maxTokens: Int = 3000,
) {
    private val lock = Mutex()

    suspend fun reset() = lock.withLock { decider.reset() }

    /** [onProgress] 는 관찰 행동을 시작할 때 부른다(말하기 화면 진행 표시). */
    suspend fun handle(heard: String, state: ShopState, onProgress: (String) -> Unit = {}): AgentTurn = lock.withLock {
        if (decider.tokenCount() > maxTokens) {
            Log.i(TAG, "tokens=${decider.tokenCount()} > $maxTokens, 대화를 새로 연다")
            decider.reset()
        }
        val started = System.nanoTime()
        var s = state
        var message = turnMessage(s, heard)
        Log.i(TAG, "heard \"$heard\" | ${message.replace("\n", " | ")}")

        repeat(maxSteps) { step ->
            val json = try {
                decider.next(message, Actions.schema(s))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "step=$step decide failed", t)
                return@withLock AgentTurn.Failed(s, AgentTurn.Reason.ENGINE)
            }
            val action = Actions.parse(json, s)
            if (action == null) {
                Log.w(TAG, "step=$step 스키마 밖 출력 $json")
                return@withLock AgentTurn.Failed(s, AgentTurn.Reason.ENGINE)
            }
            Log.i(TAG, "step=$step $action total=${elapsed(started)}ms")

            when (action) {
                is Action.Ask -> return@withLock AgentTurn.Said(s, action.question)
                is Action.Answer -> return@withLock AgentTurn.Said(s, action.text)
                else -> Unit
            }
            if (action.kind.observes) onProgress(progressText(action))

            val out = executor.execute(action, s)
            s = out.state
            if (out.offline) return@withLock AgentTurn.Failed(s, AgentTurn.Reason.OFFLINE)

            if (action.kind.observes) {
                message = out.result // 결과(실패 사유 포함)를 사실로 돌려주고 다음 행동은 모델이 고른다
                return@repeat
            }
            Log.i(TAG, "end ${action.kind.wire} ok=${out.ok} screen=${s.screen} total=${elapsed(started)}ms tokens=${decider.tokenCount()}")
            return@withLock when {
                out.ok -> AgentTurn.Moved(s)
                out.choices.isNotEmpty() -> AgentTurn.NeedOption(s, out.choices)
                else -> AgentTurn.Failed(s, AgentTurn.Reason.REJECTED)
            }
        }
        Log.w(TAG, "step limit $maxSteps total=${elapsed(started)}ms")
        AgentTurn.Failed(s, AgentTurn.Reason.STEP_LIMIT)
    }

    private fun progressText(a: Action) = when (a) {
        is Action.Search -> "'${a.query}' 찾고 있어요"
        is Action.Info -> "상품 정보를 보고 있어요"
        Action.History -> "지난 구매를 보고 있어요"
        else -> ""
    }

    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000
}
