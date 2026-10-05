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
}
