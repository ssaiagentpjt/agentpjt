package com.agentpjt.shop.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "ShopApi"

/** 서버 호출 결과. 도구 실행기가 이걸 모델용 결과로 바꾼다. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** 서버가 오류 상태를 돌려줌. 주문 422·409 는 [order] 에 choices 가 들어 있다. */
    data class Http(val code: Int, val message: String, val order: OrderErrorDetailDto? = null) : ApiResult<Nothing>

    /** 네트워크에 닿지 못함(오프라인·타임아웃). */
    data class Network(val cause: Throwable) : ApiResult<Nothing>
}

data class SearchQuery(
    val q: String,
    val minPrice: Int? = null,
    val maxPrice: Int? = null,
    val priceTier: String? = null,
    val sort: String? = null,
    val gift: Boolean? = null,
    val audience: String? = null,
    val limit: Int = 5,
)

/** 목업 상품 API. 테스트에서 가짜를 끼울 수 있게 인터페이스로 둔다. */
interface ShopApi {
    suspend fun search(query: SearchQuery): ApiResult<SearchDto>
    suspend fun product(id: String): ApiResult<ProductFullDto>
    suspend fun placeBatch(order: BatchOrderInDto): ApiResult<BatchOrderDto>
    suspend fun orders(userId: String, limit: Int): ApiResult<List<OrderDto>>
}

class HttpShopApi(
    baseUrl: String,
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient(),
) : ShopApi {

    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    override suspend fun search(query: SearchQuery): ApiResult<SearchDto> {
        val url = base.newBuilder().addPathSegments("products/search").apply {
            addQueryParameter("q", query.q)
            query.minPrice?.let { addQueryParameter("minPrice", it.toString()) }
            query.maxPrice?.let { addQueryParameter("maxPrice", it.toString()) }
            query.priceTier?.let { addQueryParameter("priceTier", it) }
            query.sort?.let { addQueryParameter("sort", it) }
            query.gift?.let { addQueryParameter("gift", it.toString()) }
            query.audience?.let { addQueryParameter("audience", it) }
            addQueryParameter("limit", query.limit.toString())
            addQueryParameter("view", "compact")
        }.build()
        return call(Request.Builder().url(url).get().build())
    }

    override suspend fun product(id: String): ApiResult<ProductFullDto> =
        call(Request.Builder().url(base.newBuilder().addPathSegment("products").addPathSegment(id).build()).get().build())

    override suspend fun placeBatch(order: BatchOrderInDto): ApiResult<BatchOrderDto> {
        val body = json.encodeToString(BatchOrderInDto.serializer(), order).toRequestBody(JSON_TYPE)
        return call(Request.Builder().url(base.newBuilder().addPathSegments("orders/batch").build()).post(body).build())
    }

    override suspend fun orders(userId: String, limit: Int): ApiResult<List<OrderDto>> {
        val url = base.newBuilder().addPathSegment("users").addPathSegment(userId).addPathSegment("orders")
            .addQueryParameter("limit", limit.toString()).build()
        return call(Request.Builder().url(url).get().build())
    }

    private suspend inline fun <reified T> call(request: Request): ApiResult<T> = withContext(Dispatchers.IO) {
        val req = request.newBuilder().header("X-API-Key", apiKey).build()
        val started = System.nanoTime()
        try {
            client.newCall(req).execute().use { res ->
                val text = res.body.string()
                Log.i(TAG, "${req.method} ${req.url.encodedPath}?${req.url.encodedQuery ?: ""} -> ${res.code} ${(System.nanoTime() - started) / 1_000_000}ms")
                if (res.isSuccessful) {
                    ApiResult.Ok(json.decodeFromString<T>(text))
                } else {
                    parseError(res.code, text)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "${req.method} ${req.url.encodedPath} network error", e)
            ApiResult.Network(e)
        }
    }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        // 서버에 필드가 늘어도 앱이 깨지지 않게 모르는 키는 무시한다
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

        /** {"detail": "문자열"} 또는 {"detail": {주문 오류}} 을 읽는다. 모양이 다르면 본문 일부를 메시지로 쓴다. */
        internal fun parseError(code: Int, text: String): ApiResult.Http {
            val detail: JsonElement? = runCatching { json.parseToJsonElement(text).jsonObject["detail"] }.getOrNull()
            return when (detail) {
                is JsonObject -> {
                    val order = runCatching { json.decodeFromJsonElement<OrderErrorDetailDto>(detail) }.getOrNull()
                    ApiResult.Http(code, order?.message ?: detail.toString(), order)
                }
                null -> ApiResult.Http(code, text.take(200))
                else -> ApiResult.Http(code, detail.toString().trim('"'))
            }
        }
    }
}
