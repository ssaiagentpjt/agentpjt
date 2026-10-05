package com.agentpjt.shop.agent

import android.util.Log
import com.agentpjt.shop.shop.CartLine
import com.agentpjt.shop.shop.ShopState
import com.agentpjt.shop.shop.Step
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "ShopAgent"

/** 한 발화를 처리한 결과. ViewModel 이 이것을 화면과 음성으로 그린다 */
sealed interface AgentTurn {
    val state: ShopState

    /** 끝맺음 행동으로 화면이 바뀌었다(show·open·show_cart·show_history·checkout·place·cancel·home). 그 화면 대본을 읽는다 */
    data class Moved(override val state: ShopState) : AgentTurn

    /** 손주야가 묻거나(ask) 답했다(answer). 화면은 그대로 두고 그 문장을 보이고 읽는다 */
    data class Said(override val state: ShopState, val text: String) : AgentTurn

    data class Failed(override val state: ShopState, val reason: Reason) : AgentTurn

    /** REJECTED: 끝맺음 행동이 거절됐다(결제가 막힌 줄은 state.cartProblem) */
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

    /**
     * [onStep] 은 관찰 행동을 시작할 때와 그 결과를 받고 다음 행동을 고를 때(찾는 중 화면 단계),
     * [onCart] 는 관찰 행동이 장바구니를 바꿨을 때 부른다
     * (턴이 끝나기 전에도 화면의 장바구니 개수가 바로 바뀌게).
     */
    suspend fun handle(
        heard: String,
        state: ShopState,
        onStep: (Step) -> Unit = {},
        onCart: (List<CartLine>) -> Unit = {},
    ): AgentTurn = lock.withLock {
        if (decider.tokenCount() > maxTokens) {
            Log.i(TAG, "tokens=${decider.tokenCount()} > $maxTokens, 대화를 새로 연다")
            decider.reset()
        }
        val started = System.nanoTime()
        var message = turnMessage(state, heard)
        var s = state.copy(notes = emptyList()) // 메모는 이번 메시지에 붙였다
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
            if (action.kind.observes) onStep(step(action))

            val out = executor.execute(action, s)
            s = out.state
            if (out.offline) return@withLock AgentTurn.Failed(s, AgentTurn.Reason.OFFLINE)

            if (action.kind.observes) {
                if (out.state.cart != state.cart) onCart(out.state.cart)
                onStep(Step("다음 할 일을 고르는 중", "다음 할 일을 골랐어요"))
                message = out.result // 결과(실패 사유 포함)를 사실로 돌려주고 다음 행동은 모델이 고른다
                return@repeat
            }
            Log.i(TAG, "end ${action.kind.wire} ok=${out.ok} screen=${s.screen} total=${elapsed(started)}ms tokens=${decider.tokenCount()}")
            return@withLock if (out.ok) AgentTurn.Moved(s) else AgentTurn.Failed(s, AgentTurn.Reason.REJECTED)
        }
        Log.w(TAG, "step limit $maxSteps total=${elapsed(started)}ms")
        AgentTurn.Failed(s, AgentTurn.Reason.STEP_LIMIT)
    }

    private fun step(a: Action) = when (a) {
        is Action.Search -> Step("'${a.query}' 찾고 있어요", "'${a.query}'를 찾았어요")
        is Action.Info -> Step("상품 정보를 보고 있어요", "상품 정보를 봤어요")
        Action.History -> Step("지난 구매를 보고 있어요", "지난 구매를 봤어요")
        is Action.CartAdd -> Step("장바구니에 담고 있어요", "장바구니를 확인했어요")
        is Action.CartRemove, is Action.CartQuantity -> Step("장바구니를 고치고 있어요", "장바구니를 고쳤어요")
        else -> Step("", "")
    }

    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000
}
