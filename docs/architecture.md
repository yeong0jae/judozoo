# Architecture

## Application architecture

### 4-layered architecture

The codebase is split by feature (package by feature), and each feature contains only the layers it actually needs.

| Layer | Responsibility |
|-------|----------------|
| `presentation` | HTTP/STOMP endpoints. Request validation and DTO conversion. |
| `application` | Use-case orchestration. **Cross-feature calls happen only between `application` layers.** |
| `domain` | Pure domain models and rules. No external dependencies. |
| `infrastructure` | Implementations of external systems such as databases and external APIs. |

#### Dependency direction

```
presentation ──▶ application ──▶ domain ◀── infrastructure
```

## Top-level package layout

```
at.backend
├── <feature>/        # feature packages: trading, stock, command, market, report, ...
│   ├── presentation/
│   ├── application/
│   ├── domain/
│   └── infrastructure/
├── library/          # generic, reusable cross-cutting code (no business logic)
└── platform/         # adapters for external systems
```

### Feature packages

Each business capability lives in its own package and contains only the layers it needs (e.g., `at.backend.stock` currently has only `domain/`). Cross-feature collaboration is allowed only between `application` layers — never between `domain` packages directly.
