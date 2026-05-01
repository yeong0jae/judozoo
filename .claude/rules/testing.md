# Testing Rules

## Kotest & MockK 테스트 컨벤션

### Spec 선택 기준

**FunSpec** 으로 통일한다.
- 관련 테스트는 `context("...")` 블록으로 그룹화한다
- `test`는 개별 케이스를 작성한다
- 구조: `context("기능 또는 시나리오") → test("케이스")`

### Description 작성 원칙

`context()` / `test()` 문자열은 **시나리오·의미**를 담는다. 메서드명·필드명 등 구현 디테일은 쓰지 않는다 — 리팩토링 시 테스트 이름까지 깨져 변경에 취약해진다. 도메인 개념(상태 이름, 시그널 종류 등 ubiquitous language)은 사용 OK.

### MockK 사용 기준

**mock 사용 O:**
- Application 레이어 테스트에서 Infrastructure 의존성(외부 Client) 격리
- Presentation 레이어 테스트에서 Application 의존성(Service) 격리
- 비결정적 의존성 격리 (시간, 랜덤, 외부 API)

**mock 사용 X:**
- Domain 레이어 — 순수 도메인 객체는 실제 인스턴스로 테스트한다
- Application 레이어 통합 테스트 — `@SpringBootTest` + Testcontainers로 실제 DB에서 테스트한다
- Infrastructure 레이어 통합 테스트 — 실제 DB(Testcontainers 등)를 사용한다

### SpringBootTest + Testcontainers 컨벤션

- `IntegrationTestBase`를 상속받아 사용한다 — MySQL 컨테이너 및 `@DynamicPropertySource`가 베이스 클래스에 정의되어 있어 모든 통합 테스트가 컨테이너를 공유한다
