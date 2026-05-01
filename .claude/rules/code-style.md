# Code Style

## Domain Layer — Object-Oriented Principles

Code in the `domain/` package keeps data and responsibility together in the object. Don't pull data out and compute externally — send a message to the object and delegate the work.

### 1. Tell, Don't Ask

If a decision can be made using the data an object already holds, the object makes it itself. External code does not reach into an object's fields to compute things.

### 2. No utility static classes

Static function collections like `XxxUtils` must be distributed to the domain objects that own the relevant data.

### 3. Wrap Lists in first-class collections

Collections with domain meaning are wrapped as `class Xxxs(private val list: List<X>)` so that collection-level behavior and invariants live inside the wrapper.

### 4. The domain has no external dependencies

Inside `domain/`, never directly call Spring, HTTP clients, or non-deterministic dependencies (time, random). Receive required values as parameters instead.
