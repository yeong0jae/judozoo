import { useLeadingStockCandidates } from "../../api/queries";
import { loadMinChangeRate } from "../../lib/changeRate";
import { formatPct, formatPrice } from "../../lib/format";

/**
 * 헤더 시세 티커 — 주도주 후보가 왼쪽으로 흐른다.
 *
 * 목록은 주도주 화면과 **같은 조회**를 쓴다(등락률 임계값이 쿼리 키라 같은 값을 읽는다).
 * 그래서 그 화면에 있을 땐 추가 호출이 없고, 다른 화면에서만 폴링 하나가 는다.
 *
 * 끊김 없이 도는 원리 — 같은 목록을 두 벌 이어 붙이고 절반(-50%)만큼 민다.
 * 한 바퀴가 끝나면 두 번째 벌이 첫 벌 자리에 정확히 와 있어 이음매가 보이지 않는다.
 */
export default function HeaderTicker() {
  // 임계값은 마운트 때 한 번만 읽는다 — 헤더는 화면 전환에도 살아남아 매 폴 다시 읽을 일이 없다.
  const { data } = useLeadingStockCandidates(loadMinChangeRate());
  const stocks = data?.stocks ?? [];
  if (stocks.length === 0) return null;

  const items = [...stocks, ...stocks];

  return (
    <div className="ticker-viewport min-w-0 flex-1" aria-label="주도주 시세">
      <div className="ticker-track">
        {items.map((s, i) => (
          <span
            // 두 벌을 이어 붙이므로 종목코드만으로는 키가 겹친다
            key={`${s.stockCode}-${i}`}
            className="flex items-baseline gap-2 whitespace-nowrap border-r border-zinc-800/70 px-5"
          >
            <span className="text-[14px] font-medium text-zinc-300">{s.stockName}</span>
            <span className="num text-[13px] text-zinc-500">{formatPrice(s.currentPrice)}</span>
            <span
              className={`num text-[13px] font-medium ${
                s.priceChangeRate > 0
                  ? "text-red-400"
                  : s.priceChangeRate < 0
                    ? "text-blue-400"
                    : "text-zinc-500"
              }`}
            >
              {formatPct(s.priceChangeRate / 100)}
            </span>
          </span>
        ))}
      </div>
    </div>
  );
}
