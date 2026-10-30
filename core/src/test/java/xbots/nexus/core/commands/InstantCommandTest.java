package xbots.nexus.core.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import xbots.nexus.core.FakeClock;
import xbots.nexus.core.RecordingCommand;
import xbots.nexus.core.RecordingSubsystem;
import xbots.nexus.core.Scheduler;
import xbots.nexus.core.commands.groups.SequentialGroup;

class InstantCommandTest {

    private final List<String> log = new ArrayList<>();
    private final FakeClock clock = new FakeClock();
    private final Scheduler scheduler = new Scheduler(clock);

    private int runs = 0;

    private void assertLog(String... expected) {
        assertEquals(Arrays.asList(expected), log);
    }

    // Subsystems log "periodic" every loop. Give them their own throwaway log so those entries
    // do not show up in the command log that the tests compare.
    private RecordingSubsystem quietSubsystem(String name) {
        return new RecordingSubsystem(name, new ArrayList<String>());
    }

    @Test
    void runsTheActionOnceAndFinishesInTheSameLoop() {
        InstantCommand instant = new InstantCommand(() -> runs++);
        scheduler.schedule(instant);
        scheduler.run();

        assertEquals(1, runs);
        assertFalse(scheduler.isScheduled(instant));

        // Nothing runs it again on later loops.
        scheduler.run();
        scheduler.run();
        assertEquals(1, runs);
    }

    @Test
    void doesNotRunUntilTheSchedulerRuns() {
        // Scheduling is deferred to the start of the next run().
        InstantCommand instant = new InstantCommand(() -> runs++);
        scheduler.schedule(instant);
        assertEquals(0, runs);
        scheduler.run();
        assertEquals(1, runs);
    }

    @Test
    void runsTheActionAgainEachTimeItIsScheduled() {
        InstantCommand instant = new InstantCommand(() -> runs++);
        scheduler.schedule(instant);
        scheduler.run();
        scheduler.schedule(instant);
        scheduler.run();
        assertEquals(2, runs);
    }

    @Test
    void withoutRequirementsItInterruptsNothing() {
        RecordingSubsystem arm = quietSubsystem("arm");
        RecordingCommand moveArm = new RecordingCommand("MOVE", log, arm);
        scheduler.schedule(moveArm);
        scheduler.run();

        InstantCommand instant = new InstantCommand(() -> log.add("ACTION"));
        assertEquals(0, instant.requirementCount());
        scheduler.schedule(instant);
        scheduler.run();

        assertTrue(scheduler.isScheduled(moveArm));
        assertLog("MOVE:initialize", "MOVE:execute", "ACTION", "MOVE:execute");
    }

    @Test
    void withARequirementItInterruptsTheCommandUsingThatSubsystem() {
        RecordingSubsystem arm = quietSubsystem("arm");
        RecordingCommand moveArm = new RecordingCommand("MOVE", log, arm);
        scheduler.schedule(moveArm);
        scheduler.run();

        InstantCommand instant = new InstantCommand(() -> log.add("ACTION"), arm);
        assertEquals(1, instant.requirementCount());
        assertSame(arm, instant.requirement(0));
        scheduler.schedule(instant);
        scheduler.run();

        assertFalse(scheduler.isScheduled(moveArm));
        // The old command ends as interrupted before the instant's action runs.
        assertLog("MOVE:initialize", "MOVE:execute", "MOVE:end(true)", "ACTION");
    }

    @Test
    void runsInItsTurnInsideASequence() {
        RecordingCommand first = new RecordingCommand("A", log).finishAfterExecutes(1);
        RecordingCommand last = new RecordingCommand("B", log).finishAfterExecutes(1);
        SequentialGroup group = new SequentialGroup(
                first, new InstantCommand(() -> log.add("ACTION")), last);

        scheduler.schedule(group);
        scheduler.run(); // A runs and finishes; the instant's action runs as it starts
        assertLog("A:initialize", "A:execute", "A:end(false)", "ACTION");
        scheduler.run(); // the instant finishes; B starts
        scheduler.run(); // B runs and finishes

        assertFalse(scheduler.isScheduled(group));
        assertLog("A:initialize", "A:execute", "A:end(false)", "ACTION",
                "B:initialize", "B:execute", "B:end(false)");
    }

    @Test
    void throwingActionIsReportedAndTheSchedulerKeepsRunning() {
        RuntimeException boom = new RuntimeException("servo missing");
        InstantCommand instant = new InstantCommand(() -> {
            throw boom;
        });
        scheduler.schedule(instant);
        scheduler.run();

        assertFalse(scheduler.isScheduled(instant));
        assertEquals(1, scheduler.getErrorCount());
        assertSame(boom, scheduler.getLastError());
        assertSame(instant, scheduler.getLastErrorSource());
        assertEquals(Scheduler.Phase.INITIALIZE, scheduler.getLastErrorPhase());

        // The scheduler is still usable.
        scheduler.run();
    }

    @Test
    void rejectsNullAction() {
        assertThrows(IllegalArgumentException.class, () -> new InstantCommand(null));
    }
}
