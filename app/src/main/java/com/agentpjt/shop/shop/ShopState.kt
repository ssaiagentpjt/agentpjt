package com.agentpjt.shop.shop

enum class Screen { Home, Listening, Results, Detail, Confirm, Done, DevLlm }

enum class AiStatus { LOADING, READY, UNAVAILABLE }

/** 시연 사용자. 서버 seed_orders.json 에 지난 구매 5건이 들어 있다. */
const val DEMO_USER_ID = "u001"
const val DEMO_USER_NAME = "홍길순"

/** 이번 쇼핑 세션에서 본 상품(검색 후보·화면·상세·구매 이력). 모델이 id 로 다시 가리킬 수 있는 범위다 */
data class KnownProduct(val id: String, val name: String)

/** 아는 상품은 최근 것부터 이만큼만 둔다. 턴 메시지와 스키마 enum 길이를 묶어 두려는 상한이고, 실측 뒤 조정한다 */
const val MAX_KNOWN = 15

data class ShopState(
    val screen: Screen = Screen.Home,
    /** 최근 search 결과(최대 5개). show 는 이 안의 id 만 받는다 */
    val candidates: List<ProductSummary> = emptyList(),
    /** 추천 화면에 띄운 상품(최대 3개). 모델이 show 로 고른다 */
    val shown: List<ProductSummary> = emptyList(),
    /** 추천 화면 제목에 쓰는 짧은 말. 모델이 정한다 */
    val label: String = "",
    /** 상세·확인·완료 화면의 상품(서버 full) */
    val current: ProductDetail? = null,
    /** 보고 있는 상품에서 고른 옵션 {이름: 값} */
    val selected: Map<String, String> = emptyMap(),
    val qty: Int = 1,
    val orderId: String? = null,
    val orderTotal: Int? = null,
    /** 최근에 본 것이 앞. open·info 가 가리킬 수 있는 id */
    val known: List<KnownProduct> = emptyList(),
    /** 말하기 화면: 받아쓰는 중인 글자 */
    val heard: String = "",
    /** 에이전트가 판단하는 중이거나, 터치 동작이 서버를 기다리는 중 */
    val agentBusy: Boolean = false,
    /** 에이전트가 지금 하는 일(말하기 화면에 보인다). 예: "'두유' 찾고 있어요" */
    val progress: String = "",
    /** 손주야가 화면을 바꾸지 않고 한 말(ask·answer). 말풍선으로 보인다 */
    val bubble: String = "",
    /** 주문하려는데 빠진 옵션 축. 상세 화면에서 강조한다 */
    val missingOption: String? = null,
    /** 말하기를 시작한 화면. 에이전트가 화면을 바꾸지 않으면 여기로 돌아간다 */
    val returnTo: Screen = Screen.Home,
    val ai: AiStatus = AiStatus.LOADING,
    val micDenied: Boolean = false,
    /** 마지막 서버 호출이 네트워크 오류였다(안내 띠) */
    val offline: Boolean = false,
) {
    /** 말하기 중이면 말을 시작한 화면, 아니면 지금 화면 */
    val effectiveScreen: Screen get() = if (screen == Screen.Listening) returnTo else screen

    /** [items] 를 아는 상품 맨 앞에 올린다(중복은 앞으로 옮긴다) */
    fun remember(items: List<KnownProduct>): ShopState {
        val ids = items.map { it.id }.toSet()
        return copy(known = (items + known.filterNot { it.id in ids }).take(MAX_KNOWN))
    }
}
