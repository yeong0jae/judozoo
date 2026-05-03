# Testing Rules

### Kotest Convention

Use **FunSpec** exclusively.
- Group related tests with `context("...")` blocks
- Write individual cases with `test`
- Structure: `context("feature or scenario") → test("case")`

### Description principles

`context()` / `test()` strings must convey **scenario and meaning**. Write them in **Korean**. Do not include implementation details such as method names or field names — they make tests brittle when refactoring. Domain concepts (state names, signal types, and other ubiquitous language) are fine to use.

### Per-layer guidelines

1. Domain Layer
- Tests must be the purest and most independent.
- Do not load the Spring context; do not use MockK — construct real domain objects and test against them.

2. Infrastructure Layer
- JPA Repository: use `@DataJpaTest` to verify query methods and mapping logic.
- External API: use WireMock to simulate external-server response scenarios (success, failure, timeout).

3. Application Layer
- All integration tests extend `IntegrationTestBase` (Testcontainers) and run against the defined MySQL container environment.
- Verify the collaboration between multiple components; isolate only the uncontrollable areas (e.g., external API integrations) with MockK.
- External API clients (e.g., `KisRestClient`) must be mocked via `@TestConfiguration + @Primary` — never use WireMock at this layer.
  WireMock belongs to the Infrastructure/Platform layer, where the HTTP client itself is under test.
  Application tests verify business logic, not HTTP parsing.

4. Presentation Layer
- Focus on controller mapping and DTO validation.
- Mock the service layer with MockK to keep API-contract verification fast.
