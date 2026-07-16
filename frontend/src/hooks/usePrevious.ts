import { useEffect, useRef } from "react";

/** 직전 렌더에서의 값을 돌려준다. 첫 렌더에는 undefined. */
export function usePrevious<T>(value: T): T | undefined {
  const ref = useRef<T>(undefined);
  useEffect(() => {
    ref.current = value;
  });
  return ref.current;
}
