package com.agentpjt.shop.agent

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * 모델에게 보여 주는 도구 정의. automaticToolCalling = false 라 이 메서드들은 실행되지 않는다 —
 * LiteRT-LM 이 시그니처와 설명만 읽어 모델에 넘기고, 호출은 Message.toolCalls 로 돌아와 ToolExecutor 가 처리한다.
 */
@Suppress("unused", "UNUSED_PARAMETER")
class ShopTools : ToolSet {

    @Tool(description = "카테고리 안의 상품 목록을 가져온다. 사용자가 무언가를 사거나 찾으려 하면 먼저 이걸로 후보를 본다.")
    fun getProducts(
        @ToolParam(description = "시스템 안내에 나온 카테고리 이름 중 하나") category: String,
        @ToolParam(description = "가격 상한(원). 사용자가 예산을 말하지 않았으면 비운다") maxPrice: Int?,
    ): String = ""

    @Tool(description = "고른 상품을 추천 화면에 번호를 붙여 보여 주고 읽어 준다. 사용자 말에 가장 맞는 상품을 최대 3개 고른다.")
    fun showProducts(
        @ToolParam(description = "보여 줄 상품 id 목록. 잘 맞는 순서대로 최대 3개") productIds: List<String>,
        @ToolParam(description = "화면 제목에 쓸 짧은 말. 예: 무릎 파스, 아침 음료") label: String,
    ): String = ""

    @Tool(description = "상품 하나의 상세 화면을 연다. 사용자가 화면의 상품 중 하나를 고르면 쓴다.")
    fun openProduct(
        @ToolParam(description = "상품 id") productId: String,
    ): String = ""

    @Tool(description = "지금 보고 있는 상품의 수량을 바꾼다.")
    fun setQuantity(
        @ToolParam(description = "수량, 1에서 9 사이") count: Int,
    ): String = ""

    @Tool(description = "주문 확인 화면을 연다. 사용자가 사겠다고 하면 쓴다. 주문은 아직 확정되지 않는다.")
    fun requestOrder(
        @ToolParam(description = "상품 id") productId: String,
        @ToolParam(description = "수량, 1에서 9 사이") quantity: Int,
    ): String = ""

    @Tool(description = "주문을 확정한다. 주문 확인 화면에서 사용자가 동의했을 때만 쓴다.")
    fun placeOrder(): String = ""

    @Tool(description = "처음 화면으로 돌아간다. 사용자가 그만하거나 처음부터 하겠다고 하면 쓴다.")
    fun goHome(): String = ""
}
