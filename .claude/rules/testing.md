# Testing Rules

Backend tests are JUnit 5 + Kotest assertions + MockK. Run with `./gradlew test` from `backend/`;
`./gradlew unitTest` skips the Docker-backed integration tests.

### Convention

Group related cases in a `@Nested inner class <이름>` inside the file's test class and write each case as a
backtick method `` @Test fun `<설명>`() ``.

```kotlin
class FeedbackServiceTest {
    @Nested
    inner class 이슈_메모 {
        @Test
        fun `작성하고 그날 목록에서 읽는다`() { ... }
    }
}
```

### Description principles

Nested class and test names must convey **scenario and meaning**, written in **Korean**. Do not encode implementation details such as function names or field names — they make tests brittle when refactoring. Domain concepts (signal types, filter names, and other ubiquitous language) are fine to use. The file's top-level class is `<ClassUnderTest>Test`.

### Layout

`src/test/kotlin/judozoo` mirrors `src/main/kotlin/judozoo` one-to-one: `auth/presentation/LoginGateFilter.kt` → `auth/presentation/LoginGateFilterTest.kt`.

### Per-layer guidelines

1. Domain (`domain/`)
- Tests must be the purest and most independent.
- Do not start Spring, touch the DB, or mock anything — construct real objects and assert on them.

2. Platform / Infrastructure (`platform/**`, `infrastructure/`)
- External API: use **WireMock** (`SharedWireMock` — one server per JVM, reset per test) to simulate external-server response scenarios (success, failure, timeout, malformed payload).
- This is where broker response traps belong — sign variants, time offsets, exchange codes. Assert on the parsed result, not on the request.

3. Application (`application/`)
- DB-backed tests are `@IntegrationTest` (Testcontainers MySQL, schema built by Flyway, scheduling off, tagged `integration`).
- Verify collaboration between components; isolate only the uncontrollable parts (external API calls) with MockK (`@MockkBean` in Spring contexts).
- **Never use WireMock at this layer.** It belongs to platform/infrastructure, where the HTTP client itself is under test. Application tests verify business logic, not HTTP parsing.
- Inject a movable `Clock` instead of sleeping or reading the wall clock.

4. Presentation (`presentation/`)
- Use `@WebMvcTest` + `MockMvc`. Focus on route mapping, query/path validation, and response shape (`JsonCompareMode.STRICT` for the API contract).
- Mock the application layer with `@MockkBean` to keep API-contract verification fast. `LoggedIn` gives a signed session cookie.
