# Autonomous Trading System

A leading-stock automated trading system (Spring Boot/Kotlin + React + MySQL).

## Behavioral guidelines

### Think Before Coding
Don't assume. Don't hide confusion. Surface tradeoffs.

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### Simplicity First
Minimum code that solves the problem. Nothing speculative.

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## Documentation

- `docs/prd.md` — product requirements
- `docs/spec.md` — feature spec
- `docs/plan.md` — phase-by-phase development plan
- `docs/tasks/tasks-NNN.md` — per-phase task checklist
- `.claude/rules/architecture.md` — architecture (auto-loaded as a rule)
