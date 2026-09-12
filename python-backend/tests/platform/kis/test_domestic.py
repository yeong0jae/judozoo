"""KIS 국내 — 휴장일(CTCA0903R), 지수선물(전광판·일봉·분봉·시세·투자자)."""

from datetime import date, time

import httpx
import pytest
import respx

from backend.platform.kis import client as kis_client, futures, holiday
from backend.stock.domain import Market

BASE = "https://openapi.koreainvestment.com:9443"
TOKEN_URL = f"{BASE}/oauth2/tokenP"
HOLIDAY_URL = f"{BASE}/uapi/domestic-stock/v1/quotations/chk-holiday"
BOARD_URL = f"{BASE}/uapi/domestic-futureoption/v1/quotations/display-board-futures"
DAILY_URL = f"{BASE}/uapi/domestic-futureoption/v1/quotations/inquire-daily-fuopchartprice"
MINUTE_URL = f"{BASE}/uapi/domestic-futureoption/v1/quotations/inquire-time-fuopchartprice"
PRICE_URL = f"{BASE}/uapi/domestic-futureoption/v1/quotations/inquire-price"
INVESTOR_URL = f"{BASE}/uapi/domestic-stock/v1/quotations/inquire-investor-time-by-market"


@pytest.fixture(autouse=True)
def kis_초기화():
    kis_client.reset()
    yield
    kis_client.reset()


@pytest.fixture
def 토큰_발급(respx_mock):
    respx_mock.post(TOKEN_URL).mock(
        return_value=httpx.Response(200, json={"access_token": "tok"})
    )


class Test휴장일:
    @respx.mock
    def test_개장일만_모은다(self, respx_mock, 토큰_발급):
        respx_mock.get(HOLIDAY_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output": [
                {"bass_dt": "20260911", "opnd_yn": "Y"},
                {"bass_dt": "20260912", "opnd_yn": "N"},
                {"bass_dt": "20260914", "opnd_yn": "Y"},
            ]})
        )

        assert holiday.fetch_open_days(date(2026, 9, 11)) == {date(2026, 9, 11), date(2026, 9, 14)}

    @respx.mock
    def test_오류면_빈_집합(self, respx_mock, 토큰_발급):
        """호출측이 주말만으로 폴백하게 한다."""
        respx_mock.get(HOLIDAY_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "1", "msg1": "오류"})
        )

        assert holiday.fetch_open_days(date(2026, 9, 11)) == set()

    @respx.mock
    def test_타임아웃이면_빈_집합(self, respx_mock, 토큰_발급):
        respx_mock.get(HOLIDAY_URL).mock(side_effect=httpx.ReadTimeout("timeout"))

        assert holiday.fetch_open_days(date(2026, 9, 11)) == set()


class Test근월물:
    @respx.mock
    def test_잔존일수가_가장_짧은_종목을_고른다(self, respx_mock, 토큰_발급):
        respx_mock.get(BOARD_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output": [
                {"futs_shrn_iscd": "101W12", "hts_kor_isnm": "K200 선물 12월", "hts_rmnn_dynu": "80"},
                {"futs_shrn_iscd": "101W09", "hts_kor_isnm": "K200 선물 9월", "hts_rmnn_dynu": "5"},
            ]})
        )

        assert futures.fetch_near_month(Market.KOSPI).iscd == "101W09"

    @respx.mock
    def test_만기가_지난_종목은_제외한다(self, respx_mock, 토큰_발급):
        respx_mock.get(BOARD_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output": [
                {"futs_shrn_iscd": "101W08", "hts_rmnn_dynu": "-3"},
                {"futs_shrn_iscd": "101W09", "hts_rmnn_dynu": "5"},
            ]})
        )

        assert futures.fetch_near_month(Market.KOSPI).iscd == "101W09"

    @respx.mock
    def test_시장별로_전광판_코드가_갈린다(self, respx_mock, 토큰_발급):
        route = respx_mock.get(BOARD_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output": []})
        )

        futures.fetch_near_month(Market.KOSDAQ)

        assert "FID_COND_MRKT_CLS_CODE=KQI" in str(route.calls[0].request.url)

    @respx.mock
    def test_후보가_없으면_None(self, respx_mock, 토큰_발급):
        respx_mock.get(BOARD_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output": []})
        )

        assert futures.fetch_near_month(Market.KOSPI) is None


class Test선물_일봉:
    @respx.mock
    def test_베이시스는_선물빼기현물로_직접_낸다(self, respx_mock, 토큰_발급):
        """KIS의 basis 필드는 이론가 − 현물(캐리)이라 쓰지 않는다."""
        respx_mock.get(DAILY_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output1": {
                "hts_kor_isnm": "K200 선물", "futs_prpr": "352.50", "kospi200_nmix": "350.00",
                "futs_prdy_ctrt": "1.25", "prdy_vrss_sign": "2", "dprt": "0.71",
                "hts_otst_stpl_qty": "300000", "otst_stpl_qty_icdc": "1500",
            }, "output2": []})
        )

        요약 = futures.fetch_daily("101W09", date(2026, 9, 1), date(2026, 9, 11)).summary

        assert 요약.basis == pytest.approx(2.5)
        assert 요약.change_rate == 1.25

    @respx.mock
    def test_하락_부호코드면_등락률이_음수가_된다(self, respx_mock, 토큰_발급):
        """ctrt는 크기만 오고 방향은 부호코드(4하한 5하락)로 온다."""
        respx_mock.get(DAILY_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output1": {
                "futs_prpr": "352.50", "kospi200_nmix": "350.00",
                "futs_prdy_ctrt": "1.25", "prdy_vrss_sign": "5",
            }, "output2": []})
        )

        assert futures.fetch_daily("101W09", date(2026, 9, 1), date(2026, 9, 11)).summary.change_rate == -1.25

    @respx.mock
    def test_일봉은_시각이_자정으로_고정된다(self, respx_mock, 토큰_발급):
        respx_mock.get(DAILY_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output1": {
                "futs_prpr": "352.50", "kospi200_nmix": "350.00",
            }, "output2": [{
                "stck_bsop_date": "20260911", "futs_prpr": "352.50", "futs_oprc": "350.00",
                "futs_hgpr": "353.00", "futs_lwpr": "349.50", "acml_vol": "120000",
            }]})
        )

        봉 = futures.fetch_daily("101W09", date(2026, 9, 1), date(2026, 9, 11)).candles[0]

        assert (봉.date, 봉.time) == ("2026-09-11", "00:00:00")

    @respx.mock
    def test_현물이_없으면_None(self, respx_mock, 토큰_발급):
        respx_mock.get(DAILY_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output1": {"futs_prpr": "352.50"}})
        )

        assert futures.fetch_daily("101W09", date(2026, 9, 1), date(2026, 9, 11)) is None


class Test선물_분봉:
    @respx.mock
    def test_야간의_24시_이상_시각은_익일로_되돌린다(self, respx_mock, 토큰_발급):
        """야간선물은 한 세션을 한 영업일로 묶으려고 자정 이후를 +24시간으로 보낸다(25:30 = 익일 01:30)."""
        respx_mock.get(MINUTE_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output2": [{
                "stck_bsop_date": "20260911", "stck_cntg_hour": "253000",
                "futs_prpr": "352.50", "futs_oprc": "352.00", "futs_hgpr": "353.00",
                "futs_lwpr": "351.50", "cntg_vol": "120",
            }]})
        )

        봉 = futures.fetch_minute("101W09", date(2026, 9, 11), "253000", futures.NIGHT)[0]

        assert (봉.date, 봉.time) == ("2026-09-12", "01:30:00")

    @respx.mock
    def test_정규장_시각은_그대로_둔다(self, respx_mock, 토큰_발급):
        respx_mock.get(MINUTE_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output2": [{
                "stck_bsop_date": "20260911", "stck_cntg_hour": "143000", "futs_prpr": "352.50",
            }]})
        )

        봉 = futures.fetch_minute("101W09", date(2026, 9, 11), time(14, 30))[0]

        assert (봉.date, 봉.time) == ("2026-09-11", "14:30:00")

    @respx.mock
    def test_시각_오름차순으로_정렬한다(self, respx_mock, 토큰_발급):
        respx_mock.get(MINUTE_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output2": [
                {"stck_bsop_date": "20260911", "stck_cntg_hour": "143000", "futs_prpr": "1"},
                {"stck_bsop_date": "20260911", "stck_cntg_hour": "090000", "futs_prpr": "1"},
            ]})
        )

        봉들 = futures.fetch_minute("101W09", date(2026, 9, 11), time(14, 30))

        assert [b.time for b in 봉들] == ["09:00:00", "14:30:00"]

    @respx.mock
    def test_오류면_빈_목록(self, respx_mock, 토큰_발급):
        respx_mock.get(MINUTE_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "1", "msg1": "오류"})
        )

        assert futures.fetch_minute("101W09", date(2026, 9, 11), time(14, 30)) == []


class Test선물_시세:
    @respx.mock
    def test_전일종가는_전일대비로_역산한다(self, respx_mock, 토큰_발급):
        """futs_prdy_clpr은 야간(CM)에서 정규장 종가가 아닌 값이 온다."""
        respx_mock.get(PRICE_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output1": {
                "hts_kor_isnm": "K200 선물", "futs_prpr": "352.50",
                "futs_prdy_vrss": "2.50", "futs_prdy_ctrt": "0.71", "prdy_vrss_sign": "2",
                "acml_vol": "120000",
            }})
        )

        시세 = futures.fetch_price("101W09", futures.NIGHT)

        assert 시세.price_change == 2.50
        assert 시세.prev_close == pytest.approx(350.00)

    @respx.mock
    def test_OHLC가_없으면_현재가로_채운다(self, respx_mock, 토큰_발급):
        respx_mock.get(PRICE_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output1": {
                "futs_prpr": "352.50", "futs_prdy_vrss": "0", "prdy_vrss_sign": "3",
            }})
        )

        시세 = futures.fetch_price("101W09", futures.DAY)

        assert (시세.open, 시세.high, 시세.low) == (352.50, 352.50, 352.50)

    @respx.mock
    def test_output1이_없으면_None(self, respx_mock, 토큰_발급):
        respx_mock.get(PRICE_URL).mock(return_value=httpx.Response(200, json={"rt_cd": "0"}))

        assert futures.fetch_price("101W09", futures.DAY) is None


class Test선물_투자자:
    @respx.mock
    def test_투자자별_계약_순매수를_읽는다(self, respx_mock, 토큰_발급):
        respx_mock.get(INVESTOR_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output": [{
                "frgn_ntby_qty": "1500", "prsn_ntby_qty": "-800", "orgn_ntby_qty": "-700",
                "scrt_ntby_qty": "-300", "pe_fund_ntby_vol": "-100", "etc_corp_ntby_vol": "50",
            }]})
        )

        결과 = futures.fetch_investors(Market.KOSPI)

        assert (결과.foreign, 결과.individual, 결과.institution) == (1500, -800, -700)
        # 사모펀드·기타법인만 필드명이 _vol다
        assert (결과.private_equity, 결과.other_corp) == (-100, 50)

    @respx.mock
    def test_시장별로_업종코드가_갈린다(self, respx_mock, 토큰_발급):
        route = respx_mock.get(INVESTOR_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output": []})
        )

        futures.fetch_investors(Market.KOSDAQ)

        url = str(route.calls[0].request.url)
        assert "fid_input_iscd=KQI" in url and "fid_input_iscd_2=F002" in url

    @respx.mock
    def test_행이_없으면_None(self, respx_mock, 토큰_발급):
        respx_mock.get(INVESTOR_URL).mock(
            return_value=httpx.Response(200, json={"rt_cd": "0", "output": []})
        )

        assert futures.fetch_investors(Market.KOSPI) is None
