import { useEffect, useState, type ReactNode } from "react";
import { useLeadingStockCandidates, useOverseasRanking } from "../../api/queries";
import { useMinChangeRate, useOverseasMinChangeRate } from "../../lib/changeRate";
import { formatPct, formatPrice } from "../../lib/format";

/** 국내를 흘리는 시간대 — 08:00~20:00. 나머지(20:01~07:59)는 미국장이 도는 때라 해외를 흘린다. */
const DOMESTIC_START_HOUR = 8;
const DOMESTIC_END_HOUR = 20;

function isDomesticHours(now: Date): boolean {
  const h = now.getHours();
  // 20시대는 20:00 정각만 국내 — 20:01부터 해외로 넘긴다
  if (h === DOMESTIC_END_HOUR) return now.getMinutes() === 0;
  return h >= DOMESTIC_START_HOUR && h < DOMESTIC_END_HOUR;
}

/**
 * 헤더 시세 티커 — 장이 도는 쪽 주도주가 왼쪽으로 흐른다.
 *
 * 국내·해외 중 한쪽만 마운트한다. 둘 다 걸어두면 안 보이는 쪽까지 폴링해서다.
 * 등락률 임계값은 각 화면과 store로 공유해, 화면에서 바꾸면 티커도 즉시 따라온다.
 *
 * 끊김 없이 도는 원리 — 같은 목록을 두 벌 이어 붙이고 절반(-50%)만큼 민다.
 * 한 바퀴가 끝나면 두 번째 벌이 첫 벌 자리에 정확히 와 있어 이음매가 보이지 않는다.
 */
export default function HeaderTicker() {
  const [domestic, setDomestic] = useState(() => isDomesticHours(new Date()));

  // 경계(08:00 / 20:01)를 넘기면 저절로 바뀌게 — 헤더는 화면을 옮겨도 죽지 않는다
  useEffect(() => {
    const id = setInterval(() => setDomestic(isDomesticHours(new Date())), 60_000);
    return () => clearInterval(id);
  }, []);

  return domestic ? <DomesticTicker /> : <OverseasTicker />;
}

function DomesticTicker() {
  const [minChangeRate] = useMinChangeRate();
  const { data } = useLeadingStockCandidates(minChangeRate);
  const stocks = data?.stocks ?? [];
  return (
    <Track
      items={stocks.map((s) => ({
        key: s.stockCode,
        name: s.stockName,
        price: formatPrice(s.currentPrice),
        rate: s.priceChangeRate,
      }))}
    />
  );
}

function OverseasTicker() {
  const [minChangeRate] = useOverseasMinChangeRate();
  const { data } = useOverseasRanking(minChangeRate);
  const stocks = data ?? [];
  return (
    <Track
      items={stocks.map((s) => ({
        key: `${s.exchange}:${s.symbol}`,
        name: s.name,
        price: `$${s.price.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`,
        rate: s.rate,
      }))}
    />
  );
}

type Item = { key: string; name: string; price: string; rate: number };

function Track({ items }: { items: Item[] }) {
  if (items.length === 0) return null;

  // 두 벌을 이어 붙여야 -50%에서 이음매가 안 보인다
  const doubled = [...items, ...items];

  return (
    <div className="ticker-viewport min-w-0 flex-1" aria-label="주도주 시세">
      <div className="ticker-track">
        {doubled.map((s, i) => (
          <Tick key={`${s.key}-${i}`} name={s.name} price={s.price} rate={s.rate} />
        ))}
      </div>
    </div>
  );
}

function Tick({ name, price, rate }: { name: string; price: ReactNode; rate: number }) {
  const tone =
    rate > 0 ? "text-red-400" : rate < 0 ? "text-blue-400" : "text-zinc-500";
  return (
    // 폰은 한 칸을 좁혀야 한 화면에 두 종목이 걸린다 — 데스크톱은 원래 여백 그대로
    <span className="flex items-baseline gap-1.5 whitespace-nowrap border-r border-zinc-800/70 px-3 md:gap-2 md:px-5">
      <span className="text-[13px] font-medium text-zinc-300 md:text-[14px]">{name}</span>
      <span className="num text-[12px] text-zinc-500 md:text-[13px]">{price}</span>
      <span className={`num text-[12px] font-medium md:text-[13px] ${tone}`}>{formatPct(rate / 100)}</span>
    </span>
  );
}
