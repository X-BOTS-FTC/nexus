# nexus

The X BOTS' command base for the 2026-27 FTC season, Biobuzz.

- `core` includes all of the actual command base code. It doesn't reference hardware or Pedro at all.
- `ftc` is where the hardware integration is. (not enabled yet)
- `pedro` is for Pedro Pathing integration. (not enabled yet)

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
