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
python-backend/src/backend
├── <feature>/        # feature packages: leadingstock, market, stock, theme, news, ...
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
