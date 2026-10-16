import { useEffect, useRef, type ReactNode } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";

const SIDE_BY_SIDE = "(min-width: 1024px)";

export default function ListDetail({
  list,
  detail,
  detailOpen,
}: {
  list: ReactNode;
  detail: ReactNode;
  detailOpen: boolean;
}) {
  const visited = useRef(false);
  if (detailOpen) visited.current = true;

  return (
    <div className="max-lg:overflow-x-clip lg:flex lg:h-[calc(100dvh-6.5rem)] lg:min-h-[40rem]">
      <aside
        className={`${detailOpen ? "hidden" : ""} ${visited.current ? "pane-from-left" : ""} relative shrink-0 lg:block lg:w-[28rem] lg:overflow-y-auto lg:border-r lg:border-zinc-800 lg:pr-3 xl:w-[32rem] 2xl:w-[37.5rem]`}
      >
        {list}
      </aside>
      <div className={`${detailOpen ? "" : "hidden"} pane-from-right relative min-w-0 flex-1 pb-6 lg:block lg:overflow-y-auto lg:pl-8 lg:pr-1`}>
        {detail}
      </div>
    </div>
  );
}

export function useMobileDetail() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const open = params.get("view") === "detail";
  const listScroll = useRef(0);

  useEffect(() => {
    window.scrollTo(0, open ? 0 : listScroll.current);
  }, [open]);

  const show = () => {
    if (window.matchMedia(SIDE_BY_SIDE).matches || open) return;
    listScroll.current = window.scrollY;
    const next = new URLSearchParams(params);
    next.set("view", "detail");
    setParams(next);
  };

  const hide = () => {
    if ((window.history.state?.idx ?? 0) > 0) {
      navigate(-1);
      return;
    }
    const next = new URLSearchParams(params);
    next.delete("view");
    setParams(next, { replace: true });
  };

  return { open, show, hide };
}
