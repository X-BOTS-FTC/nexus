package xbots.nexus.core.commands.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import xbots.nexus.core.Command;
import xbots.nexus.core.FakeClock;
import xbots.nexus.core.RecordingCommand;
import xbots.nexus.core.RecordingSubsystem;
import xbots.nexus.core.Scheduler;

/**
 * Tests for ParallelGroup. They also cover the behavior that comes from ParallelGroupBase and is
 * shared with RaceGroup and DeadlineGroup (failure handling, cancellation, constructor checks).
 */
class ParallelGroupTest {

    private final List<String> log = new ArrayList<>();
    private final FakeClock clock = new FakeClock();
    private final Scheduler scheduler = new Scheduler(clock);

    private RecordingCommand command(String name) {
        return new RecordingCommand(name, log);
    }

    // Subsystems log "periodic" every loop. Give them their own throwaway log so those entries
    // do not show up in the command log that the tests compare.
    private RecordingSubsystem quietSubsystem(String name) {
        return new RecordingSubsystem(name, new ArrayList<String>());
    }

    private void assertLog(String... expected) {
        assertEquals(Arrays.asList(expected), log);
    }

    // ---------------------------------------------------- finishing

    @Test
    void finishesWhenAllChildrenHaveFinished() {
        RecordingCommand a = command("A").finishAfterExecutes(1);
        RecordingCommand b = command("B").finishAfterExecutes(3);
        ParallelGroup group = new ParallelGroup(a, b);

        scheduler.schedule(group);
        scheduler.run();
        assertTrue(scheduler.isScheduled(group));
        scheduler.run();
        assertTrue(scheduler.isScheduled(group));
        scheduler.run();
        assertFalse(scheduler.isScheduled(group));

        // A finished in loop 1 and is not executed again. B runs three loops.
        assertLog("A:initialize", "B:initialize",
                "A:execute", "B:execute", "A:end(false)",
                "B:execute",
                "B:execute", "B:end(false)");
    }

    @Test
    void childrenFinishingInTheSameLoopAreBothEndedNormally() {
        ParallelGroup group = new ParallelGroup(
                command("A").finishAfterExecutes(1),
                command("B").finishAfterExecutes(1));

        scheduler.schedule(group);
        scheduler.run();

        assertFalse(scheduler.isScheduled(group));
        assertLog("A:initialize", "B:initialize", "A:execute", "B:execute",
                "A:end(false)", "B:end(false)");
    }

    @Test
    void emptyGroupFinishesOnTheFirstLoop() {
        ParallelGroup group = new ParallelGroup();
        scheduler.schedule(group);
        scheduler.run();
        assertFalse(scheduler.isScheduled(group));
    }

    @Test
    void canBeScheduledAgain() {
        ParallelGroup group = new ParallelGroup(command("A").finishAfterExecutes(1));
        scheduler.schedule(group);
        scheduler.run();
        log.clear();

        scheduler.schedule(group);
        scheduler.run();

        assertFalse(scheduler.isScheduled(group));
        assertLog("A:initialize", "A:execute", "A:end(false)");
    }

    @Test
    void worksInsideASequence() {
        ParallelGroup parallel = new ParallelGroup(
                command("A").finishAfterExecutes(1),
                command("B").finishAfterExecutes(2));
        SequentialGroup group = new SequentialGroup(parallel, command("C").finishAfterExecutes(1));

        scheduler.schedule(group);
        scheduler.run();
        scheduler.run();
        scheduler.run();

        assertFalse(scheduler.isScheduled(group));
        assertLog("A:initialize", "B:initialize", "A:execute", "B:execute", "A:end(false)",
                "B:execute", "B:end(false)",
                "C:initialize", "C:execute", "C:end(false)");
    }

    // ---------------------------------------------------- requirements

    @Test
    void requiresTheUnionOfItsChildren() {
        RecordingSubsystem lift = quietSubsystem("lift");
        RecordingSubsystem arm = quietSubsystem("arm");
        ParallelGroup group = new ParallelGroup(
                new RecordingCommand("A", log, lift),
                new RecordingCommand("B", log, arm));

        assertEquals(2, group.requirementCount());
        assertSame(lift, group.requirement(0));
        assertSame(arm, group.requirement(1));
    }

    @Test
    void rejectsChildrenThatShareASubsystem() {
        RecordingSubsystem arm = quietSubsystem("arm");
        assertThrows(IllegalArgumentException.class, () -> new ParallelGroup(
                new RecordingCommand("A", log, arm),
                new RecordingCommand("B", log, arm)));
    }

    @Test
    void childrenWithNoRequirementsMayShareAGroup() {
        // Does not throw.
        new ParallelGroup(command("A"), command("B"));
    }

    // ---------------------------------------------------- stopping early

    @Test
    void cancelledGroupEndsEveryRunningChildAsInterrupted() {
        ParallelGroup group = new ParallelGroup(command("A"), command("B"));
        scheduler.schedule(group);
        scheduler.run();

        scheduler.cancel(group);
        scheduler.run();

        assertFalse(scheduler.isScheduled(group));
        assertLog("A:initialize", "B:initialize", "A:execute", "B:execute",
                "A:end(true)", "B:end(true)");
    }

    @Test
    void cancelledGroupDoesNotEndChildrenThatAlreadyFinished() {
        ParallelGroup group = new ParallelGroup(command("A").finishAfterExecutes(1), command("B"));
        scheduler.schedule(group);
        scheduler.run();

        scheduler.cancel(group);
        scheduler.run();

        // A already got end(false). Only B is ended now.
        assertLog("A:initialize", "B:initialize", "A:execute", "B:execute", "A:end(false)",
                "B:end(true)");
    }

    // ---------------------------------------------------- failures

    @Test
    void childThrowingInExecuteFailsTheGroupAndEndsEveryRunningChild() {
        RecordingCommand a = command("A").throwOn(Scheduler.Phase.EXECUTE);
        RecordingCommand b = command("B");
        ParallelGroup group = new ParallelGroup(a, b);

        scheduler.schedule(group);
        scheduler.run();

        assertFalse(scheduler.isScheduled(group));
        assertEquals(1, scheduler.getErrorCount());
        assertSame(group, scheduler.getLastErrorSource());
        assertEquals(Scheduler.Phase.EXECUTE, scheduler.getLastErrorPhase());
        // B never got to execute, but it was started, so it is ended.
        assertLog("A:initialize", "B:initialize", "A:execute", "A:end(true)", "B:end(true)");
    }

    @Test
    void childThrowingInInitializeEndsTheChildrenAlreadyStarted() {
        RecordingCommand a = command("A");
        RecordingCommand b = command("B").throwOn(Scheduler.Phase.INITIALIZE);
        RecordingCommand c = command("C");
        ParallelGroup group = new ParallelGroup(a, b, c);

        scheduler.schedule(group);
        scheduler.run();

        assertFalse(scheduler.isScheduled(group));
        assertEquals(1, scheduler.getErrorCount());
        assertEquals(Scheduler.Phase.INITIALIZE, scheduler.getLastErrorPhase());
        // The scheduler gives a failed initialize no end, so the group ends A itself. B failed
        // its own initialize and gets no end. C was never started.
        assertLog("A:initialize", "B:initialize", "A:end(true)");
    }

    @Test
    void everyChildIsEndedEvenWhenSeveralEndsThrow() {
        RecordingCommand a = command("A").throwOn(Scheduler.Phase.END);
        RecordingCommand b = command("B").throwOn(Scheduler.Phase.END);
        RecordingCommand c = command("C");
        ParallelGroup group = new ParallelGroup(a, b, c);
        scheduler.schedule(group);
        scheduler.run();

        scheduler.cancel(group);
        scheduler.run();

        // A's end threw, but B and C were still ended.
        assertLog("A:initialize", "B:initialize", "C:initialize",
                "A:execute", "B:execute", "C:execute",
                "A:end(true)", "B:end(true)", "C:end(true)");
        // One failure is reported for the group, with the second exception attached to it.
        assertEquals(1, scheduler.getErrorCount());
        assertEquals(1, scheduler.getLastError().getSuppressed().length);
    }

    // ---------------------------------------------------- construction

    @Test
    void rejectsBadChildren() {
        RecordingCommand a = command("A");
        assertThrows(IllegalArgumentException.class, () -> new ParallelGroup((Command[]) null));
        assertThrows(IllegalArgumentException.class, () -> new ParallelGroup(a, null));
        assertThrows(IllegalArgumentException.class, () -> new ParallelGroup(a, a));
    }
}
