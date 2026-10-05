package com.agentpjt.shop.shop

/** 목업 상품. 필드 이름은 나중에 붙일 쇼핑 검색 응답(productName, productPrice, isRocket …)에 맞춰 두었다. */
data class Product(
    val id: String,
    val category: String,
    val name: String,
    val price: Int,
    val shippingFee: Int,
    val isRocket: Boolean,
    val isFreeShipping: Boolean,
    /** 화면 표기. 예: "내일 10월 4일(일)" */
    val arriveLabel: String,
    /** 읽어 줄 때 쓰는 말. 예: "내일", "10월 6일 화요일에" */
    val arriveSpoken: String,
)
