import { useKospiIndex } from "../../api/queries";
import MarketIndexBadge from "./MarketIndexBadge";

/** 헤더의 KOSPI 종합지수 배지. */
export default function KospiIndexBadge() {
  const { data, isLoading } = useKospiIndex();
  return <MarketIndexBadge label="KOSPI" data={data} isLoading={isLoading} />;
}
