# Architecture

## Application architecture

### 4-layered architecture

The codebase is split by feature (package by feature), and each feature contains only the layers it actually needs.

| Layer | Responsibility |
|-------|----------------|
| `presentation` | HTTP endpoints (Spring MVC `@RestController`). Request validation and response DTO conversion. |
| `application` | Use-case orchestration and scheduled jobs. **Cross-feature calls happen only between `application` layers.** |
| `domain` | Domain models and rules — entities, value objects, first-class collections, repository interfaces. No external dependencies. |
| `infrastructure` | Implementations of external systems such as SQL-level repositories and file/CDN adapters. |

#### Dependency direction

```
presentation ──▶ application ──▶ domain ◀── infrastructure
```

## Package layout

The backend is Kotlin + Spring Boot (Spring MVC, JPA) in `backend/`. It moved from Python/FastAPI in
[028](../../docs/tasks/028-코틀린-스프링-전환.md) (2026-10-16).

```
backend/src/main/kotlin/judozoo
├── <feature>/                 # leadingstock, overseasleadingstock, market, stock, leadertimeline, leadercalendar, insight, closingbet, auth, feedback
│   ├── presentation/          # LeadingStockController.kt, request/response DTOs
│   ├── application/           # LeadingStockService.kt, LeadingStockScheduler.kt
│   ├── domain/                # entities, value objects, first-class collections, repository interfaces
│   └── infrastructure/        # repository implementations, external adapters
├── library/                   # generic, reusable cross-cutting code (no business logic); may group by concern (library/web/, ...)
├── platform/<vendor>/         # adapters for external systems: kis, kiwoom, toss, yahoo, vertex, google
└── JudozooApplication.kt
```

- **A layer is a package (directory); one top-level class per file**, named after the class. A sealed hierarchy or a tiny private helper may share its owner's file.
- A feature creates only the layers it needs.
- A sub-package inside a layer (`domain/filter/`) is fine once a concern has several classes.
- Spring wiring (`@Service`, `@Repository`, `@Component`) stays out of `domain/`. Spring Data repository interfaces may live in `domain/` as the port; anything that touches `EntityManager` or SQL goes to `infrastructure/`.
- `platform/<vendor>/` holds `<Vendor>Client.kt` (auth, rate limit, transport) plus one file per endpoint group.
  Build every `RestClient` from the injected `RestClient.Builder` (`restClient.clone()`), never `RestClient.builder()` — only Spring's builder carries tracing.

### Feature packages

Each business capability lives in its own package and contains only the layers it needs. Cross-feature collaboration is allowed only between `application` layers — never between `domain` packages directly.

#### Cross-feature value objects

Value objects (data types without behavior coupling) may be defined in the upstream feature's `domain` and imported by downstream features. The upstream owns the published model; the downstream consumes it.

Example: `stock.domain.Market` is owned by `stock` and imported as a type by `market` and `leadingstock`. Those features **import the type** but do **not call methods** on stock's domain objects.

The "domain packages must not directly collaborate" rule applies to **behavior** (method calls / orchestration), not value-object type references for data exchange.

## Operating rules

- **Flyway owns the schema** (`backend/src/main/resources/db/migration`). JPA runs with `ddl-auto=validate` and never changes a table.
  `V1__baseline.sql` is the schema as Alembic left it — never edit it; add `V2__...` onward. Production was baselined at V1.
  Add a new column as NULL-able first — the old container is still running while the new one migrates.
- **MySQL reserved words as column names** (`rank`, `lead`, `trigger`) need a quoted `@Column(name = "`rank`")`.
- **Single-precision `float` columns** are read through `Float.toString().toDouble()` so values match what MySQL prints.
- **One process per broker app key.** Rate-limit buckets and broker tokens (`broker_token`, issued by `IssuedToken`) live in process memory/DB per app key.
  Scaling to two instances would double every poller and split each broker limit — don't, without moving schedules to one instance.
- **A poller that swallows a failure counts it** with `JobFailures.record(jobId)` — the "swallowed errors" alert sums `scheduler_job_errors_total` by `job_id`.
