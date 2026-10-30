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
import xbots.nexus.core.commands.WaitTimeCommand;

class SequentialGroupTest {

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

    // ---------------------------------------------------- order and timing

    @Test
    void runsChildrenInOrderEachStartingWhenThePreviousFinished() {
        RecordingCommand a = command("A").finishAfterExecutes(2);
        RecordingCommand b = command("B").finishAfterExecutes(1);
        SequentialGroup group = new SequentialGroup(a, b);

        scheduler.schedule(group);
        scheduler.run(); // A starts and executes once
        scheduler.run(); // A executes again, finishes; B starts
        assertTrue(scheduler.isScheduled(group));
        scheduler.run(); // B executes and finishes; the group is done

        assertFalse(scheduler.isScheduled(group));
        assertLog("A:initialize", "A:execute", "A:execute", "A:end(false)",
                "B:initialize", "B:execute", "B:end(false)");
    }

    @Test
    void atMostOneChildFinishesPerLoop() {
        // finishAfterExecutes(0) means "finished as soon as I have executed once".
        RecordingCommand a = command("A").finishAfterExecutes(0);
        RecordingCommand b = command("B").finishAfterExecutes(0);
        RecordingCommand c = command("C").finishAfterExecutes(0);
        SequentialGroup group = new SequentialGroup(a, b, c);

        scheduler.schedule(group);
        scheduler.run();
        // B is initialized in this loop but has not executed yet.
        assertLog("A:initialize", "A:execute", "A:end(false)", "B:initialize");
        assertTrue(scheduler.isScheduled(group));

        scheduler.run();
        assertLog("A:initialize", "A:execute", "A:end(false)", "B:initialize",
                "B:execute", "B:end(false)", "C:initialize");
        assertTrue(scheduler.isScheduled(group));

        scheduler.run();
        assertFalse(scheduler.isScheduled(group));
    }

    @Test
    void emptyGroupFinishesOnTheFirstLoop() {
        SequentialGroup group = new SequentialGroup();
        scheduler.schedule(group);
        scheduler.run();
        assertFalse(scheduler.isScheduled(group));
    }

    @Test
    void canBeScheduledAgainAndRunsAllChildrenAgain() {
        SequentialGroup group = new SequentialGroup(command("A").finishAfterExecutes(1));
        scheduler.schedule(group);
        scheduler.run();
        assertFalse(scheduler.isScheduled(group));

        log.clear();
        scheduler.schedule(group);
        scheduler.run();
        assertLog("A:initialize", "A:execute", "A:end(false)");
        assertFalse(scheduler.isScheduled(group));
    }

    @Test
    void waitInsideASequenceStartsCountingWhenItsTurnComes() {
        SequentialGroup group = new SequentialGroup(
                new WaitTimeCommand(clock, 100),
                command("A").finishAfterExecutes(1));

        scheduler.schedule(group);
        scheduler.run(); // the wait starts at time 0
        clock.advanceMillis(99);
        scheduler.run();
        assertLog(); // A has not started

        clock.advanceMillis(1);
        scheduler.run(); // the wait finishes; A starts
        assertLog("A:initialize");
    }

    @Test
    void nestedGroupsRunInOrder() {
        SequentialGroup inner = new SequentialGroup(
                command("A").finishAfterExecutes(1),
                command("B").finishAfterExecutes(1));
        SequentialGroup outer = new SequentialGroup(inner, command("C").finishAfterExecutes(1));

        scheduler.schedule(outer);
        scheduler.run();
        scheduler.run();
        scheduler.run();

        assertFalse(scheduler.isScheduled(outer));
        assertLog("A:initialize", "A:execute", "A:end(false)",
                "B:initialize", "B:execute", "B:end(false)",
                "C:initialize", "C:execute", "C:end(false)");
    }

    // ---------------------------------------------------- requirements

    @Test
    void requiresTheUnionOfItsChildrenEachSubsystemOnce() {
        RecordingSubsystem lift = quietSubsystem("lift");
        RecordingSubsystem arm = quietSubsystem("arm");
        SequentialGroup group = new SequentialGroup(
                new RecordingCommand("A", log, lift),
                new RecordingCommand("B", log, arm, lift));

        assertEquals(2, group.requirementCount());
        assertSame(lift, group.requirement(0));
        assertSame(arm, group.requirement(1));
    }

    @Test
    void conflictingCommandInterruptsTheGroupAndEndsTheRunningChild() {
        RecordingSubsystem lift = quietSubsystem("lift");
        RecordingSubsystem arm = quietSubsystem("arm");
        // The group requires the arm even though only B uses it, and B has not started yet.
        SequentialGroup group = new SequentialGroup(
                new RecordingCommand("A", log, lift),
                new RecordingCommand("B", log, arm));
        scheduler.schedule(group);
        scheduler.run();

        scheduler.schedule(new RecordingCommand("C", log, arm));
        scheduler.run();

        assertFalse(scheduler.isScheduled(group));
        assertLog("A:initialize", "A:execute", "A:end(true)", "C:initialize", "C:execute");
    }

    // ---------------------------------------------------- stopping early

    @Test
    void cancelledGroupEndsOnlyTheRunningChildWithInterrupted() {
        RecordingCommand a = command("A");
        RecordingCommand b = command("B");
        SequentialGroup group = new SequentialGroup(a, b);
        scheduler.schedule(group);
        scheduler.run();

        scheduler.cancel(group);
        scheduler.run();

        // B never started, so it gets no initialize and no end.
        assertLog("A:initialize", "A:execute", "A:end(true)");
        assertFalse(scheduler.isScheduled(group));
    }

    // ---------------------------------------------------- failures

    @Test
    void childThrowingInExecuteFailsTheGroupAndEndsThatChild() {
        RecordingCommand a = command("A").throwOn(Scheduler.Phase.EXECUTE);
        RecordingCommand b = command("B");
        SequentialGroup group = new SequentialGroup(a, b);

        scheduler.schedule(group);
        scheduler.run();

        assertFalse(scheduler.isScheduled(group));
        assertEquals(1, scheduler.getErrorCount());
        assertSame(group, scheduler.getLastErrorSource());
        assertEquals(Scheduler.Phase.EXECUTE, scheduler.getLastErrorPhase());
        // The failed child is ended as interrupted, and B never starts.
        assertLog("A:initialize", "A:execute", "A:end(true)");
    }

    @Test
    void childThrowingInInitializeGetsNoEndAndStopsTheSequence() {
        RecordingCommand a = command("A").finishAfterExecutes(1);
        RecordingCommand b = command("B").throwOn(Scheduler.Phase.INITIALIZE);
        RecordingCommand c = command("C");
        SequentialGroup group = new SequentialGroup(a, b, c);

        scheduler.schedule(group);
        scheduler.run();

        assertFalse(scheduler.isScheduled(group));
        assertEquals(1, scheduler.getErrorCount());
        // B logged its initialize before throwing, but a failed initialize gets no end.
        assertLog("A:initialize", "A:execute", "A:end(false)", "B:initialize");
    }

    @Test
    void childThrowingInEndIsNotEndedAgain() {
        RecordingCommand a = command("A").finishAfterExecutes(1).throwOn(Scheduler.Phase.END);
        RecordingCommand b = command("B");
        SequentialGroup group = new SequentialGroup(a, b);

        scheduler.schedule(group);
        scheduler.run();

        assertFalse(scheduler.isScheduled(group));
        assertEquals(1, scheduler.getErrorCount());
        // Exactly one end call for A, and B never starts.
        assertLog("A:initialize", "A:execute", "A:end(false)");
    }

    // ---------------------------------------------------- construction

    @Test
    void rejectsBadChildren() {
        RecordingCommand a = command("A");
        assertThrows(IllegalArgumentException.class, () -> new SequentialGroup((Command[]) null));
        assertThrows(IllegalArgumentException.class, () -> new SequentialGroup(a, null));
        assertThrows(IllegalArgumentException.class, () -> new SequentialGroup(a, a));
    }

    @Test
    void changingTheCallersArrayAfterConstructionHasNoEffect() {
        RecordingCommand a = command("A").finishAfterExecutes(1);
        RecordingCommand other = command("OTHER").finishAfterExecutes(1);
        Command[] array = {a};
        SequentialGroup group = new SequentialGroup(array);
        array[0] = other;

        scheduler.schedule(group);
        scheduler.run();

        assertLog("A:initialize", "A:execute", "A:end(false)");
    }
}
