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

- **Requests are deferred.** `schedule()` and `cancel()` only queue a request. Requests are applied at the start of the next `run()`, in the order they were made, so a command scheduled from inside another command waits for the next run. *Why: command code only runs at predictable points, and the running list never changes while it is being looped over. Cost: one loop of delay.*
- **Conflicts:** when a command starts, any running command sharing a required subsystem gets `end(true)` first, then the new command's `initialize`. If two commands for the same subsystem start in one loop, the later request wins. No uninterruptible commands.
- **Subsystems are auto-registered** when a command that requires them is scheduled, or when a default command is set. `periodic()` runs every loop, in registration order. *Cost: a subsystem nothing ever mentions never gets `periodic()`.*
- **Default commands:** at most one per subsystem, and it must require exactly that subsystem (setting a second default throws). A default starts at the beginning of a `run()`, after requests are applied, when nothing else holds its subsystem. So after a command finishes in loop N, the default starts in loop N+1. Cancelling a default ends it and it restarts straight away. `setDefaultCommandsEnabled(false)` turns defaults off (used in auto). A default that fails 3 runs in a row is disabled for good.
- **Stop:** `stop()` drops queued requests (never-started commands get no `end`), ends every running command with `end(true)` in start order, then calls `enterSafeState()` on every subsystem in registration order. Afterward `schedule`, `cancel` and `run` do nothing.
- **Failure policy:** isolate and report by default, strict mode for tests. See "Failure policy" below.

### Loop order (fixed)

Inside `Scheduler.run()`:

1. Subsystem `periodic`
2. Apply queued schedule/cancel requests
3. Start default commands for free subsystems
4. `execute` every running command
5. End and remove commands that finished or failed

The adapter clears the bulk-read cache before `run()`.

**Open:** triggers must be polled after `periodic` and before commands run, but `run()` has no hook for that yet. Decide it when building triggers: either a poll hook the scheduler calls, or `run()` split into phases the adapter calls.

Avoid allocating inside the loop. Build compositions at init. Loop time is exposed for telemetry.

## Failure policy

What happens when a command throws. Default is **isolate and report**.

- **Isolate:** the scheduler catches exceptions around every call into a command (`initialize`, `execute`, `isFinished`, `end`) and into a subsystem (`periodic`, `enterSafeState`). Only the failing command is cancelled and removed, its subsystems are released, and everything else keeps running. *Why: one bug in the intake shouldn't stop the drivetrain mid-match.*
- **Only `RuntimeException` is caught.** An `Error` (out of memory, stack overflow) escapes, because the scheduler cannot promise to stay consistent after one. *To verify on the robot: how the FTC SDK signals an OpMode stop, so isolation never swallows it.*
- **Report:** the scheduler records the last error (which command or subsystem, which phase, the exception) and a total error count, as read-only facts. The `ftc` layer prints them to telemetry. `core` never calls telemetry itself. Make failures obvious on the Driver Station, not only in a log.
- **Strict mode:** `setStrict(true)` does everything isolation does, finishes all its bookkeeping, then rethrows the first failure of that `run()` (or of `stop()`). Use in desktop tests and practice so failures are loud, without leaving the scheduler in a half-updated state.

Rules for specific cases:

- **Throw in `initialize`:** the command never started, so it gets no `end`.
- **Throw in `execute` or `isFinished`:** the command is cancelled and still gets `end(true)`.
- **Throw in `end`:** the command is still removed, and the remaining cleanup continues. This matters most at OpMode stop, where one broken `end` must not stop other commands from reaching a safe state.
- **Throw in a subsystem's `periodic`:** report it and keep calling `periodic()` every loop. *Cost: commands using that subsystem may act on stale data until it recovers.*
- **Throw in a default command:** counted once per run. After 3 consecutive failing runs the default is disabled for good (`isDefaultCommandDisabled` reports it). A successful run resets the count. *Cost: the driver can lose that default mid-match, so make sure it is visible.*
- **Throw inside a group** *(not built yet)*: the whole group is cancelled and its other running children get `end(true)`.

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

## Edge cases

Informed by reading Ivy's source, where each of these is either inconsistent or left implicit. Items marked **(tested)** are implemented in `Scheduler` and covered by `SchedulerTest`. The rest are still proposed defaults, to confirm when the group classes are built.

**Lifecycle**
- `end` receives a boolean `interrupted`. **(tested)**
- `end` is called exactly once for every successful `initialize`, however the command stops. **(tested)**
- A command that never started never gets `end`. **(tested for commands queued at stop and for a failing `initialize`.)** For unstarted children of an interrupted sequence this is still proposed. *(Ivy calls `end` on them. Ours avoids cleanup running on state that was never set up.)*
- Every command gets `execute` at least once before `isFinished` is checked, so a command that is done right after `initialize` runs `execute` once, then `end(false)`, and is removed in the same loop. **(tested)**

**Ordering**
- The order is `execute`, then `isFinished`, **at every level**, including inside groups. *(Ivy checks done-before-execute inside sequences but execute-before-done at the top level.)* **(tested at the top level; groups not built yet.)** At the top level `isFinished` is checked after *every* running command has executed that loop, not between commands, matching loop order steps 4 and 5.
- Parallel, race, and deadline children run in the order they were given, never in hash order. If several race members finish in the same loop, the first in order wins. *(Ivy stores children in a hash map, so order can vary between runs.)*
- When a sequence step finishes, the next step is initialized in the same loop, so a run of instant commands does not cost one loop per step. *Cost: a long chain of work can land in a single loop.*

**Scheduling**
- Scheduling an already-running command is ignored, checked explicitly and **independent of requirements**. *(In Ivy, a command with no requirements can end up in the running list twice.)* **(tested)**
- Any deferred or lazy command must declare its requirements up front. *(Ivy's `lazy` declares none, so the scheduler cannot see conflicts for what it wraps.)*
- The scheduler's per-loop work must not allocate: it iterates preallocated lists by index and compacts them in place. *(Ivy allocates a copy and a removal list every loop.)* Implemented, but **not tested**: a test for allocation is hard, so this is checked by reading the code. A list that grows past its starting capacity of 32 allocates once.

## Known tradeoffs

- A stray input can interrupt a half-finished sequence. Guard this in bindings or inside the command.
- Triggers are polled once per loop, so a press shorter than a loop can be missed.
- Failure isolation can hide a dead command mid-match. Make sure errors reach telemetry.
