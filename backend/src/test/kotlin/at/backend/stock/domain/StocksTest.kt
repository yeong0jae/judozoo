package at.backend.stock.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class StocksTest : FunSpec({

    fun stock(code: String, name: String, market: Market = Market.KOSPI) =
        Stock(shortCode = code, standardCode = "KR7$code", name = name, market = market)

    val samsung = stock("005930", "삼성전자")
    val samsungBio = stock("207940", "삼성바이오로직스")
    val skHynix = stock("000660", "SK하이닉스")
    val kodex = stock("069500", "KODEX 200", Market.KOSPI)
    val catalog = Stocks(listOf(samsung, samsungBio, skHynix, kodex))

    context("종목 검색") {
        test("종목명 일부로 여러 종목을 찾는다") {
            catalog.search("삼성", limit = 10).map { it.name } shouldContainExactly
                listOf("삼성바이오로직스", "삼성전자")
        }

        test("종목코드 접두사로 찾는다") {
            catalog.search("0059", limit = 10) shouldContainExactly listOf(samsung)
        }

        test("대소문자를 가리지 않는다") {
            catalog.search("kodex", limit = 10) shouldContainExactly listOf(kodex)
        }

        test("종목명 시작 일치를 코드 시작 일치보다 앞에 둔다") {
            val byName = stock("999999", "ABC전자")     // 종목명이 "abc"로 시작 → 관련도 0
            val byCode = stock("ABC123", "주식회사가나") // 종목코드가 "abc"로 시작 → 관련도 1
            val ranked = Stocks(listOf(byCode, byName))

            ranked.search("abc", limit = 10) shouldContainExactly listOf(byName, byCode)
        }

        test("최대 건수를 초과하지 않는다") {
            catalog.search("삼성", limit = 1).size shouldBe 1
        }

        test("공백 질의는 빈 결과") {
            catalog.search("   ", limit = 10) shouldBe emptyList()
        }

        test("일치하는 종목이 없으면 빈 결과") {
            catalog.search("없는종목", limit = 10) shouldBe emptyList()
        }
    }
})
