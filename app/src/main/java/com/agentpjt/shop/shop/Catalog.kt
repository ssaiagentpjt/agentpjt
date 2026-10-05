package com.agentpjt.shop.shop

/**
 * 상품 출처. 지금은 앱 안 목업이고, 쇼핑 검색 API 가 생기면 구현만 바꾼다.
 * 문자열 매칭 검색은 두지 않는다 — 어떤 상품이 말에 맞는지는 모델이 목록을 보고 판단한다.
 */
interface Catalog {
    fun categories(): List<String>
    fun products(category: String, maxPrice: Int?): List<Product>
    fun byId(id: String): Product?
}

class MockCatalog(private val items: List<Product> = MOCK_ITEMS) : Catalog {
    override fun categories(): List<String> = items.map { it.category }.distinct()

    override fun products(category: String, maxPrice: Int?): List<Product> =
        items.filter { it.category == category && (maxPrice == null || it.price <= maxPrice) }

    override fun byId(id: String): Product? = items.firstOrNull { it.id == id }
}

private const val TOMORROW = "내일 10월 4일(일)"
private const val TOMORROW_SPOKEN = "내일"
private const val MON = "10월 5일(월)"
private const val MON_SPOKEN = "10월 5일 월요일에"
private const val TUE = "10월 6일(화)"
private const val TUE_SPOKEN = "10월 6일 화요일에"

private fun rocket(id: String, cat: String, name: String, price: Int) =
    Product(id, cat, name, price, 0, isRocket = true, isFreeShipping = false, TOMORROW, TOMORROW_SPOKEN)

private fun free(id: String, cat: String, name: String, price: Int) =
    Product(id, cat, name, price, 0, isRocket = false, isFreeShipping = true, MON, MON_SPOKEN)

private fun paid(id: String, cat: String, name: String, price: Int, fee: Int) =
    Product(id, cat, name, price, fee, isRocket = false, isFreeShipping = false, TUE, TUE_SPOKEN)

// 가상의 상품이다. 실제 브랜드·가격과 무관하다. 날짜는 2026-10-03(토) 기준.
val MOCK_ITEMS = listOf(
    rocket("p01", "파스·찜질", "쿨 파스 20매", 9_900),
    paid("p02", "파스·찜질", "관절 플라스타 34매", 18_500, 3_000),
    free("p03", "파스·찜질", "온열 찜질 패치 10매", 12_000),
    rocket("p04", "파스·찜질", "무릎 전용 핫팩 파스 12매", 15_800),
    rocket("p05", "영양제", "칼슘 마그네슘 비타민D 180정", 21_900),
    free("p06", "영양제", "루테인 지아잔틴 90캡슐", 27_000),
    paid("p07", "영양제", "오메가3 120캡슐", 19_800, 2_500),
    rocket("p08", "영양제", "종합비타민 실버 60정", 16_500),
    rocket("p09", "눈·돋보기", "접이식 돋보기 안경 +2.0", 13_900),
    free("p10", "눈·돋보기", "LED 확대경 3배율", 24_000),
    rocket("p11", "눈·돋보기", "인공눈물 0.5ml 60개입", 11_200),
    rocket("p12", "위생·기저귀", "성인용 팬티 기저귀 대형 20매", 23_900),
    free("p13", "위생·기저귀", "요실금 패드 중형 30매", 14_500),
    paid("p14", "위생·기저귀", "물티슈 대용량 100매 10팩", 12_900, 3_000),
    rocket("p15", "쌀·잡곡", "햅쌀 10kg", 34_900),
    free("p16", "쌀·잡곡", "현미 찹쌀 혼합 4kg", 18_900),
    paid("p17", "쌀·잡곡", "국산 검은콩 1kg", 15_000, 3_000),
    rocket("p18", "음료·간식", "무가당 두유 190ml 24팩", 13_900),
    rocket("p19", "음료·간식", "구운 김 도시락 16봉", 8_900),
    free("p20", "음료·간식", "검은깨 선식 1kg", 19_500),
    paid("p21", "음료·간식", "보리차 티백 100개", 6_900, 2_500),
    rocket("p22", "건강기기", "팔뚝형 자동 혈압계", 45_000),
    free("p23", "건강기기", "혈당 측정 시험지 50매", 22_000),
    rocket("p24", "건강기기", "보청기용 건전지 312 30개", 9_800),
    rocket("p25", "생활·보조용품", "접이식 알루미늄 지팡이", 19_900),
    free("p26", "생활·보조용품", "무릎 보호대 2개입", 16_900),
    paid("p27", "생활·보조용품", "욕실 미끄럼 방지 매트", 11_900, 3_000),
    rocket("p28", "생활·보조용품", "큰 버튼 리모컨 만능형", 14_900),
)
