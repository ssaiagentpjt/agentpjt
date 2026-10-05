package com.agentpjt.shop.shop

import java.text.NumberFormat
import java.util.Locale

private val DIGITS = arrayOf("", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
private val UNITS = arrayOf("천", "백", "십", "")

/**
 * 원 단위 금액을 읽는 말로 바꾼다. 9900 → "구천구백 원", 21500 → "이만 천오백 원".
 *
 * Gemma 실측에서 9,900원을 "만구천 원"으로 읽는 오류가 있어 숫자는 모델에 맡기지 않는다.
 * 1억 이상은 쇼핑 금액으로 나올 일이 없어 다루지 않는다.
 */
fun readWon(amount: Int): String {
    require(amount in 0 until 100_000_000) { "범위 밖 금액: $amount" }
    val man = amount / 10_000
    val rest = amount % 10_000
    val sb = StringBuilder()
    if (man > 0) sb.append(if (man == 1) "" else readFour(man)).append("만")
    if (rest > 0) {
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(readFour(rest))
    }
    return (if (sb.isEmpty()) "영" else sb.toString()) + " 원"
}

/** 0~9999 를 읽는다. 천·백·십 앞의 "일"은 생략한다(일천 → 천). */
private fun readFour(n: Int): String {
    val s = n.toString().padStart(4, '0')
    val sb = StringBuilder()
    for (i in 0 until 4) {
        val v = s[i] - '0'
        if (v == 0) continue
        if (!(v == 1 && UNITS[i].isNotEmpty())) sb.append(DIGITS[v])
        sb.append(UNITS[i])
    }
    return sb.toString()
}

private val AMOUNT = Regex("""[0-9일이삼사오육칠팔구십백천만]+(?=\s|$|원)(?:\s+[0-9일이삼사오육칠팔구십백천만]+(?=\s|$|원))*""")
private val NUM_WORDS = mapOf('일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5, '육' to 6, '칠' to 7, '팔' to 8, '구' to 9)
private val SMALL_UNITS = mapOf('십' to 10, '백' to 100, '천' to 1000)

/**
 * 말로 한 금액 → 원. "오만 원", "삼만 오천", "2만5천원", "50000", "만 원" 같은 숫자 표현만 바꾼다(readWon 의 반대).
 * 숫자 표현이 아니면 null. 뜻을 해석하지 않는다 — "싼 거" 같은 말은 null 이다.
 */
fun parseWon(text: String): Int? {
    // 숫자·수 낱말로만 된 덩어리(뒤에 공백·끝·'원'이 오는 것)를 이어 붙인 첫 금액 표현만 쓴다. "이하"의 '이' 같은 것은 덩어리가 아니다
    val chunk = AMOUNT.find(text.replace(",", ""))?.value ?: return null
    val s = chunk.replace(" ", "")
    if (s.isEmpty()) return null
    s.toIntOrNull()?.let { return it }
    var total = 0L
    var section = 0L // 만 아래 묶음
    var digit = -1L  // 바로 앞의 한 자리 수
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c.isDigit() -> {
                var j = i
                while (j < s.length && s[j].isDigit()) j++
                digit = s.substring(i, j).toLong()
                i = j
                continue
            }
            c in NUM_WORDS -> digit = NUM_WORDS.getValue(c).toLong()
            c in SMALL_UNITS -> { section += (if (digit < 0) 1 else digit) * SMALL_UNITS.getValue(c); digit = -1 }
            c == '만' -> { total += (section + (if (digit < 0) (if (section == 0L) 1 else 0) else digit)) * 10_000; section = 0; digit = -1 }
            c == '억' -> return null
            else -> return null
        }
        i++
    }
    total += section + (if (digit < 0) 0 else digit)
    return total.takeIf { it in 1..99_999_999 }?.toInt()
}

private val COUNTS = arrayOf("", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉")

/** 개수를 세는 말. 1 → "한", 2 → "두". 10 이상은 숫자 그대로. */
fun koCount(n: Int): String = COUNTS.getOrNull(n)?.takeIf { it.isNotEmpty() } ?: n.toString()

/** 목적격 조사를 붙인다. 받침이 있으면 "을", 없거나 한글로 끝나지 않으면 "를". "무릎 파스" → "무릎 파스를" */
fun withObjectParticle(word: String): String {
    val last = word.lastOrNull() ?: return word
    val hasFinal = last in '가'..'힣' && (last - '가') % 28 != 0
    return word + if (hasFinal) "을" else "를"
}

/** 화면 표기용. 18500 → "18,500원" */
fun formatWon(amount: Int): String = NumberFormat.getNumberInstance(Locale.KOREA).format(amount) + "원"
