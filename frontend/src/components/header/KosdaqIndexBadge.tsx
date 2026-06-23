import { useKosdaqIndex } from "../../api/queries";
import MarketIndexBadge from "./MarketIndexBadge";

/** 헤더의 KOSDAQ 종합지수 배지. */
export default function KosdaqIndexBadge() {
  const { data, isLoading } = useKosdaqIndex();
  return <MarketIndexBadge label="KOSDAQ" data={data} isLoading={isLoading} />;
}
