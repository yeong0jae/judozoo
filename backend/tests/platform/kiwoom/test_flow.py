"""키움 수급·프로그램매매 — 종목 투자자(ka10059), 종목 프로그램(ka90013)."""

from datetime import date

import httpx
import pytest
import respx

from backend.platform.kiwoom import client, investor, program

BASE = "https://api.kiwoom.com"
TOKEN_URL = f"{BASE}/oauth2/token"
STOCK_INFO_URL = f"{BASE}/api/dostk/stkinfo"
MRKCOND_URL = f"{BASE}/api/dostk/mrkcond"


@pytest.fixture(autouse=True)
def 키움_초기화():
    client.reset()
    yield
    client.reset()


@pytest.fixture
def 토큰_발급(respx_mock):
    respx_mock.post(TOKEN_URL).mock(
        return_value=httpx.Response(200, json={"token": "tok", "return_code": 0})
    )


class Test종목_투자자_수급:
    @respx.mock
    def test_날짜를_ISO로_바꾸고_부호를_살린다(self, respx_mock, 토큰_발급):
        respx_mock.post(STOCK_INFO_URL).mock(
            return_value=httpx.Response(200, json={"return_code": 0, "stk_invsr_orgn": [{
                "dt": "20260911", "ind_invsr": "+1200", "frgnr_invsr": "-800",
                "orgn": "400-", "etc_corp": "0", "fnnc_invt": "+100", "insrnc": "-50",
                "invtrt": "+200", "etc_fnnc": "0", "bank": "-10", "penfnd_etc": "+150",
                "samo_fund": "-20",
            }]})
        )

        하루 = investor.fetch_investor_trend("005930")[0]

        assert 하루.date == "2026-09-11"
        assert 하루.individual_net == 1200
        assert 하루.foreign_net == -800
        assert 하루.institution_net == -400  # "400-" 후위 부호

    @respx.mock
    def test_기관_세부를_모두_읽는다(self, respx_mock, 토큰_발급):
        respx_mock.post(STOCK_INFO_URL).mock(
            return_value=httpx.Response(200, json={"return_code": 0, "stk_invsr_orgn": [{
                "dt": "20260911", "fnnc_invt": "100", "insrnc": "50", "invtrt": "200",
                "etc_fnnc": "10", "bank": "20", "penfnd_etc": "150", "samo_fund": "30",
            }]})
        )

        하루 = investor.fetch_investor_trend("005930")[0]

        assert (하루.financial_investment_net, 하루.trust_net, 하루.pension_fund_net) == (100, 200, 150)
        assert (하루.insurance_net, 하루.bank_net, 하루.private_equity_net) == (50, 20, 30)

    @respx.mock
    def test_날짜_형식이_어긋나면_빈_문자열(self, respx_mock, 토큰_발급):
        respx_mock.post(STOCK_INFO_URL).mock(
            return_value=httpx.Response(200, json={"return_code": 0, "stk_invsr_orgn": [{"dt": "2026"}]})
        )

        assert investor.fetch_investor_trend("005930")[0].date == ""

    @respx.mock
    def test_오류_코드면_빈_목록(self, respx_mock, 토큰_발급):
        respx_mock.post(STOCK_INFO_URL).mock(
            return_value=httpx.Response(200, json={"return_code": 3, "return_msg": "오류"})
        )

        assert investor.fetch_investor_trend("005930") == []

    @respx.mock
    def test_타임아웃이면_빈_목록(self, respx_mock, 토큰_발급):
        respx_mock.post(STOCK_INFO_URL).mock(side_effect=httpx.ReadTimeout("timeout"))

        assert investor.fetch_investor_trend("005930") == []


class Test종목_프로그램매매:
    @respx.mock
    def test_첫_행의_순매수를_읽는다(self, respx_mock, 토큰_발급):
        respx_mock.post(MRKCOND_URL).mock(
            return_value=httpx.Response(200, json={"stk_daly_prm_trde_trnsn": [{
                "dt": "20260911", "prm_sell_amt": "1000", "prm_buy_amt": "1500",
                "prm_netprps_amt": "+500",
            }]})
        )

        결과 = program.fetch_program_trading("005930")

        assert 결과.program_net_buy_amount == 500

    @respx.mock
    def test_같은_종목은_캐시하고_날짜가_바뀌면_다시_조회한다(self, respx_mock, 토큰_발급, mocker):
        현재날짜 = mocker.patch.object(program, "today", return_value=date(2026, 9, 30))
        route = respx_mock.post(MRKCOND_URL).mock(return_value=httpx.Response(200, json={
            "stk_daly_prm_trde_trnsn": [{"dt": "20260930", "prm_netprps_amt": "500"}],
        }))

        assert program.fetch_program_net_buy("005930") == 500
        assert program.fetch_program_net_buy("005930") == 500
        assert route.call_count == 1

        assert program.fetch_program_net_buy("000660") == 500
        assert route.call_count == 2

        현재날짜.return_value = date(2026, 10, 1)
        assert program.fetch_program_net_buy("005930") == 500
        assert route.call_count == 3

    @respx.mock
    def test_목록이_통째로_빠져도_None으로_넘긴다(self, respx_mock, 토큰_발급):
        """운영 로그에서 이 필드가 없는 응답이 확인됐다."""
        route = respx_mock.post(MRKCOND_URL).mock(return_value=httpx.Response(200, json={}))

        assert program.fetch_program_trading("005930") is None
        assert program.fetch_program_net_buy("005930") == 0
        assert route.call_count == 2


class Test금액_파서:
    @pytest.mark.parametrize(
        "입력,기대",
        [("-123", -123), ("123-", -123), ("--123", -123), ("+123", 123), ("123", 123), ("", 0), ("abc", 0)],
    )
    def test_음수_변종을_모두_흡수한다(self, 입력, 기대):
        """"--123"이 실제로 온다 — 앞의 하나만 떼면 부호가 뒤집힌다."""
        assert program.parse_amount(입력) == 기대
