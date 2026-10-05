package com.agentpjt.shop.shop

enum class Screen { Home, Listening, Results, Detail, Confirm, Done, DevLlm }

enum class AiStatus { LOADING, READY, UNAVAILABLE }

data class ShopState(
    val screen: Screen = Screen.Home,
    /** 추천 화면에 띄운 상품(최대 3개). 모델이 show_products 로 고른다. */
    val shown: List<Product> = emptyList(),
    /** 추천 화면 제목에 쓰는 짧은 말. 예: "무릎 파스". 모델이 정한다. */
    val label: String = "",
    /** 상세·확인·완료 화면의 상품 */
    val current: Product? = null,
    val qty: Int = 1,
    val orderId: String? = null,
    /** 말하기 화면: 받아쓰는 중인 글자 */
    val heard: String = "",
    /** 말하기 화면: 에이전트가 판단하는 중 */
    val agentBusy: Boolean = false,
    /** 말하기를 시작한 화면. 에이전트가 화면을 바꾸지 않으면 여기로 돌아간다. */
    val returnTo: Screen = Screen.Home,
    val ai: AiStatus = AiStatus.LOADING,
    val thinkingMode: Boolean = false,
    val micDenied: Boolean = false,
)
