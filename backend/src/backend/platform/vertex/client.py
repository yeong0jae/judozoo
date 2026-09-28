"""Gemini on Vertex AI — REST `generateContent`.

SDK(`google-genai`) 대신 httpx로 직접 부른다. 다른 벤더 어댑터와 같은 모양이라 respx로 시험하고,
외부 호출 계측(`MeteredTransport`)도 그대로 탄다. 인증은 ADC — 운영은 VM 서비스 계정, 로컬은
`gcloud auth application-default login`. API 키가 없다.

두 가지 호출만 한다(026 §생성 파이프라인).
- `ground`: Google 검색을 켜고 **문장으로** 답하게 한다. 출처 목록은 이때만 온다
- `structure`: 검색 없이 **JSON으로** 답하게 한다. 그라운딩과 JSON을 한 호출에 묶으면 출처가 비고
  모델이 기사 URL을 지어낸다(026 §0)
"""

import json
import logging
import threading
from dataclasses import dataclass, field
from typing import Any

import httpx

from backend.library import metrics
from backend.settings import get_settings

log = logging.getLogger(__name__)

_SCOPE = "https://www.googleapis.com/auth/cloud-platform"
#: 검색까지 도는 호출은 10~20초 걸린다
_TIMEOUT = 60.0

_client: httpx.Client | None = None
_credentials: Any = None
_lock = threading.Lock()


class VertexError(Exception):
    """응답을 쓸 수 없다 — HTTP 오류, 후보 없음, 차단. 부르는 쪽은 실패로 기록하고 3분 뒤 한 번 더 한다."""


@dataclass(frozen=True)
class Usage:
    input_tokens: int = 0
    output_tokens: int = 0
    thoughts_tokens: int = 0
    search_queries: int = 0


@dataclass(frozen=True)
class GroundedSource:
    """그라운딩 출처 한 건. `uri`는 기사 주소가 아니라 Vertex 전달 주소라 따라가야 실제 URL이 나온다."""

    domain: str
    uri: str


@dataclass(frozen=True)
class Grounded:
    text: str
    sources: list[GroundedSource] = field(default_factory=list)
    usage: Usage = Usage()


def get_client() -> httpx.Client:
    global _client
    if _client is None:
        _client = httpx.Client(
            base_url="https://aiplatform.googleapis.com",
            transport=metrics.MeteredTransport("vertex", httpx.HTTPTransport(), label_endpoint=lambda r: ":generateContent"),
            timeout=_TIMEOUT,
        )
    return _client


def _token() -> str:
    """ADC 토큰. 만료 전까지 들고 있다가 필요할 때만 새로 받는다."""
    global _credentials
    import google.auth
    import google.auth.transport.requests

    with _lock:
        if _credentials is None:
            _credentials, _ = google.auth.default(scopes=[_SCOPE])
        if not _credentials.valid:
            _credentials.refresh(google.auth.transport.requests.Request())
        return _credentials.token


def ground(prompt: str) -> Grounded:
    body = {
        "contents": [{"role": "user", "parts": [{"text": prompt}]}],
        "tools": [{"googleSearch": {}}],
        "generationConfig": {"thinkingConfig": {"thinkingLevel": "LOW"}},
    }
    data = _generate(body)
    candidate = data["candidates"][0]
    meta = candidate.get("groundingMetadata") or {}
    sources = [
        GroundedSource(domain=web.get("domain") or web.get("title") or "", uri=web["uri"])
        for chunk in meta.get("groundingChunks") or []
        if (web := chunk.get("web")) and web.get("uri")
    ]
    usage = _usage(data, search_queries=len(meta.get("webSearchQueries") or []))
    return Grounded(text=_text(candidate), sources=sources, usage=usage)


def structure(prompt: str, schema: dict) -> tuple[dict, Usage]:
    body = {
        "contents": [{"role": "user", "parts": [{"text": prompt}]}],
        "generationConfig": {
            "responseMimeType": "application/json",
            "responseSchema": schema,
            "thinkingConfig": {"thinkingLevel": "LOW"},
        },
    }
    data = _generate(body)
    text = _text(data["candidates"][0])
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError as exc:
        raise VertexError(f"JSON이 아닌 응답: {text[:200]}") from exc
    if not isinstance(parsed, dict):
        raise VertexError(f"객체가 아닌 JSON: {text[:200]}")
    return parsed, _usage(data)


def _generate(body: dict) -> dict:
    s = get_settings().vertex
    path = f"/v1/projects/{s.project}/locations/{s.location}/publishers/google/models/{s.model}:generateContent"
    try:
        response = get_client().post(path, json=body, headers={"Authorization": f"Bearer {_token()}"})
    except httpx.HTTPError as exc:
        raise VertexError(f"요청 실패: {type(exc).__name__}") from exc
    if response.status_code != 200:
        raise VertexError(f"HTTP {response.status_code}: {response.text[:200]}")
    data = response.json()
    candidates = data.get("candidates") or []
    if not candidates or candidates[0].get("finishReason") not in (None, "STOP", "MAX_TOKENS"):
        reason = candidates[0].get("finishReason") if candidates else data.get("promptFeedback")
        raise VertexError(f"쓸 수 있는 답이 없다: {reason}")
    return data


def _text(candidate: dict) -> str:
    parts = (candidate.get("content") or {}).get("parts") or []
    return "".join(p.get("text", "") for p in parts if not p.get("thought")).strip()


def _usage(data: dict, search_queries: int = 0) -> Usage:
    u = data.get("usageMetadata") or {}
    usage = Usage(
        input_tokens=u.get("promptTokenCount", 0),
        output_tokens=u.get("candidatesTokenCount", 0),
        thoughts_tokens=u.get("thoughtsTokenCount", 0),
        search_queries=search_queries,
    )
    model = get_settings().vertex.model
    metrics.LLM_TOKENS.labels(model, "input").inc(usage.input_tokens)
    metrics.LLM_TOKENS.labels(model, "output").inc(usage.output_tokens)
    metrics.LLM_TOKENS.labels(model, "thoughts").inc(usage.thoughts_tokens)
    metrics.LLM_SEARCH_QUERIES.labels(model).inc(usage.search_queries)
    return usage


def reset() -> None:
    """테스트 격리용."""
    global _client, _credentials
    if _client is not None:
        _client.close()
    _client = None
    _credentials = None
