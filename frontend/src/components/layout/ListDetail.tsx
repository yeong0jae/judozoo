import { useEffect, useRef, type ReactNode } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";

/** 목록과 상세를 나란히 두는 폭 — Tailwind `lg`와 같아야 한다. */
const SIDE_BY_SIDE = "(min-width: 1024px)";

/**
 * 목록 + 상세 — 넓으면 왼쪽 목록·오른쪽 상세, 좁으면 한 화면씩 보여준다.
 * 주도주·눌림·돌파·시그널이 같은 틀을 쓴다. 두 칸은 각자 스크롤된다(지수·수급 화면과 같다).
 */
export default function ListDetail({
  list,
  detail,
  detailOpen,
}: {
  list: ReactNode;
  detail: ReactNode;
  /** 좁은 화면에서 상세를 보여줄지 — `useMobileDetail().open` */
  detailOpen: boolean;
}) {
  // 목록은 한 번이라도 상세에 다녀온 뒤에만 밀려 들어온다 — 첫 진입까지 움직이면 산만하다.
  // 클래스는 붙여 둔 채로 두고, `hidden`이 풀릴 때마다 애니메이션이 다시 돈다.
  const visited = useRef(false);
  if (detailOpen) visited.current = true;

  // 두 칸 모두 `relative` — 안의 absolute 요소(sr-only 등)가 스크롤 칸을 빠져나와 페이지 높이를 늘리지 않게.
  // 밀려 들어오는 동안 가로 스크롤이 생기지 않게 좁은 폭에서는 가로로 자른다.
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

/**
 * 좁은 화면의 "상세 열림" — 주소(`?view=detail`)에 둔다.
 *
 * 상태로만 두면 폰의 뒤로가기가 목록이 아니라 이전 페이지로 가 버린다.
 * 넓은 화면에서는 목록과 상세가 이미 같이 보여 기록을 쌓지 않는다.
 */
export function useMobileDetail() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const open = params.get("view") === "detail";
  const listScroll = useRef(0);

  // 상세는 맨 위부터, 목록은 보던 자리로
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
    // 이 화면 안에서 연 상세면 기록을 되돌리고, 주소로 바로 들어왔으면 파라미터만 걷어낸다
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
