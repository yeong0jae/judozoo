package at.backend.leadingstock.application.filter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EtfExclusionFilterTest : FunSpec({
    val filter = EtfExclusionFilter()

    context("개별 종목 통과") {
        test("일반 종목명은 통과") {
            filter.filter(snapshot(stockName = "삼성전자")) shouldBe true
        }
    }

    context("운용사 brand prefix 차단") {
        test("KODEX prefix → 차단") {
            filter.filter(snapshot(stockName = "KODEX 200")) shouldBe false
        }
        test("TIGER prefix → 차단") {
            filter.filter(snapshot(stockName = "TIGER 반도체")) shouldBe false
        }
        test("KOSEF prefix → 차단") {
            filter.filter(snapshot(stockName = "KOSEF 코스피")) shouldBe false
        }
        test("ACE prefix → 차단") {
            filter.filter(snapshot(stockName = "ACE 미국S&P500")) shouldBe false
        }
    }

    context("SOL prefix") {
        test("SOL 반도체 ETF도 prefix로 차단") {
            filter.filter(snapshot(stockName = "SOL AI반도체TOP2플러스")) shouldBe false
        }
        test("다른 SOL 종목도 차단") {
            filter.filter(snapshot(stockName = "SOL 미국배당다우존스")) shouldBe false
        }
    }

    context("ETN 패턴 차단") {
        test("종목명 중간에 ' ETN'이 포함되면 차단") {
            filter.filter(snapshot(stockName = "한투 ETN 코스피200 H")) shouldBe false
        }
    }

    context("위 패턴에 매칭되지 않으면 통과") {
        test("일부만 비슷한 케이스(예: 'ACEM')는 통과 — prefix 정확히 일치 필요") {
            filter.filter(snapshot(stockName = "ACEM헬스케어")) shouldBe true
        }
    }
})
