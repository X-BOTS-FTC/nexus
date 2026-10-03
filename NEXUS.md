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
- **Failure policy:** isolate and report by default, strict mode for tests. See "Failure policy" below.
- **Stop:** on OpMode stop, cancel all commands (each gets `end(true)`) and put subsystems in a safe state.

### Loop order (fixed)

1. Clear bulk-read cache
2. Subsystem `periodic`
3. Poll triggers
4. Run commands (`execute`)
5. End and remove finished commands

Avoid allocating inside the loop. Build compositions at init. Expose loop time for telemetry.

## Failure policy

What happens when a command throws. Default is **isolate and report**.

- **Isolate:** the scheduler catches exceptions around every call into a command (`initialize`, `execute`, `isFinished`, `end`). Only the failing command is cancelled and removed, its subsystems are released, and all other commands keep running. *Why: one bug in the intake shouldn't stop the drivetrain mid-match.*
- **Report:** the scheduler records the last error (which command, which phase, the exception) as a read-only fact. The `ftc` layer prints it to telemetry. `core` never calls telemetry itself. Make failures obvious on the Driver Station, not only in a log.
- **Strict mode:** the scheduler rethrows instead of isolating. Use in desktop tests and practice so failures are loud.

Rules for specific cases:

- **Throw in `initialize`:** the command never started, so it gets no `end`.
- **Throw in `execute` or `isFinished`:** the command is cancelled and still gets `end(true)`.
- **Throw in `end`:** the command is still removed, and the remaining cleanup continues. This matters most at OpMode stop, where one broken `end` must not stop other commands from reaching a safe state.
- **Throw inside a group:** the whole group is cancelled and its other running children get `end(true)`.

Open (undecided): a throw in a subsystem's `periodic` (likely report and continue, but commands may then use stale data), and whether a default command that fails repeatedly should be disabled to avoid flooding telemetry.

*Cost: a command can die quietly while the robot keeps running half-working. This only helps if reporting is visible.*

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

Informed by reading Ivy's source, where each of these is either inconsistent or left implicit.

**Lifecycle**
- `end` receives a boolean `interrupted`.
- `end` is called exactly once for every successful `initialize`, however the command stops.
- A command that never started never gets `end`. This includes unstarted children of an interrupted sequence. *(Ivy calls `end` on them. Ours avoids cleanup running on state that was never set up.)*
- Every command gets `execute` at least once before `isFinished` is checked, so a command that is done right after `initialize` runs `execute` once, then `end(false)`, and is removed in the same loop.

**Ordering**
- The order is `execute`, then `isFinished`, **at every level**, including inside groups. *(Ivy checks done-before-execute inside sequences but execute-before-done at the top level.)*
- Parallel, race, and deadline children run in the order they were given, never in hash order. If several race members finish in the same loop, the first in order wins. *(Ivy stores children in a hash map, so order can vary between runs.)*
- When a sequence step finishes, the next step is initialized in the same loop, so a run of instant commands does not cost one loop per step. *Cost: a long chain of work can land in a single loop.*

**Scheduling**
- Scheduling an already-running command is ignored, checked explicitly and **independent of requirements**. *(In Ivy, a command with no requirements can end up in the running list twice.)*
- Any deferred or lazy command must declare its requirements up front. *(Ivy's `lazy` declares none, so the scheduler cannot see conflicts for what it wraps.)*
- The scheduler's per-loop work must not allocate: iterate preallocated lists by index, and reuse a removal list. *(Ivy allocates a copy and a removal list every loop.)*

## Known tradeoffs

- A stray input can interrupt a half-finished sequence. Guard this in bindings or inside the command.
- Triggers are polled once per loop, so a press shorter than a loop can be missed.
- Failure isolation can hide a dead command mid-match. Make sure errors reach telemetry.
