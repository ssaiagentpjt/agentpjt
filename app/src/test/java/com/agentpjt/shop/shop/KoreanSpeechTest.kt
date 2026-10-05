package com.agentpjt.shop.shop

import org.junit.Assert.assertEquals
import org.junit.Test

class KoreanSpeechTest {

    @Test
    fun readWon_readsPricesAsSpoken() {
        assertEquals("구천구백 원", readWon(9_900)) // Gemma 가 "만구천 원"으로 틀렸던 값
        assertEquals("만 팔천오백 원", readWon(18_500)) // Gemma 가 "일만 팔천 원"으로 틀렸던 값
        assertEquals("이만 천오백 원", readWon(21_500))
        assertEquals("사만 원", readWon(40_000))
        assertEquals("만 원", readWon(10_000))
        assertEquals("십일만 원", readWon(110_000))
        assertEquals("천 원", readWon(1_000))
        assertEquals("영 원", readWon(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun readWon_rejectsNegative() {
        readWon(-1)
    }

    @Test
    fun koCount_usesNativeCounters() {
        assertEquals("한", koCount(1))
        assertEquals("두", koCount(2))
        assertEquals("아홉", koCount(9))
        assertEquals("12", koCount(12))
    }

    @Test
    fun withObjectParticle_followsFinalConsonant() {
        assertEquals("무릎 파스를", withObjectParticle("무릎 파스"))
        assertEquals("쌀을", withObjectParticle("쌀"))
        assertEquals("TV를", withObjectParticle("TV"))
    }

    @Test
    fun formatWon_groupsThousands() {
        assertEquals("18,500원", formatWon(18_500))
    }

    @Test
    fun parseWon_readsSpokenAmounts_andRejectsNonNumbers() {
        mapOf(
            "오만 원" to 50_000, "오만 원 안쪽" to 50_000, "오만 원 안쪽으로" to 50_000, "십만 이하" to 100_000, "삼만 오천" to 35_000, "2만5천원" to 25_000, "50000" to 50_000,
            "만 원" to 10_000, "만 오천 원" to 15_000, "십만 원 이하" to 100_000, "이십만" to 200_000, "구천구백 원" to 9_900, "3만" to 30_000,
        ).forEach { (text, won) -> assertEquals(text, won, parseWon(text)) }
        listOf("싼 거", "적당히", "").forEach { assertEquals(it, null, parseWon(it)) }
        (1000..99_999 step 700).forEach { assertEquals(it, parseWon(readWon(it))) } // 읽는 말로 바꾼 것을 되돌린다
    }
}
