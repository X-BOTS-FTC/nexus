# Nexus

A minimal, reliable command base for FTC (Java). Inspired by WPILib's command base, SolversLib, and Pedro Pathing's Ivy. Not a fork of any of them.

Nexus schedules commands safely. Everything else is an optional layer.

## Hard rules

1. **No FTC SDK imports in `core`.** It must compile and test as plain Java. *Why: desktop testing and reusability, enforced by the build.*
2. **Single-threaded.** All Nexus code runs on the OpMode loop thread. No locks. *Why: removes a whole class of bugs; slow work lives outside Nexus.*
3. **One scheduler instance per OpMode, no static state.** Scheduler and bindings are created at init and cleared on init. *Why: the SDK keeps the JVM alive between OpModes, so static state leaks between runs.*
4. **Time comes from an injected `Clock`.** Never read system time directly in `core`. *Why: time-based commands become testable.*
5. **Commands never assume who scheduled them.** The same command must work from a TeleOp binding and an auto sequence.

## Command model

- Lifecycle: `initialize`, `execute`, `isFinished`, `end(interrupted)`.
- Commands are **reusable**. All state must be reset in `initialize`. *Cost: a forgotten reset causes flaky second runs.*
- Each command declares the subsystems it requires.

## Scheduler behavior

- **Conflicts:** a newly scheduled command interrupts any command holding a required subsystem. No uninterruptible commands.
- **Default commands:** at most one per subsystem. After an interrupting command ends, the default restarts on the **next** loop, not the same one. Default commands can be disabled (used in auto).
- **Failure policy:** by default, a command that throws is cancelled, reported, and the loop continues. A strict mode rethrows (use in tests and practice).
- **Stop:** on OpMode stop, cancel all commands (each gets `end(true)`) and put subsystems in a safe state.

### Loop order (fixed)

1. Clear bulk-read cache
2. Subsystem `periodic`
3. Poll triggers
4. Run commands (`execute`)
5. End and remove finished commands

Avoid allocating inside the loop. Build compositions at init. Expose loop time for telemetry.

## Compositions

Sequential, parallel (wait for all), race (first to finish), deadline (one command decides), wait-for-time, wait-until. A parallel group **throws at construction** if members share a required subsystem.

## Triggers (`bind`)

Bound to boolean suppliers, not gamepad types. Polled once per loop so all bindings see the same value. Vocabulary: on-press, while-held, on-release, toggle.

## Layers

```
robot  ->  ftc, pedro, core
ftc    ->  core (plus FTC SDK)
pedro  ->  core (plus Pedro)
core   ->  nothing
```

`core` never calls telemetry. It exposes read-only facts (running commands, loop time, last error) and the `ftc` layer prints them.

## Edge cases (proposed defaults, confirm before implementing)

- `end` receives a boolean `interrupted`.
- Scheduling an already-running command is ignored.
- A command that is finished immediately after `initialize` still runs `end(false)` and is removed in the same loop.

## Known tradeoffs

- A stray input can interrupt a half-finished sequence. Guard this in bindings or inside the command.
- Triggers are polled once per loop, so a press shorter than a loop can be missed.
- Failure isolation can hide a dead command mid-match. Make sure errors reach telemetry.
