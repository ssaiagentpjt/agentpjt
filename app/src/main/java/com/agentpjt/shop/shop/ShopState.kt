package com.agentpjt.shop.shop

enum class Screen { Home, Listening, Results, Detail, Cart, Confirm, Done, History, DevLlm }

enum class AiStatus { LOADING, READY, UNAVAILABLE }

/** 첫 실행 단계. DONE 이 되기 전에는 쇼핑 화면 대신 인트로·설치 화면을 띄운다(나중에 받기를 고르면 [Setup.later]) */
enum class SetupPhase { BOOT, NEED, DOWNLOADING, VERIFYING, FAILED, DONE }

data class Setup(
    val phase: SetupPhase = SetupPhase.BOOT,
    val done: Long = 0,
    val total: Long = 0,
    /** 남은 시간(초). 받는 속도로 어림한다 */
    val etaSec: Long? = null,
    /** 연결을 기다리는 중(시스템 다운로더가 일시 정지) */
    val waiting: Boolean = false,
    val freeBytes: Long = 0,
    val connected: Boolean = true,
    /** Wi-Fi 등 요금이 안 드는 연결 */
    val unmetered: Boolean = true,
    val error: String? = null,
    /** 받는 동안 쇼핑 화면으로 나갔다(받기는 계속된다) */
    val later: Boolean = false,
)

/** 시연 사용자. 서버 seed_orders.json 에 지난 구매 5건이 들어 있다. */
const val DEMO_USER_ID = "u001"
const val DEMO_USER_NAME = "홍길순"

/**
 * 이번 쇼핑 세션에서 본 상품(검색 후보·화면·상세·구매 이력). 모델이 id 로 다시 가리킬 수 있는 범위다.
 * [options] 는 아는 경우의 옵션 축 → 고를 수 있는 값. 장바구니에 담을 때 스키마 enum 이 된다.
 */
data class KnownProduct(val id: String, val name: String, val options: Map<String, List<String>> = emptyMap())

/** 처음 화면 대화 기록의 말풍선. 손주야(AI)는 왼쪽, 어르신(USER)은 오른쪽, SYSTEM 은 가운데 구분선 */
data class ChatMsg(
    val id: Long,
    val from: From,
    val text: String,
    val typed: Boolean = false,
    val ask: Boolean = false,
    val link: ChatLink? = null,
) {
    enum class From { AI, USER, SYSTEM }
}

/** 손주야가 화면을 바꿨던 말풍선에서 그 화면으로 돌아가는 길. 추천은 그때 목록을 그대로 담는다 */
sealed interface ChatLink {
    data class Results(val label: String, val products: List<ProductSummary>) : ChatLink
    data class Product(val id: String, val name: String) : ChatLink
    data object Cart : ChatLink
    data object History : ChatLink
}

/** 대화 기록은 최근 이만큼만 둔다(메모리에만, 앱을 끄면 사라진다) */
const val MAX_LOG = 50

/** 에이전트가 한 단계에서 하는 일. 찾는 중 화면에 쌓인다(앞 단계는 done 글로 보인다) */
data class Step(val doing: String, val done: String)

/** 화면에 띄웠던 목록. "아까 처음 나온 거"처럼 앞 목록을 가리킬 근거다 */
data class ShownList(val label: String, val items: List<KnownProduct>)

/** 주문 내역 화면의 한 줄(서버 구매 이력) */
data class PastOrder(val date: String, val productId: String, val name: String, val qty: Int, val options: Map<String, String>, val total: Int)

/** 아는 상품은 최근 것부터 이만큼만 둔다. 턴 메시지와 스키마 enum 길이를 묶어 두려는 상한이고, 실측 뒤 조정한다 */
const val MAX_KNOWN = 15

/** 앞서 띄운 목록은 이만큼만 기억한다 */
const val MAX_LISTS = 3

data class ShopState(
    val screen: Screen = Screen.Home,
    /** 최근 search 결과(최대 5개). show 는 이 안의 id 만 받는다 */
    val candidates: List<ProductSummary> = emptyList(),
    /** 추천 화면에 띄운 상품(최대 3개). 모델이 show 로 고른다 */
    val shown: List<ProductSummary> = emptyList(),
    /** 추천 화면 제목에 쓰는 짧은 말. 모델이 정한다 */
    val label: String = "",
    /** 최근 띄운 목록(최신이 앞). shown 이 바뀌어도 남는다 */
    val lists: List<ShownList> = emptyList(),
    /** 상세 화면의 상품(서버 full) */
    val current: ProductDetail? = null,
    /** 상세 화면에서 터치로 고른 옵션·수량. "담기"를 누를 때 쓴다 */
    val selected: Map<String, String> = emptyMap(),
    val qty: Int = 1,
    /** 장바구니. 앱 파일에 저장되고 처음 화면으로 가도 남는다(ShopViewModel) */
    val cart: List<CartLine> = emptyList(),
    /** 결제가 막힌 줄. 장바구니 화면에서 강조한다 */
    val cartProblem: String? = null,
    /** 주문 내역 화면 */
    val pastOrders: List<PastOrder> = emptyList(),
    val orderIds: List<String> = emptyList(),
    val orderTotal: Int? = null,
    /** 최근에 본 것이 앞. open·info·cart_add 가 가리킬 수 있는 id */
    val known: List<KnownProduct> = emptyList(),
    /** 다음 턴 메시지 앞에 붙일 사실(터치로 한 일, 중단한 요청). 붙인 뒤 비운다 */
    val notes: List<String> = emptyList(),
    /** 말하기 화면: 받아쓰는 중인 글자 */
    val heard: String = "",
    /** 에이전트가 판단하는 중이거나, 터치 동작이 서버를 기다리는 중 */
    val agentBusy: Boolean = false,
    /** 이번 발화에서 에이전트가 거친 단계. 마지막이 지금 하는 일이다. 예: "'두유' 찾고 있어요" */
    val steps: List<Step> = emptyList(),
    /** 대화 띠: 마지막으로 들은 말(다음에 말하기 시작할 때까지 남는다) */
    val lastHeard: String = "",
    /** 마지막 말이 글자로 쓴 것이다(대화 띠에 "쓴 말"로 보인다) */
    val heardTyped: Boolean = false,
    /** 글자로 쓰기 입력 칸이 열려 있다 */
    val typing: Boolean = false,
    /** 처음 화면 대화 기록 */
    val log: List<ChatMsg> = emptyList(),
    /** "?" 도움말 시트가 열려 있다 */
    val helpOpen: Boolean = false,
    /** 대화 띠: 손주야가 마지막으로 한 말(앱 대본의 첫 문장, 또는 ask·answer) */
    val said: String = "",
    /** 손주야가 화면을 바꾸지 않고 한 말(ask·answer). 말풍선으로 보인다 */
    val bubble: String = "",
    /** 담으려는데 빠진 옵션 축. 상세 화면에서 강조한다 */
    val missingOption: String? = null,
    /** 말하기를 시작한 화면. 에이전트가 화면을 바꾸지 않으면 여기로 돌아간다 */
    val returnTo: Screen = Screen.Home,
    val ai: AiStatus = AiStatus.LOADING,
    val setup: Setup = Setup(),
    val micDenied: Boolean = false,
    /** 마지막 서버 호출이 네트워크 오류였다(안내 띠) */
    val offline: Boolean = false,
) {
    /** 말하기 중이면 말을 시작한 화면, 아니면 지금 화면 */
    val effectiveScreen: Screen get() = if (screen == Screen.Listening) returnTo else screen

    /** [items] 를 아는 상품 맨 앞에 올린다. 이미 알던 상품은 앞으로 옮기고, 옵션 정보는 아는 쪽을 남긴다 */
    fun remember(items: List<KnownProduct>): ShopState {
        val old = known.associateBy { it.id }
        val merged = items.map { k -> if (k.options.isEmpty() && old[k.id]?.options?.isNotEmpty() == true) k.copy(options = old.getValue(k.id).options) else k }
        val ids = merged.map { it.id }.toSet()
        return copy(known = (merged + known.filterNot { it.id in ids }).take(MAX_KNOWN))
    }

    /** 처음 화면으로: 세션 상태는 비우고 장바구니와 앱 상태만 남긴다 */
    fun freshSession(): ShopState = ShopState(ai = ai, micDenied = micDenied, cart = cart, setup = setup, log = log)

    /** 말풍선을 쌓는다. 바로 앞과 같은 손주야 말·구분선은 다시 쌓지 않는다(같은 화면 대본이 되풀이될 때). 어르신 말은 늘 쌓는다 */
    fun withMessage(msg: ChatMsg): ShopState {
        val last = log.lastOrNull()
        if (msg.from != ChatMsg.From.USER && last != null && last.from == msg.from && last.text == msg.text) return this
        if (msg.from == ChatMsg.From.SYSTEM && (last == null || last.from == ChatMsg.From.SYSTEM)) return this // 빈 기록 맨 앞이나 연속 구분선은 두지 않는다
        return copy(log = (log + msg).takeLast(MAX_LOG))
    }
}

fun ProductSummary.known() = KnownProduct(id, name, options)
fun ProductDetail.known() = KnownProduct(id, name, options.associate { it.name to it.available })
