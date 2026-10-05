package com.agentpjt.shop.api

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShopApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: HttpShopApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = HttpShopApi(server.url("/").toString(), apiKey = "test-key")
    }

    @After
    fun tearDown() = server.close()

    private fun json(code: Int, body: String) = MockResponse(code, headersOf("Content-Type", "application/json"), body)

    @Test
    fun search_sendsKeyAndKoreanQuery_parsesCompact() = runTest {
        server.enqueue(json(200, """
            {"query":"무릎 파스","total":1,"products":[{"id":"p03005","name":"무릎 전용 핫팩 파스 12매","brand":"온케어",
             "sub":"핫파스","price":15800,"priceSpoken":"만 오천팔백 원","tier":"보통 가격","discount":12,"rating":4.7,
             "reviews":2210,"rankInMid":1,"badges":["베스트"],"arrive":"내일","stock":"in_stock","gift":false,
             "image":"https://x/static/icons/medical.svg","options":[],"newFieldFromServer":123}]}
        """.trimIndent()))

        val r = api.search(SearchQuery(q = "무릎 파스", maxPrice = 20000, sort = "sales"))

        val req = server.takeRequest()
        assertEquals("test-key", req.headers["X-API-Key"])
        assertEquals("/products/search", req.url.encodedPath)
        assertEquals("무릎 파스", req.url.queryParameter("q"))
        assertEquals("20000", req.url.queryParameter("maxPrice"))
        assertEquals("compact", req.url.queryParameter("view"))
        val ok = r as ApiResult.Ok
        assertEquals("만 오천팔백 원", ok.value.products.single().priceSpoken) // 모르는 필드는 무시된다
    }

    @Test
    fun placeOrder_422_parsesChoices() = runTest {
        server.enqueue(json(422, """{"detail":{"message":"옵션을 골라야 한다","missing":["사이즈"],"invalid":{},
            "unknownOptions":[],"choices":{"사이즈":["M","L","XL"]}}}"""))

        val r = api.placeOrder(OrderInDto("u001", "p07002", 1, emptyMap()))

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.body!!.utf8().contains("\"productId\":\"p07002\""))
        val http = r as ApiResult.Http
        assertEquals(422, http.code)
        assertEquals(listOf("M", "L", "XL"), http.order!!.choices["사이즈"])
    }

    @Test
    fun stringDetail_isMessage() = runTest {
        server.enqueue(json(404, """{"detail":"없는 상품: p99999"}"""))
        val r = api.product("p99999") as ApiResult.Http
        assertEquals(404, r.code)
        assertEquals("없는 상품: p99999", r.message)
    }

    @Test
    fun orders_parsesHistory() = runTest {
        server.enqueue(json(200, """[{"orderId":"M-1","userId":"u001","productId":"p01001","productName":"햅쌀 10kg",
            "quantity":1,"options":{},"totalPrice":34900,"orderedAt":"2026-09-12"}]"""))
        val r = api.orders("u001", 5) as ApiResult.Ok
        assertEquals("/users/u001/orders", server.takeRequest().url.encodedPath)
        assertEquals("햅쌀 10kg", r.value.single().productName)
    }

    @Test
    fun unreachableServer_isNetworkError() = runTest {
        val dead = HttpShopApi(server.url("/").toString(), "k")
        server.close()
        assertTrue(dead.search(SearchQuery("쌀")) is ApiResult.Network)
    }
}
