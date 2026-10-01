# nexus

Reusable library code for FTC robots, split into modules:

- `core` — hardware-agnostic core abstractions (`core`, `group`, and later `bind`).
- `ftc` — FTC SDK integration *(not yet enabled)*.
- `pedro` — Pedro Pathing integration *(not yet enabled)*.

## Layout

```
nexus/
  core/    # Java library, base package xbots.nexus.core
  ftc/     # later
  pedro/   # later
```

## Building

Requires a JDK 17+ toolchain.

```
./gradlew build
./gradlew test
```
