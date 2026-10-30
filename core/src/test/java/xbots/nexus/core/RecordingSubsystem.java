package xbots.nexus.core;

import java.util.List;

/**
 * Test helper: a subsystem that records its hook calls into a shared log, as
 * "S:periodic" and "S:safe" (where S is the name given).
 *
 * throwInPeriodic() makes periodic() log its entry and then throw.
 */
public final class RecordingSubsystem extends Subsystem {

    private final String name;
    private final List<String> log;
    private boolean throwInPeriodic;

    public RecordingSubsystem(String name, List<String> log) {
        this.name = name;
        this.log = log;
    }

    public RecordingSubsystem throwInPeriodic() {
        this.throwInPeriodic = true;
        return this;
    }

    @Override
    public void periodic() {
        log.add(name + ":periodic");
        if (throwInPeriodic) {
            throw new RuntimeException("boom");
        }
    }

    @Override
    public void enterSafeState() {
        log.add(name + ":safe");
    }
}
