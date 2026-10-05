package com.agentpjt.shop.shop

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 장바구니 한 줄. 같은 상품이라도 옵션이 다르면 다른 줄이다.
 * 가격·추가 금액·배송비는 담을 때 서버 상세(full)에서 받아 둔다 — 결제 때 서버가 다시 검사한다.
 */
@Serializable
data class CartLine(
    val lineId: String,
    val productId: String,
    val name: String,
    val price: Int,
    val priceAdd: Int,
    val options: Map<String, String>,
    val qty: Int,
    val shippingFee: Int,
    val image: String = "",
) {
    /** (가격 + 추가 금액) × 수량 + 배송비. 서버 POST /orders/batch 의 줄 합계와 같은 계산 */
    val total: Int get() = (price + priceAdd) * qty + shippingFee
}

fun List<CartLine>.total(): Int = sumOf { it.total }

/** 다음 줄 id. c1, c2 … 지운 번호는 다시 쓰지 않아 모델이 앞서 본 id 가 다른 줄을 가리키지 않는다 */
fun List<CartLine>.nextLineId(): String = "c" + ((mapNotNull { it.lineId.removePrefix("c").toIntOrNull() }.maxOrNull() ?: 0) + 1)

/**
 * 장바구니를 앱 전용 폴더의 JSON 파일에 둔다. 처음 화면으로 가도, 앱을 꺼도 남는다.
 * 임시 파일에 다 쓴 뒤 이름을 바꿔치기해서, 쓰는 도중 앱이 죽어도 이전 내용이 깨지지 않는다.
 */
class CartStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CartLine.serializer())

    fun load(): List<CartLine> = runCatching {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
    }.getOrElse {
        Log.w("ShopCart", "장바구니 파일을 읽지 못해 비운다", it)
        emptyList()
    }

    fun save(lines: List<CartLine>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, lines))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
