package com.agentpjt.shop.shop

/** TTS 큐에 넣는 한 문장. id 로 화면 하이라이트를 맞춘다. */
data class Utterance(val id: String, val text: String)

const val ITEM_PREFIX = "item-"

private val ORDINALS = listOf("첫", "두", "세")

/** 화면별로 읽어 줄 대본. 가격은 모두 readWon 을 거친다. */
object Scripts {

    fun home() = listOf(
        Utterance("home", "안녕하세요. 무엇을 사 드릴까요? 가운데 파란 버튼을 누르고 말씀해 주세요."),
    )

    fun searching() = listOf(Utterance("searching", "찾고 있어요. 잠시만 기다려 주세요."))

    fun results(keyword: String, products: List<Product>): List<Utterance> =
        listOf(Utterance("results-intro", "${withObjectParticle(keyword)} ${koCount(products.size)} 개 찾았어요.")) +
            products.take(3).mapIndexed { i, p ->
                Utterance("$ITEM_PREFIX$i", "${ORDINALS[i]} 번째, ${p.name}, ${readWon(p.price)}, ${p.arriveSpoken} 도착해요.")
            } +
            Utterance("results-ask", "마음에 드는 번호를 말씀하시거나 눌러 주세요.")

    fun detail(p: Product) = listOf(
        Utterance(
            "detail",
            "${p.name}예요. ${readWon(p.price)}이고, " +
                (if (p.shippingFee > 0) "배송비 ${readWon(p.shippingFee)}이 붙어요. " else "배송비는 없어요. ") +
                "${p.arriveSpoken} 도착해요. 몇 개 드릴까요?",
        ),
    )

    fun confirm(p: Product, qty: Int) = listOf(
        Utterance(
            "confirm",
            "${p.name.substringBeforeLast(' ')} ${koCount(qty)} 개, 배송비 포함 ${readWon(total(p, qty))}입니다. " +
                "국민카드로 결제할까요? 네라고 말씀하시거나 아래 버튼을 눌러 주세요.",
        ),
    )

    fun done(p: Product) = listOf(
        Utterance("done", "주문했어요. ${p.arriveSpoken} 도착해요. 가족께도 알려 드렸어요."),
    )

    fun total(p: Product, qty: Int) = p.price * qty + p.shippingFee
}
