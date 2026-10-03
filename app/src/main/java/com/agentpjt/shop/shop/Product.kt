package com.agentpjt.shop.shop

/** 목업 상품. 필드 이름은 나중에 붙일 쿠팡 검색 응답(productName, productPrice, isRocket …)에 맞춰 두었다. */
data class Product(
    val name: String,
    val price: Int,
    val shippingFee: Int,
    val isRocket: Boolean,
    val isFreeShipping: Boolean,
    /** 화면 표기. 예: "내일 10월 4일(일)" */
    val arriveLabel: String,
    /** 읽어 줄 때 쓰는 말. 예: "내일", "10월 6일 화요일에" */
    val arriveSpoken: String,
)

// 시안(2026-10-03 기준 날짜)과 같은 값. STT·검색이 붙기 전까지 어떤 말을 해도 이 3개가 나온다.
val MOCK_PRODUCTS = listOf(
    Product("신신파스 아렉스 20매", 9_900, 0, isRocket = true, isFreeShipping = false, "내일 10월 4일(일)", "내일"),
    Product("케토톱 플라스타 34매", 18_500, 3_000, isRocket = false, isFreeShipping = false, "10월 6일(화)", "10월 6일 화요일에"),
    Product("제일 쿨파프 30매", 12_000, 0, isRocket = false, isFreeShipping = true, "10월 5일(월)", "10월 5일 월요일에"),
)

const val MOCK_HEARD = "무릎 아플 때 붙이는 파스 2만 원 이하로 찾아줘"
const val MOCK_KEYWORD = "무릎 파스"
