# Testing Rules

## Kotest & MockK Conventions

### Spec selection

Use **FunSpec** exclusively.
- Group related tests with `context("...")` blocks
- Write individual cases with `test`
- Structure: `context("feature or scenario") → test("case")`

### Description principles

`context()` / `test()` strings must convey **scenario and meaning**. Do not include implementation details such as method names or field names — they make tests brittle when refactoring. Domain concepts (state names, signal types, and other ubiquitous language) are fine to use.

### MockK usage guidelines

**Use mocks:**
- Isolating Infrastructure dependencies (external clients) in Application layer tests
- Isolating Application dependencies (services) in Presentation layer tests
- Isolating non-deterministic dependencies (time, random, external APIs)

**Do not use mocks:**
- Domain layer — test pure domain objects with real instances
- Application layer integration tests — use `@SpringBootTest` + Testcontainers against a real DB
- Infrastructure layer integration tests — use a real DB (Testcontainers, etc.)

### SpringBootTest + Testcontainers conventions

- Extend `IntegrationTestBase` — the MySQL container and `@DynamicPropertySource` are defined there so all integration tests share the same container.
