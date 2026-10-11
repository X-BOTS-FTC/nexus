package xbots.nexus.core;

import java.util.List;

/**
 * Test helper: a subsystem that records every lifecycle call made to it.
 *
 * Entries look like "S:periodic", "S:safe". Several subsystems can share one log
 * list, so a test can assert the exact order of calls across subsystems and
 * commands.
 */
final class RecordingSubsystem extends Subsystem {

    private final String name;
    private final List<String> log;

    private Scheduler.Phase throwPhase;
    private int throwsRemaining;

    RecordingSubsystem(String name, List<String> log) {
        this.name = name;
        this.log = log;
    }

    /** Makes periodic() throw every time it is called. */
    RecordingSubsystem throwInPeriodic() {
        return throwInPeriodic(Integer.MAX_VALUE);
    }

    /** Makes periodic() throw for its first `times` calls only. */
    RecordingSubsystem throwInPeriodic(int times) {
        this.throwPhase = Scheduler.Phase.PERIODIC;
        this.throwsRemaining = times;
        return this;
    }

    /** Makes enterSafeState() throw every time it is called. */
    RecordingSubsystem throwInSafeState() {
        return throwInSafeState(Integer.MAX_VALUE);
    }

    /** Makes enterSafeState() throw for its first `times` calls only. */
    RecordingSubsystem throwInSafeState(int times) {
        this.throwPhase = Scheduler.Phase.SAFE_STATE;
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
    public void periodic() {
        log.add(name + ":periodic");
        maybeThrow(Scheduler.Phase.PERIODIC);
    }

    @Override
    public void enterSafeState() {
        log.add(name + ":safe");
        maybeThrow(Scheduler.Phase.SAFE_STATE);
    }
}