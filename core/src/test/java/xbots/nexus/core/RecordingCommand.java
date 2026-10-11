package xbots.nexus.core;

import java.util.List;

/**
 * Test helper: a command that records every lifecycle call made to it.
 *
 * Entries look like "A:initialize", "A:execute", "A:end(true)". Several commands
 * can share one log list, so a test can assert the exact order of calls across
 * commands (for example "A:end(true)" before "B:initialize").
 *
 * By default it never finishes and never throws. isFinished() itself is not
 * logged, so the log shows only what the scheduler did.
 *   finishAfterExecutes(n)  isFinished() is true once execute() has run n times
 *   throwOn(phase)          that lifecycle method logs its entry, then throws
 *   throwOn(phase, times)   same, but only for the first `times` calls
 * (For IS_FINISHED nothing is logged before the throw.)
 */
final class RecordingCommand extends Command {

    private final String name;
    private final List<String> log;

    private int finishAfterExecutes = -1; // negative means never finish
    private int executeCount;

    private Scheduler.Phase throwPhase;
    private int throwsRemaining;

    RecordingCommand(String name, List<String> log, Subsystem... requirements) {
        super(requirements);
        this.name = name;
        this.log = log;
    }

    /** Makes isFinished() true once execute() has been called n times (n may be 0). */
    RecordingCommand finishAfterExecutes(int executes) {
        this.finishAfterExecutes = executes;
        return this;
    }

    /** Makes the given lifecycle method throw every time it is called. */
    RecordingCommand throwOn(Scheduler.Phase phase) {
        return throwOn(phase, Integer.MAX_VALUE);
    }

    /** Makes the given lifecycle method throw for its first `times` calls only. */
    RecordingCommand throwOn(Scheduler.Phase phase, int times) {
        this.throwPhase = phase;
        this.throwsRemaining = times;
        return this;
    }

    private void maybeThrow(Scheduler.Phase phase) {
        if (phase == throwPhase && throwsRemaining > 0) {
            throwsRemaining--;
            throw new RuntimeException("boom");
        }
    }

    @Override
    public void initialize() {
        executeCount = 0; // per-run state is reset here, as NEXUS.md requires
        log.add(name + ":initialize");
        maybeThrow(Scheduler.Phase.INITIALIZE);
    }

    @Override
    public void execute() {
        executeCount++;
        log.add(name + ":execute");
        maybeThrow(Scheduler.Phase.EXECUTE);
    }

    @Override
    public boolean isFinished() {
        maybeThrow(Scheduler.Phase.IS_FINISHED);
        return finishAfterExecutes >= 0 && executeCount >= finishAfterExecutes;
    }

    @Override
    public void end(boolean interrupted) {
        log.add(name + ":end(" + interrupted + ")");
        maybeThrow(Scheduler.Phase.END);
    }
}
