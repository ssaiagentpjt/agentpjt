package com.agentpjt.shop.shop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {

    // 실기기 eval1.log 에서 모델이 실제로 한 말(일부)
    private val logged = """
        [화면] 처음 화면
        무릎에 좋은 파스들을 몇 가지 찾아봤습니다. 이 중에서 마음에 드시는 것을 골라보세요.

        1. **상어연골 콘드로이친 120정** (ID: p02083) - 가격은 삼만 구천구백 원입니다.
        2. **무릎 쿨파스 대형 10매** (ID: p03184) - 가격은 만 이천구백 원입니다.

        어떤 상품이 가장 마음에 드시나요? 번호를 말씀해 주세요.
    """.trimIndent()

    @Test
    fun clean_removesStateEchoMarkdownAndIds() {
        val text = SpeechText.clean(logged)
        listOf("[화면]", "*", "ID", "p02083", "1.", "(", "\n").forEach { assertFalse(it, text.contains(it)) }
        assertTrue(text, text.startsWith("무릎에 좋은 파스들을 몇 가지 찾아봤습니다."))
        assertTrue(text, text.contains("상어연골 콘드로이친 120정, 가격은 삼만 구천구백 원입니다."))
    }

    @Test
    fun clean_keepsPlainSentence() {
        val q = "사이즈는 M, L, XL 이 있어요. 어떤 걸로 드릴까요?"
        assertEquals(q, SpeechText.clean(q))
    }

    @Test
    fun spoken_takesFirstTwoSentencesWithinLimit() {
        assertEquals("무릎에 좋은 파스들을 몇 가지 찾아봤습니다. 이 중에서 마음에 드시는 것을 골라보세요.", SpeechText.spoken(logged))
        val long = "가".repeat(200) + "."
        assertTrue(SpeechText.spoken(long).length <= 120)
    }
}
