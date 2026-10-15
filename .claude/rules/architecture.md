# Architecture

## Application architecture

### 4-layered architecture

The codebase is split by feature (package by feature), and each feature contains only the layers it actually needs.

| Layer | Responsibility |
|-------|----------------|
| `presentation` | HTTP endpoints (FastAPI `APIRouter`). Request validation and response model conversion. |
| `application` | Use-case orchestration. **Cross-feature calls happen only between `application` layers.** |
| `domain` | Pure domain models and rules. No external dependencies. |
| `infrastructure` | Implementations of external systems such as databases and external APIs. |

#### Dependency direction

```
presentation ──▶ application ──▶ domain ◀── infrastructure
```

## Top-level package layout

```
backend/src/backend
├── <feature>/        # feature packages: leadingstock, market, stock, ...
│   ├── presentation.py
│   ├── application.py
│   ├── domain.py
│   └── infrastructure.py
├── library/          # generic, reusable cross-cutting code (no business logic)
├── platform/         # adapters for external systems: kis, kiwoom, toss, yahoo
├── main.py           # FastAPI app assembly, router registration
└── settings.py       # pydantic-settings, env-backed
```

### Layers are modules, not packages

Each layer is a single module file (`application.py`), not a directory. A feature splits further only when one concern grows its own vocabulary — `leadingstock/` has `filters.py`, `signals.py`, `entities.py`, `scheduler.py`, `events.py` alongside the four layers. Do not create a directory per layer.

`platform/<vendor>/` is the exception: it is a package with `client.py` (auth, rate limit, transport) plus one module per endpoint group.

### Feature packages

Each business capability lives in its own package and contains only the layers it needs. Cross-feature collaboration is allowed only between `application` layers — never between `domain` modules directly.

#### Cross-feature value objects

Value objects (data types without behavior coupling) may be defined in the upstream feature's `domain` and imported by downstream features. The upstream owns the published model; the downstream consumes it.

Example: `stock.domain.Market` is owned by `stock` and imported as a type by `market` and `leadingstock`. Those features **import the type** but do **not call methods** on stock's domain objects.

The "domain modules must not directly collaborate" rule applies to **behavior** (method calls / orchestration), not value-object type references for data exchange.

## Kotlin backend (`backend-kotlin/`, migration in progress — [028](../../docs/tasks/028-코틀린-스프링-전환.md))

The backend is moving to Kotlin + Spring Boot (Spring MVC, JPA) one feature at a time. Until it finishes,
**both backends run against the same MySQL** and nginx routes each `/api` path to one of them.
The layer rules and dependency direction above apply unchanged. **The file layout does not** — Kotlin follows
the usual Spring convention instead of Python's one-module-per-layer.

```
backend-kotlin/src/main/kotlin/judozoo
├── <feature>/                 # same feature packages as Python
│   ├── presentation/          # LeadingStockController.kt, request/response DTOs
│   ├── application/           # LeadingStockService.kt, LeadingStockScheduler.kt
│   ├── domain/                # entities, value objects, first-class collections, repository interfaces
│   └── infrastructure/        # repository implementations, external adapters
├── library/                   # cross-cutting, may group by concern (library/web/, ...)
├── platform/<vendor>/         # KiwoomClient.kt + one file per endpoint group
└── JudozooApplication.kt
```

- **A layer is a package (directory); one top-level class per file**, named after the class. A sealed hierarchy or a tiny private helper may share its owner's file.
- A feature still creates only the layers it needs.
- Python's concern modules (`filters.py`, `signals.py`, `events.py`) become classes in the layer they belong to — usually `domain/` — not extra top-level directories. A sub-package inside a layer (`domain/filter/`) is fine once a concern has several classes.
- Spring wiring (`@Service`, `@Repository`, `@Component`) stays out of `domain/`. Spring Data repository interfaces may live in `domain/` as the port; anything that touches `EntityManager` or SQL goes to `infrastructure/`.

### While both backends run

- **Alembic owns the schema.** JPA runs with `ddl-auto=validate` and never changes a table.
  **Adding an Alembic migration means running `backend-kotlin/scripts/dump-schema.sh` and committing the new `schema.sql`** — Kotlin integration tests build their tables from it.
- **One owner per scheduled job.** Moving a feature turns its Python `register` off and its Kotlin `@Scheduled` on in the same commit.
- **One issuer per broker token.** The other side only reads `broker_token`.
- **Rate limits are split** between the two processes by settings — each bucket lives in its own process memory.
- A moved feature's Python code stays until no remaining Python feature calls its `application`.
