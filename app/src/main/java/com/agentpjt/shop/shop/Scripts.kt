package com.agentpjt.shop.shop

/** TTS 큐에 넣는 한 문장. id 로 화면 하이라이트를 맞춘다. */
data class Utterance(val id: String, val text: String)

const val ITEM_PREFIX = "item-"

private val ORDINALS = listOf("첫", "두", "세")

/** 화면별로 읽어 줄 대본. 가격은 모두 readWon(또는 서버가 같은 규칙으로 만든 priceSpoken)을 거친다. */
object Scripts {

    fun home() = listOf(
        Utterance("home", "안녕하세요, 손주야예요. 무엇을 사 드릴까요? 가운데 파란 버튼을 누르고 말씀해 주세요."),
    )

    fun thinking() = listOf(Utterance("thinking", "알아볼게요."))

    fun notHeard() = listOf(Utterance("not-heard", "잘 못 들었어요. 다시 말씀해 주세요."))

    fun aiUnavailable() = listOf(Utterance("ai-unavailable", "지금은 말로 도와 드리기 어려워요. 화면을 눌러서 이용해 주세요."))

    fun agentFailed() = listOf(Utterance("agent-failed", "죄송해요, 잘 알아듣지 못했어요. 다르게 말씀해 주세요."))

    fun networkError() = listOf(Utterance("network-error", "인터넷 연결이 불안정해서 상품을 불러오지 못했어요. 잠시 뒤에 다시 말씀해 주세요."))

    /** 모델이 도구 없이 한 말(되묻기 등). 기호·id 를 지우고 앞 두 문장만 읽는다. 전문은 화면 말풍선(ShopState.reply)에 있다. */
    fun say(text: String) = listOf(Utterance("say", SpeechText.spoken(text)))

    /** 옵션을 골라야 할 때(터치로 주문을 눌렀는데 옵션이 비었을 때). 한 번에 하나만 묻는다 */
    fun askOption(choices: Map<String, List<String>>): List<Utterance> {
        val (name, values) = choices.entries.first()
        return listOf(Utterance("ask-option", "${withObjectParticle(name)} 먼저 골라 주세요. ${values.joinToString(", ")} 중에 고르실 수 있어요."))
    }

    fun results(label: String, products: List<ProductSummary>): List<Utterance> =
        listOf(Utterance("results-intro", "${withObjectParticle(label)} ${koCount(products.size)} 개 골랐어요.")) +
            products.take(3).mapIndexed { i, p ->
                Utterance("$ITEM_PREFIX$i", "${ORDINALS[i]} 번째, ${p.name}, ${p.priceSpoken}, ${p.arriveSpoken} 도착해요.")
            } +
            Utterance("results-ask", "마음에 드는 번호를 말씀하시거나 눌러 주세요.")

    fun detail(p: ProductDetail, selected: Map<String, String> = emptyMap()) = listOf(
        Utterance(
            "detail",
            "${p.name}예요. ${readWon(p.price)}이고, " +
                (if (p.shippingFee > 0) "배송비 ${readWon(p.shippingFee)}이 붙어요. " else "배송비는 없어요. ") +
                "${p.arriveSpoken} 도착해요. " +
                when {
                    p.soldOut -> "지금은 품절이에요."
                    p.missing(selected).isNotEmpty() -> p.missing(selected).entries.first().let { (name, values) ->
                        "${name}는 ${values.joinToString(", ")} 중에 고르실 수 있어요."
                    }
                    else -> "몇 개 드릴까요?"
                },
        ),
    )

    fun confirm(p: ProductDetail, qty: Int, selected: Map<String, String> = emptyMap()): List<Utterance> {
        val opts = if (selected.isEmpty()) "" else selected.values.joinToString(" ", postfix = ", ")
        return listOf(
            Utterance(
                "confirm",
                "${p.name}, $opts${koCount(qty)} 개, 배송비 포함 ${readWon(p.total(qty, selected))}입니다. " +
                    "국민카드로 결제할까요? 네라고 말씀하시거나 아래 버튼을 눌러 주세요.",
            ),
        )
    }

    fun done(p: ProductDetail) = listOf(
        Utterance("done", "주문했어요. ${p.arriveSpoken} 도착해요. 가족께도 알려 드렸어요."),
    )
}
