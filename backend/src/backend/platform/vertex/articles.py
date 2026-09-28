"""그라운딩 출처를 실제 기사로 — 전달 주소를 따라가 기사 URL과 페이지 제목을 얻는다.

Vertex가 주는 출처는 `vertexaisearch.cloud.google.com/grounding-api-redirect/…` 전달 주소와 도메인뿐이다.
화면에는 원문 링크와 기사 제목이 필요하고, 모델이 쓴 URL은 지어낸 것이라(026 §0) 서버가 직접 확인한다.
제목 다듬기(매체명·사이트 경로 떼기)는 도메인(`insight.domain.clean_title`)이 한다.
"""

import logging
import re
from dataclasses import dataclass

import httpx

from backend.library import metrics

log = logging.getLogger(__name__)

#: 제목은 문서 앞쪽에 있다 — 본문 전체를 받지 않는다
_HEAD_BYTES = 200_000
_OG_TITLE = re.compile(rb"""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)""", re.I)
_OG_SITE = re.compile(rb"""<meta[^>]+property=["']og:site_name["'][^>]+content=["']([^"']+)""", re.I)
_TITLE = re.compile(rb"<title[^>]*>(.*?)</title>", re.I | re.S)
_META_CHARSET = re.compile(rb"""<meta[^>]+charset=["']?([\w-]+)""", re.I)

_client: httpx.Client | None = None


@dataclass(frozen=True)
class Article:
    url: str
    title: str
    #: 매체명(`og:site_name`). 없으면 None — 부르는 쪽이 도메인으로 대신한다
    site: str | None = None


def get_client() -> httpx.Client:
    global _client
    if _client is None:
        _client = httpx.Client(
            headers={"User-Agent": "Mozilla/5.0"},
            transport=metrics.MeteredTransport("news", httpx.HTTPTransport(), label_endpoint=lambda r: r.url.host),
            timeout=10.0,
        )
    return _client


def resolve(redirect_uri: str) -> Article | None:
    """전달 주소 → (실제 URL, 페이지 제목). 어느 단계든 실패하면 None — 그 출처는 목록에서 빠진다."""
    try:
        hop = get_client().get(redirect_uri)
        url = hop.headers.get("location")
        if not url:
            return None
        with get_client().stream("GET", url, follow_redirects=True) as page:
            if page.status_code != 200:
                return None
            head = b""
            for chunk in page.iter_bytes():
                head += chunk
                if len(head) >= _HEAD_BYTES:
                    break
            final_url = str(page.url)
            charset = page.charset_encoding
    except httpx.HTTPError:
        log.info("출처를 따라가지 못함 %s", redirect_uri[:80], exc_info=True)
        return None
    encoding = charset or ((m := _META_CHARSET.search(head)) and m.group(1).decode("ascii", "ignore")) or "utf-8"
    # og:title이 먼저다 — <title>보다 매체명이 덜 붙어 있다
    title = _decode((m := _OG_TITLE.search(head)) and m.group(1) or (m := _TITLE.search(head)) and m.group(1), encoding)
    site = _decode((m := _OG_SITE.search(head)) and m.group(1), encoding)
    return Article(final_url, title, site or None) if title else None


def _decode(raw: bytes | None, encoding: str) -> str:
    """페이지 인코딩으로 푼다. 모르는 인코딩 이름이면 UTF-8로."""
    if not raw:
        return ""
    try:
        return raw.decode(encoding, errors="replace").strip()
    except LookupError:
        return raw.decode("utf-8", errors="replace").strip()


def reset() -> None:
    """테스트 격리용."""
    global _client
    if _client is not None:
        _client.close()
    _client = None
