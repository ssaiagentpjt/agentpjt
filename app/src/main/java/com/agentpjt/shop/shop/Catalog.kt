package com.agentpjt.shop.shop

import com.agentpjt.shop.api.CategoryMainDto

/**
 * 매장 분류(대분류 16 · 중분류 86 · 소분류 288, 서버 GET /categories). 상품 사실이 아니라 매장의 구조다.
 * - 지시문에 분류 목록을 넣지는 않는다: 넣었더니 첫 판단이 무너지고 느려졌다(실기기 eval4)
 * - 검색을 분류로 거른다(스키마 enum 은 이름, 서버에는 id 로 보낸다)
 * - 검색 결과 한 줄에 분류 경로를 붙인다. 소분류 이름이 서로 겹치지 않아 경로를 정확히 찾을 수 있다
 * 서버에 닿지 못하면 비어 있고, 그때는 분류 없이 지금처럼 동작한다.
 */
data class Catalog(val mains: List<CategoryMainDto> = emptyList()) {

    val isEmpty: Boolean get() = mains.isEmpty()

    /** 검색 category 로 고를 수 있는 이름: 대분류와 중분류 */
    val categoryNames: List<String> get() = mains.flatMap { m -> listOf(m.name) + m.mids.map { it.name } }

    /** 이름 → (대분류 id, 중분류 id 또는 null). 모르는 이름이면 null */
    fun filterOf(name: String): Pair<String, String?>? {
        mains.firstOrNull { it.name == name }?.let { return it.id to null }
        for (m in mains) m.mids.firstOrNull { it.name == name }?.let { return m.id to it.id }
        return null
    }

    /** 소분류 이름 → 그 위의 (대분류 이름, 중분류 이름). 모르면 빈 목록 */
    fun parentsOf(sub: String): List<String> {
        for (m in mains) for (mid in m.mids) if (mid.subs.any { it.name == sub }) return listOf(m.name, mid.name)
        return emptyList()
    }

    /** 소분류 이름 → "대분류 > 중분류 > 소분류". 모르면 소분류 이름만 */
    fun pathOf(sub: String): String {
        for (m in mains) for (mid in m.mids) if (mid.subs.any { it.name == sub }) return "${m.name} > ${mid.name} > $sub"
        return sub
    }

}
