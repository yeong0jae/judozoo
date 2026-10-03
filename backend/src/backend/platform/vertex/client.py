"""Gemini on Vertex AI — REST `generateContent`.

SDK(`google-genai`) 대신 httpx로 직접 부른다. 다른 벤더 어댑터와 같은 모양이라 respx로 시험하고,
외부 호출 계측(`MeteredTransport`)도 그대로 탄다. 인증은 ADC — 운영은 VM 서비스 계정, 로컬은
`gcloud auth application-default login`. API 키가 없다.

두 가지 호출만 한다(026 §생성 파이프라인).
- `ground`: Google 검색을 켜고 **문장으로** 답하게 한다. 출처 목록은 이때만 온다
- `structure`: 검색 없이 **JSON으로** 답하게 한다. 그라운딩과 JSON을 한 호출에 묶으면 출처가 비고
  모델이 기사 URL을 지어낸다(026 §0)

429·5xx는 같은 호출 안에서 두 번 더 묻고, 401은 토큰을 새로 받아 한 번 더 묻는다. 그 밖의 실패는
종류(`VertexError.kind`)만 붙여 올린다 — 무엇을 다시 할지는 부르는 쪽이 정한다(026 §실패 처리).
"""

import json
import logging
import random
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Literal

import httpx

from backend.library import metrics
from backend.settings import get_settings

log = logging.getLogger(__name__)

_SCOPE = "https://www.googleapis.com/auth/cloud-platform"
#: 평소 가장 느린 응답이 30초 안팎이다. 이보다 길면 Vertex가 아픈 것으로 본다
_TIMEOUT = 60.0
#: 429·5xx에 같은 호출 안에서 다시 묻기 전 기다리는 시간(초). 각각 지터를 섞는다 — 동시에 돈 종목들이
#: 같은 순간에 거절당하면 같은 순간에 다시 물어 함께 실패하기 쉽다
_BACKOFF = (2.0, 5.0)

_client: httpx.Client | None = None
_credentials: Any = None
_lock = threading.Lock()


FailureKind = Literal["timeout", "unavailable", "client_error", "bad_answer"]


class VertexError(Exception):
    """응답을 쓸 수 없다. `kind`가 부르는 쪽의 대응을 가른다.

    - timeout: 시간 안에 답이 없다
    - unavailable: 429·5xx·연결 실패 — 같은 호출 안에서 다시 물어도 안 풀렸다
    - client_error: 400·403·404 등 — 우리 설정이 틀렸다. 사람이 고쳐야 한다
    - bad_answer: 답은 왔는데 쓸 수 없다(깨진 JSON, 차단, 후보 없음)
    """

    def __init__(self, kind: FailureKind, message: str) -> None:
        super().__init__(message)
        self.kind: FailureKind = kind


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


def _token(refresh: bool = False) -> str:
    """ADC 토큰. 만료 전까지 들고 있다가 필요할 때만 새로 받는다. 401을 받았으면 `refresh`로 새로 받는다."""
    global _credentials
    import google.auth
    import google.auth.transport.requests

    with _lock:
        if _credentials is None:
            _credentials, _ = google.auth.default(scopes=[_SCOPE])
        if refresh or not _credentials.valid:
            _credentials.refresh(google.auth.transport.requests.Request())
        return _credentials.token


def ground(prompt: str) -> Grounded:
    body = {
        "contents": [{"role": "user", "parts": [{"text": prompt}]}],
        "tools": [{"googleSearch": {}}],
        "generationConfig": {"thinkingConfig": {"thinkingLevel": "LOW"}},
    }
    data = _generate(body, "ground")
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
    data = _generate(body, "structure")
    text = _text(data["candidates"][0])
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError as exc:
        raise VertexError("bad_answer", f"structure JSON이 아닌 응답: {text[:200]}") from exc
    if not isinstance(parsed, dict):
        raise VertexError("bad_answer", f"structure 객체가 아닌 JSON: {text[:200]}")
    return parsed, _usage(data)


def _generate(body: dict, stage: str) -> dict:
    """`stage`(ground·structure)는 오류 문구 앞에 붙는다 — 두 호출 중 어디서 멈췄는지 행에서 바로 보이게."""
    s = get_settings().vertex
    path = f"/v1/projects/{s.project}/locations/{s.location}/publishers/google/models/{s.model}:generateContent"
    backoff = iter(_BACKOFF)
    refresh = False
    while True:
        try:
            response = get_client().post(path, json=body, headers={"Authorization": f"Bearer {_token(refresh)}"})
        except httpx.TimeoutException as exc:
            # 이미 오래 기다렸다 — 바로 다시 물어도 느리기 쉽다. 같은 호출 안에서는 다시 하지 않는다
            raise VertexError("timeout", f"{stage} 요청 실패: {type(exc).__name__}") from exc
        except httpx.HTTPError as exc:
            raise VertexError("unavailable", f"{stage} 요청 실패: {type(exc).__name__}") from exc
        status = response.status_code
        if status == 401 and not refresh:
            refresh = True
            continue
        if status == 429 or status >= 500:
            if (delay := next(backoff, None)) is not None:
                _wait(delay * random.uniform(0.5, 1.5))
                continue
            raise VertexError("unavailable", f"{stage} HTTP {status}: {response.text[:200]}")
        if status != 200:
            raise VertexError("client_error", f"{stage} HTTP {status}: {response.text[:200]}")
        break
    data = response.json()
    candidates = data.get("candidates") or []
    if not candidates or candidates[0].get("finishReason") not in (None, "STOP", "MAX_TOKENS"):
        reason = candidates[0].get("finishReason") if candidates else data.get("promptFeedback")
        raise VertexError("bad_answer", f"{stage} 쓸 수 있는 답이 없다: {reason}")
    return data


def _wait(seconds: float) -> None:
    time.sleep(seconds)


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
