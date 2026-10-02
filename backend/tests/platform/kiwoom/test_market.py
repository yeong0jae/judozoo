"""키움 시세 — 부호 표식, SOR(_AL) 접미사, 캐시 정책, 토큰 재시도."""

from datetime import date

import httpx
import pytest
import respx

from backend.platform.kiwoom import client, market

BASE = "https://api.kiwoom.com"
TOKEN_URL = f"{BASE}/oauth2/token"
RANK_URL = f"{BASE}/api/dostk/rkinfo"
STOCK_INFO_URL = f"{BASE}/api/dostk/stkinfo"
CHART_URL = f"{BASE}/api/dostk/chart"


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


def 순위행(code: str, name: str, cur_prc: str = "+71500", rank: str = "1", tamt: str = "1000",
         flu_rt: str = "+3.5", sig: str = "2", volume: str = "123456") -> dict:
    """`pred_pre_sig`는 등락부호 — 1:상한 2:상승 3:보합 4:하한 5:하락."""
    return {
        "stk_cd": code, "stk_nm": name, "cur_prc": cur_prc, "now_rank": rank,
        "flu_rt": flu_rt, "trde_prica": tamt, "pred_pre_sig": sig,
        "now_trde_qty": volume,
    }


class Test거래대금_상위:
    @respx.mock
    def test_순위와_거래대금을_읽는다(self, respx_mock, 토큰_발급):
        respx_mock.post(RANK_URL).mock(
            return_value=httpx.Response(200, json={
                "return_code": 0,
                "trde_prica_upper": [순위행("005930", "삼성전자", tamt="5000")],
            })
        )

        결과 = market.fetch_top_trading_value_stocks()

        assert len(결과) == 1
        s = 결과[0]
        assert (s.stock_code, s.stock_name) == ("005930", "삼성전자")
        assert s.current_price == 71500       # "+71500" → 부호 제거
        assert s.accumulated_trading_value == 5_000_000_000  # 백만원 단위 → 원
        assert s.accumulated_volume == 123456

    @respx.mock
    def test_등락부호로_상한가를_읽는다(self, respx_mock, 토큰_발급):
        respx_mock.post(RANK_URL).mock(
            return_value=httpx.Response(200, json={
                "return_code": 0,
                "trde_prica_upper": [
                    순위행("900001", "잠긴잡주", flu_rt="+29.94", sig="1"),
                    순위행("005930", "삼성전자", rank="2"),
                ],
            })
        )

        결과 = market.fetch_top_trading_value_stocks()

        assert [s.limit_up for s in 결과] == [True, False]

    @respx.mock
    def test_많이_올랐어도_부호가_상한이_아니면_상한가가_아니다(self, respx_mock, 토큰_발급):
        """신규상장 종목은 제한폭이 없어 부호 2인 채로 +150%가 온다 — 등락률로 가리면 틀린다."""
        respx_mock.post(RANK_URL).mock(
            return_value=httpx.Response(200, json={
                "return_code": 0,
                "trde_prica_upper": [순위행("0200G0", "한국제17호스팩", flu_rt="+153.00", sig="2")],
            })
        )

        결과 = market.fetch_top_trading_value_stocks()

        assert 결과[0].price_change_rate == 153.0
        assert 결과[0].limit_up is False

    @respx.mock
    def test_목록이_없으면_예외로_올린다(self, respx_mock, 토큰_발급):
        """빈 결과를 캐싱하면 후속 폴링이 TTL 동안 빈 목록을 재사용한다 — 그래서 던진다."""
        respx_mock.post(RANK_URL).mock(
            return_value=httpx.Response(200, json={"return_code": 0, "return_msg": "no data"})
        )

        with pytest.raises(RuntimeError, match="no list"):
            market.fetch_top_trading_value_stocks()

    @respx.mock
    def test_오류_코드면_예외로_올린다(self, respx_mock, 토큰_발급):
        respx_mock.post(RANK_URL).mock(
            return_value=httpx.Response(200, json={"return_code": 3, "return_msg": "한도 초과"})
        )

        with pytest.raises(RuntimeError, match="한도 초과"):
            market.fetch_top_trading_value_stocks()

    @respx.mock
    def test_토큰_무효면_캐시를_버리고_한_번_재시도한다(self, respx_mock, 토큰_발급):
        응답들 = [
            httpx.Response(200, json={"return_code": 8005, "return_msg": "Token이 유효하지 않습니다"}),
            httpx.Response(200, json={"return_code": 0, "trde_prica_upper": [순위행("005930", "삼성전자")]}),
        ]
        respx_mock.post(RANK_URL).mock(side_effect=응답들)

        결과 = market.fetch_top_trading_value_stocks()

        assert 결과[0].stock_code == "005930"

    @respx.mock
    def test_요청_개수만큼만_자른다(self, respx_mock, 토큰_발급):
        행들 = [순위행(f"00000{i}", f"종목{i}", rank=str(i + 1)) for i in range(5)]
        respx_mock.post(RANK_URL).mock(
            return_value=httpx.Response(200, json={"return_code": 0, "trde_prica_upper": 행들})
        )

        assert len(market.fetch_top_trading_value_stocks(count=3)) == 3


class Test종목_기본정보:
    @respx.mock
    def test_접미사가_없으면_SOR_통합_코드를_붙인다(self, respx_mock, 토큰_발급):
        """KRX 기본 코드는 정규장 종가에 멈춘다 — _AL이라야 프리·애프터가 반영된다."""
        route = respx_mock.post(STOCK_INFO_URL).mock(
            return_value=httpx.Response(200, json={
                "stk_cd": "005930", "stk_nm": "삼성전자", "cur_prc": "+71500",
                "flu_rt": "+1.5", "mac": "4200000", "open_pric": "71000",
                "base_pric": "70500", "high_pric": "72000", "low_pric": "70800",
                "trde_qty": "987654",
            })
        )

        detail = market.fetch_stock_detail("005930")

        import json
        assert json.loads(route.calls[0].request.content)["stk_cd"] == "005930_AL"
        assert detail.accumulated_volume == 987654

    @respx.mock
    def test_이미_접미사가_있으면_그대로_둔다(self, respx_mock, 토큰_발급):
        route = respx_mock.post(STOCK_INFO_URL).mock(
            return_value=httpx.Response(200, json={
                "stk_cd": "005930_NX", "stk_nm": "삼성전자", "cur_prc": "71500",
                "flu_rt": "0", "mac": "0", "open_pric": "0",
                "base_pric": "0", "high_pric": "0", "low_pric": "0",
            })
        )

        market.fetch_stock_detail("005930_NX")

        import json
        assert json.loads(route.calls[0].request.content)["stk_cd"] == "005930_NX"

    @respx.mock
    def test_실패하면_None(self, respx_mock, 토큰_발급):
        respx_mock.post(STOCK_INFO_URL).mock(return_value=httpx.Response(500))

        assert market.fetch_stock_detail("005930") is None

    @respx.mock
    def test_타임아웃이면_None(self, respx_mock, 토큰_발급):
        respx_mock.post(STOCK_INFO_URL).mock(side_effect=httpx.ReadTimeout("timeout"))

        assert market.fetch_stock_detail("005930") is None


class Test일봉:
    @respx.mock
    def test_일봉은_SOR_접미사를_붙이지_않는다(self, respx_mock, 토큰_발급):
        """ka10081은 _AL이면 빈 응답이 오는 경우가 있어 KRX 기본 코드로 부른다."""
        route = respx_mock.post(CHART_URL).mock(
            return_value=httpx.Response(200, json={"stk_dt_pole_chart_qry": []})
        )

        market.fetch_daily_candles("005930_AL", base_date=date(2026, 9, 11))

        import json
        assert json.loads(route.calls[0].request.content)["stk_cd"] == "005930"

    @respx.mock
    def test_전일대비로_등락률을_역산한다(self, respx_mock, 토큰_발급):
        """응답에 등락률이 없다 — pred_pre(종가-전일종가)와 종가로 만든다."""
        respx_mock.post(CHART_URL).mock(
            return_value=httpx.Response(200, json={"stk_dt_pole_chart_qry": [{
                "dt": "20260911", "open_pric": "70000", "high_pric": "72000",
                "low_pric": "69000", "cur_prc": "+71500", "trde_qty": "1000", "pred_pre": "+1500",
            }]})
        )

        봉 = market.fetch_daily_candles("005930", base_date=date(2026, 9, 11))[0]

        assert 봉.close_price == 71500
        assert 봉.date == date(2026, 9, 11)
        assert round(봉.change_rate, 4) == round(1500 / 70000 * 100, 4)

    @respx.mock
    def test_날짜가_비어있는_패딩_항목은_건너뛴다(self, respx_mock, 토큰_발급):
        respx_mock.post(CHART_URL).mock(
            return_value=httpx.Response(200, json={"stk_dt_pole_chart_qry": [
                {"dt": "20260911", "cur_prc": "71500", "open_pric": "1", "high_pric": "1",
                 "low_pric": "1", "trde_qty": "1", "pred_pre": "0"},
                {"dt": "", "cur_prc": "", "open_pric": "", "high_pric": "",
                 "low_pric": "", "trde_qty": "", "pred_pre": ""},
            ]})
        )

        assert len(market.fetch_daily_candles("005930", base_date=date(2026, 9, 11))) == 1


    @respx.mock
    def test_개수만_다른_조회는_한_번의_호출을_나눠_쓴다(self, respx_mock, 토큰_발급):
        """필터용 60개와 차트 200개는 키움에 보내는 요청이 같다 — 개수는 받은 뒤 자른다."""
        행 = {"cur_prc": "1", "open_pric": "1", "high_pric": "1", "low_pric": "1", "trde_qty": "1", "pred_pre": "0"}
        route = respx_mock.post(CHART_URL).mock(
            return_value=httpx.Response(200, json={"stk_dt_pole_chart_qry": [
                {**행, "dt": f"2026{m:02d}{d:02d}"} for m in (9, 8) for d in range(28, 0, -1)
            ]})
        )
        기준일 = date(2026, 9, 28)

        필터용 = market.fetch_daily_candles("005930", 3, 기준일)
        차트용 = market.fetch_daily_candles("005930", 5, 기준일)

        assert route.call_count == 1
        assert len(필터용) == 3 and len(차트용) == 5
        assert 차트용[:3] == 필터용


class Test분봉:
    @respx.mock
    def test_분봉은_SOR_통합_코드로_부른다(self, respx_mock, 토큰_발급):
        """KRX 기본은 정규장 봉만 준다 — _AL이라야 NXT 프리·애프터 봉이 함께 온다."""
        route = respx_mock.post(CHART_URL).mock(
            return_value=httpx.Response(200, json={"stk_min_pole_chart_qry": []})
        )

        market.fetch_historical_minute_candles("005930", date(2026, 9, 11))

        import json
        assert json.loads(route.calls[0].request.content)["stk_cd"] == "005930_AL"

    @respx.mock
    def test_거래대금을_종가곱거래량으로_근사한다(self, respx_mock, 토큰_발급):
        """ka10080 응답엔 거래대금 필드가 없다."""
        respx_mock.post(CHART_URL).mock(
            return_value=httpx.Response(200, json={"stk_min_pole_chart_qry": [{
                "cntr_tm": "20260911093000", "open_pric": "70000", "high_pric": "72000",
                "low_pric": "69000", "cur_prc": "-71500", "trde_qty": "100",
            }]})
        )

        봉 = market.fetch_historical_minute_candles("005930", date(2026, 9, 11))[0]

        assert 봉.close_price == 71500          # "-71500"은 하락 표식이지 음수가 아니다
        assert 봉.trading_value == 71500 * 100
        assert 봉.date_time.hour == 9 and 봉.date_time.minute == 30

    @respx.mock
    def test_실패하면_빈_목록(self, respx_mock, 토큰_발급):
        respx_mock.post(CHART_URL).mock(return_value=httpx.Response(500))

        assert market.fetch_historical_minute_candles("005930", date(2026, 9, 11)) == []


class Test가격_파서:
    @pytest.mark.parametrize(
        "입력,기대",
        [("+71500", 71500), ("-71500", 71500), ("71500", 71500), ("", 0), ("abc", 0)],
    )
    def test_부호는_등락_방향_표식이라_절대값으로_읽는다(self, 입력, 기대):
        assert market.parse_price(입력) == 기대


class TestNXT_상장_여부:
    @respx.mock
    def test_NX로_조회해_종목명이_오면_상장(self, respx_mock, 토큰_발급):
        route = respx_mock.post(STOCK_INFO_URL).mock(
            return_value=httpx.Response(200, json={"return_code": 0, "stk_nm": "SK하이닉스", "cur_prc": "+1834000"})
        )
        assert market.fetch_nxt_listed("000660_AL") is True
        assert route.calls.last.request.content == b'{"stk_cd":"000660_NX"}'

    @respx.mock
    def test_정상_응답에_종목명이_비어_있으면_비상장(self, respx_mock, 토큰_발급):
        respx_mock.post(STOCK_INFO_URL).mock(
            return_value=httpx.Response(200, json={"return_code": 0, "return_msg": "정상적으로 처리되었습니다", "stk_nm": "", "cur_prc": ""})
        )
        assert market.fetch_nxt_listed("036010") is False

    @respx.mock
    def test_오류_코드면_단정하지_않는다(self, respx_mock, 토큰_발급):
        respx_mock.post(STOCK_INFO_URL).mock(return_value=httpx.Response(200, json={"return_code": 1, "return_msg": "오류"}))
        assert market.fetch_nxt_listed("036010") is None

    @respx.mock
    def test_서버_오류면_단정하지_않는다(self, respx_mock, 토큰_발급):
        respx_mock.post(STOCK_INFO_URL).mock(return_value=httpx.Response(500))
        assert market.fetch_nxt_listed("036010") is None
