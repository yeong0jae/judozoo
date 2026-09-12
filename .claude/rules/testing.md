# Testing Rules

Backend tests are pytest. Run with `uv run pytest` from `backend/`.

### Convention

Group related cases in a `class Test<이름>:` and write each case as a `def test_<설명>(self)`.
No `unittest.TestCase`, no bare module-level test functions when a grouping exists.

```python
class Test이슈_메모:
    def test_작성하고_그날_목록에서_읽는다(self, 빈_테이블):
        ...
```

### Description principles

Class and test names must convey **scenario and meaning**, written in **Korean**. Do not encode implementation details such as function names or field names — they make tests brittle when refactoring. Domain concepts (signal types, filter names, and other ubiquitous language) are fine to use.

### Layout

`tests/` mirrors `src/backend/` one-to-one — `tests/platform/kiwoom/test_client.py` covers `backend/platform/kiwoom/client.py`. Every test directory has an `__init__.py`; without it pytest rejects duplicate file basenames across directories.

### Per-layer guidelines

1. Domain (`domain.py`, `filters.py`, `signals.py`)
- Tests must be the purest and most independent.
- Do not start the app, touch the DB, or mock anything — construct real objects and assert on them.

2. Platform / Infrastructure (`platform/**`, `infrastructure.py`)
- External API: use **respx** to simulate external-server response scenarios (success, failure, timeout, malformed payload).
- This is where broker response traps belong — sign variants, time offsets, exchange codes. Assert on the parsed result, not on the request.

3. Application (`application.py`, `scheduler.py`)
- DB-backed tests use the `통합_db` fixture (Testcontainers MySQL) and must be marked `@pytest.mark.integration`.
- Verify collaboration between components; isolate only the uncontrollable parts (external API calls) with `pytest-mock`.
- **Never use respx at this layer.** respx belongs to platform/infrastructure, where the HTTP client itself is under test. Application tests verify business logic, not HTTP parsing.

4. Presentation (`presentation.py`)
- Use the `client` fixture (FastAPI `TestClient`). Focus on route mapping, query/path validation, and response shape.
- Mock the application layer with `pytest-mock` to keep API-contract verification fast.

### Markers

`@pytest.mark.integration` means the test needs Docker. `uv run pytest -m "not integration"` skips those — use it when Docker is unavailable.

### Fixtures

Shared fixtures live in `tests/conftest.py`: `client` (TestClient), `통합_db` (container MySQL), and an autouse cache reset. A fixture needed by one file only (e.g. `빈_테이블`) stays in that file.
