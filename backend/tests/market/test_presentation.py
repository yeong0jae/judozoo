from backend.market import application
from backend.market.application import MacroQuotes, QuoteResult


def _시세(price: float, prev_close: float) -> QuoteResult:
    change = price - prev_close
    return QuoteResult(
        price=price, prev_close=prev_close, price_change=change, change_rate=change / prev_close * 100
    )


class Test매크로_시세:
    def test_미국채_10년_금리가_다른_지표와_함께_실린다(self, client, mocker):
        mocker.patch.object(
            application,
            "macro_quotes",
            return_value=MacroQuotes(
                usd_krw=_시세(1392.4, 1389.5),
                wti=_시세(68.12, 68.84),
                vix=_시세(15.33, 15.67),
                us10y=_시세(5.209, 5.162),
            ),
        )

        데이터 = client.get("/api/market/macro/quotes").json()["data"]

        assert set(데이터) == {"usdKrw", "wti", "vix", "us10y"}
        assert 데이터["us10y"]["price"] == 5.209
        assert 데이터["us10y"]["prevClose"] == 5.162

    def test_한_지표만_실패하면_그것만_비운다(self, client, mocker):
        mocker.patch.object(
            application,
            "macro_quotes",
            return_value=MacroQuotes(usd_krw=_시세(1392.4, 1389.5), wti=None, vix=None, us10y=None),
        )

        데이터 = client.get("/api/market/macro/quotes").json()["data"]

        assert 데이터["us10y"] is None
        assert 데이터["usdKrw"]["price"] == 1392.4
