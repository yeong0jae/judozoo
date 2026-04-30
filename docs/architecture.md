# 아키텍쳐

## 애플리케이션 아키텍쳐

### 4 Layered Architecture

도메인별로 분할(package by feature)하고, 각 도메인 안에 필요한 계층만 둔다.

| 계층 | 책임 |
|------|------|
| `presentation` | HTTP/STOMP 엔드포인트. 요청 검증과 DTO 변환. |
| `application` | 유스케이스 오케스트레이션. **도메인 간 호출은 이 계층끼리만**. |
| `domain` | 순수 도메인 모델·룰 (sealed class, 상태 전이, 매매 룰 함수). 외부 의존 없음. |
| `infrastructure` | 영속화·외부 API·스케줄러. 같은 도메인 `domain`의 추상을 구현/저장. |

#### 의존 방향

```
presentation ──▶ application ──▶ domain ◀── infrastructure
```

- `domain`은 같은 도메인의 어떤 계층에도 의존하지 않는다

---

## 시스템 아키텍쳐

### 데이터 흐름

