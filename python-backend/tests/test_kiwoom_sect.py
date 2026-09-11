"""키움 업종 — 지수(ka20001), 업종 투자자 순매수(ka10051), 테마(ka90001)."""

from datetime import date

import httpx
import pytest
import respx

from backend.platform.kiwoom import client, index, sector_investor, theme
from backend.stock.domain import Market

BASE = "https://api.kiwoom.com"
TOKEN_URL = f"{BASE}/oauth2/token"
SECT_URL = f"{BASE}/api/dostk/sect"
THEME_URL = f"{BASE}/api/dostk/thme"


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


class Test업종_지수:
    @respx.mock
    def test_지수값은_절대값_등락률은_부호를_살린다(self, respx_mock, 토큰_발급):
        """cur_prc 앞 부호는 등락 방향 표식 — 지수 레벨은 음수일 수 없다."""
        respx_mock.post(SECT_URL).mock(
            return_value=httpx.Response(200, json={
                "return_code": 0, "cur_prc": "-2653.81", "flu_rt": "-1.25",
            })
        )

        결과 = index.fetch_index("001")

        assert 결과.current_value == 2653.81
        assert 결과.change_rate == -1.25

    @respx.mock
    def test_오류_코드면_None(self, respx_mock, 토큰_발급):
        respx_mock.post(SECT_URL).mock(
            return_value=httpx.Response(200, json={"return_code": 3, "return_msg": "오류"})
        )

        assert index.fetch_index("001") is None

    @respx.mock
    def test_타임아웃이면_None(self, respx_mock, 토큰_발급):
        respx_mock.post(SECT_URL).mock(side_effect=httpx.ReadTimeout("timeout"))

        assert index.fetch_index("001") is None

    @respx.mock
    def test_10초_틱에_조회일을_붙여_시각을_만든다(self, respx_mock, 토큰_발급):
        respx_mock.post(SECT_URL).mock(
            return_value=httpx.Response(200, json={
                "return_code": 0, "cur_prc": "+2653.81", "flu_rt": "+1.25",
                "inds_cur_prc_tm": [
                    {"tm_n": "143000", "cur_prc_n": "-2650.00", "trde_qty_n": "1200"},
                    {"tm_n": "090000", "cur_prc_n": "+2600.00", "trde_qty_n": "800"},
                ],
            })
        )

        결과 = index.fetch_index_intraday_for(Market.KOSPI, date(2026, 9, 11))

        assert [t.at.strftime("%H:%M:%S") for t in 결과.ticks] == ["09:00:00", "14:30:00"]  # 오름차순 정렬
        assert 결과.ticks[1].value == 2650.00  # 부호 제거
        assert 결과.ticks[0].at.date() == date(2026, 9, 11)

    @respx.mock
    def test_시각_형식이_어긋난_틱은_버린다(self, respx_mock, 토큰_발급):
        respx_mock.post(SECT_URL).mock(
            return_value=httpx.Response(200, json={
                "return_code": 0, "cur_prc": "2653.81", "flu_rt": "0",
                "inds_cur_prc_tm": [
                    {"tm_n": "1430", "cur_prc_n": "2650.00"},      # 6자리가 아니다
                    {"tm_n": "143000", "cur_prc_n": "2650.00"},
                ],
            })
        )

        assert len(index.fetch_index_intraday("001", "0", date(2026, 9, 11)).ticks) == 1

    @respx.mock
    def test_시장별로_업종코드가_갈린다(self, respx_mock, 토큰_발급):
        route = respx_mock.post(SECT_URL).mock(
            return_value=httpx.Response(200, json={"return_code": 0, "cur_prc": "1", "flu_rt": "0"})
        )

        index.fetch_index_intraday_for(Market.KOSDAQ, date(2026, 9, 11))

        import json
        보낸것 = json.loads(route.calls[0].request.content)
        assert (보낸것["inds_cd"], 보낸것["mrkt_tp"]) == ("101", "1")


class Test업종_투자자_순매수:
    def 응답(self, **덮어쓰기) -> dict:
        행 = {
            "inds_nm": "종합(KOSPI)", "cur_prc": "+265381", "flu_rt": "+352",
            "frgnr_netprps": "+255", "orgn_netprps": "-622", "ind_netprps": "367",
            "etc_corp_netprps": "0", "sc_netprps": "-100", "invtrt_netprps": "-200",
            "endw_netprps": "-50", "samo_fund_netprps": "-72", "insrnc_netprps": "-100",
            "bank_netprps": "-50", "jnsinkm_netprps": "-50",
        }
        행.update(덮어쓰기)
        return {"return_code": 0, "inds_netprps": [행]}

    @respx.mock
    def test_지수값과_등락률은_100으로_나눈다(self, respx_mock, 토큰_발급):
        """ka10051의 cur_prc/flu_rt는 소수점이 빠진 정수 — 2653.81이 "+265381"."""
        respx_mock.post(SECT_URL).mock(return_value=httpx.Response(200, json=self.응답()))

        결과 = sector_investor.fetch_sector_net_buy("0")

        assert 결과.index_value == 2653.81
        assert 결과.change_rate == 3.52

    @respx.mock
    def test_순매수는_부호를_살린_억원(self, respx_mock, 토큰_발급):
        respx_mock.post(SECT_URL).mock(return_value=httpx.Response(200, json=self.응답()))

        결과 = sector_investor.fetch_sector_net_buy("0")

        assert (결과.foreign_eok, 결과.institution_eok, 결과.individual_eok) == (255, -622, 367)

    @respx.mock
    def test_종합_행이_없으면_첫_행으로_폴백한다(self, respx_mock, 토큰_발급):
        respx_mock.post(SECT_URL).mock(
            return_value=httpx.Response(200, json=self.응답(inds_nm="반도체"))
        )

        assert sector_investor.fetch_sector_net_buy("0") is not None

    @respx.mock
    def test_행이_없으면_None(self, respx_mock, 토큰_발급):
        respx_mock.post(SECT_URL).mock(
            return_value=httpx.Response(200, json={"return_code": 0, "inds_netprps": []})
        )

        assert sector_investor.fetch_sector_net_buy("0") is None

    @respx.mock
    def test_기준일자를_주면_요청에_싣는다(self, respx_mock, 토큰_발급):
        route = respx_mock.post(SECT_URL).mock(return_value=httpx.Response(200, json=self.응답()))

        sector_investor.fetch_sector_net_buy("1", "20260911")

        import json
        assert json.loads(route.calls[0].request.content)["base_dt"] == "20260911"


class Test종목_테마:
    @respx.mock
    def test_테마명_목록을_돌려준다(self, respx_mock, 토큰_발급):
        respx_mock.post(THEME_URL).mock(
            return_value=httpx.Response(200, json={
                "return_code": 0,
                "thema_grp": [{"thema_nm": "반도체"}, {"thema_nm": "AI"}, {"thema_nm": "  "}],
            })
        )

        assert theme.fetch_themes_for_stock("005930") == ["반도체", "AI"]

    @respx.mock
    def test_접미사를_떼고_6자리로_조회한다(self, respx_mock, 토큰_발급):
        """_AL 등이 달라도 같은 종목이면 캐시를 맞히도록 정규화한다."""
        route = respx_mock.post(THEME_URL).mock(
            return_value=httpx.Response(200, json={"return_code": 0, "thema_grp": []})
        )

        theme.fetch_themes_for_stock("005930_AL")

        import json
        assert json.loads(route.calls[0].request.content)["stk_cd"] == "005930"

    @respx.mock
    def test_오류_코드면_빈_목록(self, respx_mock, 토큰_발급):
        respx_mock.post(THEME_URL).mock(
            return_value=httpx.Response(200, json={"return_code": 3, "return_msg": "오류"})
        )

        assert theme.fetch_themes_for_stock("005930") == []

    @respx.mock
    def test_타임아웃이면_빈_목록(self, respx_mock, 토큰_발급):
        respx_mock.post(THEME_URL).mock(side_effect=httpx.ReadTimeout("timeout"))

        assert theme.fetch_themes_for_stock("005930") == []
