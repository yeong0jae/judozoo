#!/usr/bin/env python3
"""Kotlin ↔ Python 응답 동등성 비교.

각 마일스톤의 완료 조건이다. "돌아간다"가 아니라 "같은 JSON이 나온다"를 본다.

    python scripts/compare_responses.py /api/news/stock/005930
    python scripts/compare_responses.py --paths-file paths.txt

시세·타임스탬프처럼 호출 시점마다 달라지는 필드는 비교에서 뺀다. 다만 **경로를
출력**하므로, 무엇을 제외했는지가 결과에 남는다. 조용히 넘어가면 진짜 차이를 놓친다.
"""

import argparse
import json
import sys
from typing import Any

import httpx

# 시변 필드 — 값이 달라도 차이로 보지 않는다. 엔드포인트별로 늘려간다.
VOLATILE_KEYS = {
    "asOf",
    "at",
    "currentPrice",
    "fetchedAt",
    "timestamp",
    "ts",
    "updatedAt",
}


def flatten(value: Any, prefix: str = "") -> dict[str, Any]:
    """중첩 JSON을 `a.b[0].c` 형태의 평면 딕셔너리로 편다."""
    if isinstance(value, dict):
        out: dict[str, Any] = {}
        for k, v in value.items():
            out |= flatten(v, f"{prefix}.{k}" if prefix else k)
        return out
    if isinstance(value, list):
        out = {}
        for i, v in enumerate(value):
            out |= flatten(v, f"{prefix}[{i}]")
        return out
    return {prefix: value}


def compare(path: str, kotlin_base: str, python_base: str) -> tuple[bool, list[str]]:
    lines: list[str] = []
    try:
        left = httpx.get(f"{kotlin_base}{path}", timeout=30.0)
        right = httpx.get(f"{python_base}{path}", timeout=30.0)
    except httpx.HTTPError as e:
        return False, [f"  요청 실패: {e}"]

    if left.status_code != right.status_code:
        lines.append(f"  상태 코드 다름: Kotlin {left.status_code} vs Python {right.status_code}")
        return False, lines

    flat_left = flatten(left.json())
    flat_right = flatten(right.json())

    only_left = sorted(set(flat_left) - set(flat_right))
    only_right = sorted(set(flat_right) - set(flat_left))
    for key in only_left:
        lines.append(f"  Kotlin에만 있음: {key}")
    for key in only_right:
        lines.append(f"  Python에만 있음: {key}")

    skipped: list[str] = []
    for key in sorted(set(flat_left) & set(flat_right)):
        leaf = key.rsplit(".", 1)[-1].split("[")[0]
        if leaf in VOLATILE_KEYS:
            if flat_left[key] != flat_right[key]:
                skipped.append(key)
            continue
        if flat_left[key] != flat_right[key]:
            lines.append(f"  값 다름 {key}: {flat_left[key]!r} vs {flat_right[key]!r}")

    if skipped:
        lines.append(f"  (시변 필드 {len(skipped)}개 제외: {', '.join(skipped[:5])}…)")

    ok = not any(
        line.startswith(("  Kotlin에만", "  Python에만", "  값 다름")) for line in lines
    )
    return ok, lines


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="*", help="비교할 API 경로 (예: /api/health)")
    parser.add_argument("--paths-file", help="경로가 줄바꿈으로 나열된 파일")
    parser.add_argument("--kotlin", default="http://localhost:8080")
    parser.add_argument("--python", default="http://localhost:8000")
    args = parser.parse_args()

    paths = list(args.paths)
    if args.paths_file:
        with open(args.paths_file, encoding="utf-8") as f:
            paths += [ln.strip() for ln in f if ln.strip() and not ln.startswith("#")]
    if not paths:
        parser.error("비교할 경로를 하나 이상 지정해야 한다")

    failed = 0
    for path in paths:
        ok, lines = compare(path, args.kotlin, args.python)
        print(f"{'OK  ' if ok else 'DIFF'} {path}")
        for line in lines:
            print(line)
        if not ok:
            failed += 1

    print(f"\n{len(paths)}건 중 {len(paths) - failed}건 일치")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
