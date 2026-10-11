package xbots.nexus.core;

import java.util.ArrayList;

/**
 * Runs commands. One instance per OpMode, no static state (NEXUS.md, hard rule 3).
 *
 * Every request (schedule, cancel) is queued and applied at the start of the next
 * run(), in the order it was made. Command code therefore only ever runs at
 * predictable points inside run(), and the running list never changes while it is
 * being looped over.
 *
 * One run(), in order:
 *   1. periodic() of every registered subsystem
 *   2. apply queued schedule/cancel requests
 *   3. start default commands for free subsystems
 *   4. execute() every running command
 *   5. end and remove the commands that finished or failed
 *
 * Failures: a RuntimeException thrown by a command or subsystem is isolated. The
 * offender is cancelled (a command that failed after starting still gets
 * end(true)), the error is recorded for telemetry, and everything else carries on.
 * In strict mode the first failure of a run() is rethrown after all bookkeeping is
 * finished. Only RuntimeException is caught: an Error (out of memory, stack
 * overflow) escapes, because the scheduler cannot promise to stay consistent
 * after one.
 *
 * Single-threaded. Call everything from the OpMode loop thread.
 */
public final class Scheduler {

    /** Where in the lifecycle a failure happened. */
    public enum Phase {
        INITIALIZE, EXECUTE, IS_FINISHED, END, PERIODIC, SAFE_STATE
    }

    private static final class DefaultEntry {
        final Subsystem subsystem;
        final Command command;
        int consecutiveFailures;
        boolean failedThisRun;
        boolean disabled;

        DefaultEntry(Subsystem subsystem, Command command) {
            this.subsystem = subsystem;
            this.command = command;
        }
    }

    private static final int INITIAL_CAPACITY = 32;

    /** A default command that fails this many runs in a row is disabled. */
    private static final int MAX_DEFAULT_FAILURES = 3;

    private final Clock clock;

    // Commands that have been initialized and execute every run().
    private final ArrayList<Command> running = new ArrayList<>(INITIAL_CAPACITY);

    // Queued requests, oldest first. requestIsCancel.get(i) tells what request i is.
    private final ArrayList<Command> requestCommands = new ArrayList<>(INITIAL_CAPACITY);
    private final ArrayList<Boolean> requestIsCancel = new ArrayList<>(INITIAL_CAPACITY);

    // Every subsystem a scheduled command or default command has mentioned, in
    // the order first seen. periodic() runs in this order.
    private final ArrayList<Subsystem> subsystems = new ArrayList<>(INITIAL_CAPACITY);

    private final ArrayList<DefaultEntry> defaults = new ArrayList<>(INITIAL_CAPACITY);

    // Commands whose execute() threw during the current run().
    private final ArrayList<Command> failedExecute = new ArrayList<>(INITIAL_CAPACITY);

    private boolean strict;
    private boolean defaultsEnabled = true;
    private boolean stopped;

    private long lastLoopNanos;

    private Throwable lastError;
    private Object lastErrorSource;
    private Phase lastErrorPhase;
    private int errorCount;
    private RuntimeException strictError;

    public Scheduler(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock is null");
        }
        this.clock = clock;
    }

    // ---------------------------------------------------------------- setup

    /**
     * In strict mode, the first failure of each run() (or of stop()) is rethrown
     * once the scheduler has finished its own bookkeeping. Use it in tests and
     * practice so failures are loud. Default is off (isolate and report).
     */
    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    /**
     * Turns default commands on or off. Call before the first run(). It only
     * controls whether defaults are started; it does not end one that is running.
     * Intended for autonomous, where defaults are off.
     */
    public void setDefaultCommandsEnabled(boolean enabled) {
        this.defaultsEnabled = enabled;
    }

    /**
     * Sets the command that runs on a subsystem whenever nothing else is using it.
     * The command must require exactly that one subsystem. A subsystem has at most
     * one default; setting a second throws, so a double registration is caught at
     * init instead of silently replacing the first.
     */
    public void setDefaultCommand(Subsystem subsystem, Command command) {
        if (subsystem == null) {
            throw new IllegalArgumentException("subsystem is null");
        }
        if (command == null) {
            throw new IllegalArgumentException("command is null");
        }
        if (command.requirementCount() != 1 || command.requirement(0) != subsystem) {
            throw new IllegalArgumentException(
                    "A default command must require exactly its own subsystem");
        }
        if (findDefault(subsystem) != null) {
            throw new IllegalStateException("Subsystem already has a default command");
        }
        defaults.add(new DefaultEntry(subsystem, command));
        registerSubsystem(subsystem);
    }

    // ------------------------------------------------------------- requests

    /**
     * Requests that a command start. It is initialized at the start of the next
     * run(). Scheduling a command that is already running is ignored. If a started
     * command shares a required subsystem with a running one, the running one is
     * interrupted first (it gets end(true) before the new command's initialize()).
     */
    public void schedule(Command command) {
        if (command == null) {
            throw new IllegalArgumentException("command is null");
        }
        if (stopped) {
            return;
        }
        registerRequirements(command);
        requestCommands.add(command);
        requestIsCancel.add(Boolean.FALSE);
    }

    /**
     * Requests that a command be cancelled. At the start of the next run() a
     * running command gets end(true) and is removed. Cancelling a command that is
     * not running does nothing. Cancelling a default command ends it and it
     * restarts straight away, since its subsystem is then free.
     */
    public void cancel(Command command) {
        if (command == null) {
            throw new IllegalArgumentException("command is null");
        }
        if (stopped) {
            return;
        }
        requestCommands.add(command);
        requestIsCancel.add(Boolean.TRUE);
    }

    /** True if the command is running, or a request to start it is queued. */
    public boolean isScheduled(Command command) {
        if (running.contains(command)) {
            return true;
        }
        for (int i = 0; i < requestCommands.size(); i++) {
            if (requestCommands.get(i) == command && !requestIsCancel.get(i)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ run

    /** Runs one loop. See the class comment for the order. Does nothing after stop(). */
    public void run() {
        if (stopped) {
            return;
        }
        long startNanos = clock.nanos();
        strictError = null;
        failedExecute.clear();

        runPeriodics();
        applyRequests();
        startDefaults();
        executeAll();
        finishAll();
        clearDefaultFailureFlags();

        lastLoopNanos = clock.nanos() - startNanos;
        rethrowStrictError();
    }

    /**
     * Ends the scheduler for good: drops queued requests (never-started commands
     * get no end()), ends every running command with end(true) in start order, then
     * calls enterSafeState() on every subsystem in registration order. A failure in
     * one step does not stop the rest. After this, schedule(), cancel() and run() do
     * nothing.
     */
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        strictError = null;
        requestCommands.clear();
        requestIsCancel.clear();
        for (int i = 0; i < running.size(); i++) {
            safeEnd(running.get(i), true);
        }
        running.clear();
        for (int i = 0; i < subsystems.size(); i++) {
            Subsystem subsystem = subsystems.get(i);
            try {
                subsystem.enterSafeState();
            } catch (RuntimeException e) {
                report(subsystem, Phase.SAFE_STATE, e);
            }
        }
        rethrowStrictError();
    }

    private void runPeriodics() {
        // size() is re-read each pass: a periodic() may schedule something, which
        // can register a new subsystem.
        for (int i = 0; i < subsystems.size(); i++) {
            Subsystem subsystem = subsystems.get(i);
            try {
                subsystem.periodic();
            } catch (RuntimeException e) {
                report(subsystem, Phase.PERIODIC, e);
            }
        }
    }

    private void applyRequests() {
        // Snapshot the count: requests made while these run wait for the next run().
        int count = requestCommands.size();
        for (int i = 0; i < count; i++) {
            Command command = requestCommands.get(i);
            if (requestIsCancel.get(i)) {
                cancelRunning(command);
            } else if (!running.contains(command)) {
                startCommand(command);
            }
        }
        dropFirst(requestCommands, count);
        dropFirst(requestIsCancel, count);
    }

    private void startDefaults() {
        if (!defaultsEnabled) {
            return;
        }
        for (int i = 0; i < defaults.size(); i++) {
            DefaultEntry entry = defaults.get(i);
            if (entry.disabled
                    || running.contains(entry.command)
                    || isRequiredByRunning(entry.subsystem)) {
                continue;
            }
            startCommand(entry.command);
        }
    }

    private void executeAll() {
        // Nothing is added to or removed from running during this pass.
        for (int i = 0; i < running.size(); i++) {
            Command command = running.get(i);
            try {
                command.execute();
            } catch (RuntimeException e) {
                failedExecute.add(command);
                report(command, Phase.EXECUTE, e);
            }
        }
    }

    private void finishAll() {
        // Every call into a command is isolated, so nothing here can throw and
        // leave the list half-compacted. Survivors are shifted down in place.
        int count = running.size();
        int kept = 0;
        for (int i = 0; i < count; i++) {
            Command command = running.get(i);
            if (failedExecute.contains(command)) {
                safeEnd(command, true);
                continue;
            }
            boolean finished;
            try {
                finished = command.isFinished();
            } catch (RuntimeException e) {
                report(command, Phase.IS_FINISHED, e);
                safeEnd(command, true);
                continue;
            }
            if (finished) {
                if (safeEnd(command, false)) {
                    noteDefaultSuccess(command);
                }
            } else {
                running.set(kept, command);
                kept++;
                noteDefaultSuccess(command);
            }
        }
        for (int i = count - 1; i >= kept; i--) {
            running.remove(i);
        }
    }

    // ---------------------------------------------------- start / cancel / end

    private void startCommand(Command command) {
        // Interrupt anything sharing a subsystem, oldest first, before the new
        // command touches the hardware.
        int i = 0;
        while (i < running.size()) {
            Command other = running.get(i);
            if (conflicts(other, command)) {
                safeEnd(other, true);
                running.remove(i);
            } else {
                i++;
            }
        }
        try {
            command.initialize();
        } catch (RuntimeException e) {
            // Never started, so no end().
            report(command, Phase.INITIALIZE, e);
            return;
        }
        running.add(command);
    }

    private void cancelRunning(Command command) {
        int index = running.indexOf(command);
        if (index >= 0) {
            safeEnd(command, true);
            running.remove(index);
        }
    }

    /** Calls end(), isolating a failure. Returns true if end() did not throw. */
    private boolean safeEnd(Command command, boolean interrupted) {
        try {
            command.end(interrupted);
            return true;
        } catch (RuntimeException e) {
            report(command, Phase.END, e);
            return false;
        }
    }

    private static boolean conflicts(Command a, Command b) {
        for (int i = 0; i < a.requirementCount(); i++) {
            for (int j = 0; j < b.requirementCount(); j++) {
                if (a.requirement(i) == b.requirement(j)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isRequiredByRunning(Subsystem subsystem) {
        for (int i = 0; i < running.size(); i++) {
            Command command = running.get(i);
            for (int j = 0; j < command.requirementCount(); j++) {
                if (command.requirement(j) == subsystem) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------ registration

    private void registerRequirements(Command command) {
        for (int i = 0; i < command.requirementCount(); i++) {
            registerSubsystem(command.requirement(i));
        }
    }

    private void registerSubsystem(Subsystem subsystem) {
        if (!subsystems.contains(subsystem)) {
            subsystems.add(subsystem);
        }
    }

    private DefaultEntry findDefault(Subsystem subsystem) {
        for (int i = 0; i < defaults.size(); i++) {
            if (defaults.get(i).subsystem == subsystem) {
                return defaults.get(i);
            }
        }
        return null;
    }

    private DefaultEntry findDefaultFor(Command command) {
        for (int i = 0; i < defaults.size(); i++) {
            if (defaults.get(i).command == command) {
                return defaults.get(i);
            }
        }
        return null;
    }

    // ----------------------------------------------------------------- failures

    private void report(Object source, Phase phase, RuntimeException error) {
        errorCount++;
        lastError = error;
        lastErrorSource = source;
        lastErrorPhase = phase;
        if (strictError == null) {
            strictError = error;
        }
        if (source instanceof Command) {
            noteDefaultFailure((Command) source);
        }
    }

    private void noteDefaultFailure(Command command) {
        DefaultEntry entry = findDefaultFor(command);
        // Counted once per run(), however many calls into it failed.
        if (entry != null && !entry.failedThisRun) {
            entry.failedThisRun = true;
            entry.consecutiveFailures++;
            if (entry.consecutiveFailures >= MAX_DEFAULT_FAILURES) {
                entry.disabled = true;
            }
        }
    }

    private void noteDefaultSuccess(Command command) {
        DefaultEntry entry = findDefaultFor(command);
        if (entry != null && !entry.failedThisRun) {
            entry.consecutiveFailures = 0;
        }
    }

    private void clearDefaultFailureFlags() {
        for (int i = 0; i < defaults.size(); i++) {
            defaults.get(i).failedThisRun = false;
        }
    }

    private void rethrowStrictError() {
        RuntimeException error = strictError;
        strictError = null;
        if (strict && error != null) {
            throw error;
        }
    }

    // -------------------------------------------------------------------- util

    /** Removes the first count entries, keeping the rest in order. No allocation. */
    private static <T> void dropFirst(ArrayList<T> list, int count) {
        int remaining = list.size() - count;
        for (int i = 0; i < remaining; i++) {
            list.set(i, list.get(count + i));
        }
        for (int i = list.size() - 1; i >= remaining; i--) {
            list.remove(i);
        }
    }

    // ---------------------------------------------- read-only facts (telemetry)

    /** Number of commands currently running. */
    public int runningCount() {
        return running.size();
    }

    /** The i-th running command, in start order. Index access so reading allocates nothing. */
    public Command runningCommand(int index) {
        return running.get(index);
    }

    /** How long the most recent run() took, in clock nanoseconds. Zero before the first run(). */
    public long lastLoopNanos() {
        return lastLoopNanos;
    }

    /** The most recent failure, or null if none yet. */
    public Throwable getLastError() {
        return lastError;
    }

    /** The Command or Subsystem that failed most recently, or null. */
    public Object getLastErrorSource() {
        return lastErrorSource;
    }

    /** Where the most recent failure happened, or null. */
    public Phase getLastErrorPhase() {
        return lastErrorPhase;
    }

    /** Total failures recorded since this scheduler was created. */
    public int getErrorCount() {
        return errorCount;
    }

    /** True if this subsystem's default command was disabled after repeated failures. */
    public boolean isDefaultCommandDisabled(Subsystem subsystem) {
        DefaultEntry entry = findDefault(subsystem);
        return entry != null && entry.disabled;
    }
}
