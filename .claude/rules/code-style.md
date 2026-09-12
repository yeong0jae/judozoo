# Code Style

## Domain Layer — Object-Oriented Principles

Code in the `domain` module keeps data and responsibility together in the object. Don't pull data out and compute externally — send a message to the object and delegate the work.

### 1. Tell, Don't Ask

If a decision can be made using the data an object already holds, the object makes it itself. External code does not reach into an object's fields to compute things.

### 2. No utility function collections

Modules that are just a bag of free functions over someone else's data (`*_utils.py`, `format_utils`) must be distributed to the domain objects that own the relevant data.

### 3. Wrap lists in first-class collections

Collections with domain meaning are wrapped as `class Xxxs` holding the list, so that collection-level behavior and invariants live inside the wrapper — `MinuteCandles`, `DailyCandles`, `Stocks`, `Nets`.

### 4. The domain has no external dependencies

Inside `domain`, never directly call FastAPI, HTTP clients (`httpx`), the session/engine, or non-deterministic dependencies (`datetime.now`, `random`). Receive required values as parameters instead.

### 5. SQLAlchemy model = domain entity

Persistent domain concepts are declared as SQLAlchemy models on `library.db.Base` and used directly as domain entities. Do not add a parallel "pure" domain class that mirrors the model.

Purely computational value objects stay as `@dataclass` with no ORM involvement — `entities.py` holds the mapped classes when a feature has both.
