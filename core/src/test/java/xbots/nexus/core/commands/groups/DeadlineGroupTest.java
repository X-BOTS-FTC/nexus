package xbots.nexus.core.commands.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class DeadlineGroupTest {

    private final List<String> log = new ArrayList<>();
    private final FakeClock clock = new FakeClock();
    private final Scheduler scheduler = new Scheduler(clock);

    private RecordingCommand command(String name) {
        return new RecordingCommand(name, log);
    }

    private void assertLog(String... expected) {
        assertEquals(Arrays.asList(expected), log);
    }

    @Test
    void finishesWhenTheDeadlineFinishesAndInterruptsTheOthers() {
        RecordingCommand deadline = command("D").finishAfterExecutes(2);
        RecordingCommand slow = command("X"); // never finishes by itself
        RecordingCommand quick = command("Y").finishAfterExecutes(1);
        DeadlineGroup group = new DeadlineGroup(deadline, slow, quick);

        scheduler.schedule(group);
        scheduler.run();
        // Y finished early and is ended normally. The group keeps going.
        assertTrue(scheduler.isScheduled(group));
        scheduler.run();

        assertFalse(scheduler.isScheduled(group));
        // Y is not executed again after it finished. X is interrupted when D finishes.
        assertLog("D:initialize", "X:initialize", "Y:initialize",
                "D:execute", "X:execute", "Y:execute", "Y:end(false)",
                "D:execute", "X:execute", "D:end(false)", "X:end(true)");
    }

    @Test
    void otherChildrenFinishingDoesNotEndTheGroup() {
        DeadlineGroup group = new DeadlineGroup(
                command("D"), command("X").finishAfterExecutes(1));

        scheduler.schedule(group);
        scheduler.run();
        scheduler.run();
        scheduler.run();

        assertTrue(scheduler.isScheduled(group));
    }

    @Test
    void deadlineAndAnotherChildFinishingTogetherAreBothEndedNormally() {
        DeadlineGroup group = new DeadlineGroup(
                command("D").finishAfterExecutes(1), command("X").finishAfterExecutes(1));

        scheduler.schedule(group);
        scheduler.run();

        assertFalse(scheduler.isScheduled(group));
        assertLog("D:initialize", "X:initialize", "D:execute", "X:execute",
                "D:end(false)", "X:end(false)");
    }

    @Test
    void deadlineAloneBehavesLikeThatCommand() {
        DeadlineGroup group = new DeadlineGroup(command("D").finishAfterExecutes(1));
        scheduler.schedule(group);
        scheduler.run();

        assertFalse(scheduler.isScheduled(group));
        assertLog("D:initialize", "D:execute", "D:end(false)");
    }

    @Test
    void cancelledGroupEndsEveryRunningChildAsInterrupted() {
        DeadlineGroup group = new DeadlineGroup(command("D"), command("X"));
        scheduler.schedule(group);
        scheduler.run();

        scheduler.cancel(group);
        scheduler.run();

        assertLog("D:initialize", "X:initialize", "D:execute", "X:execute",
                "D:end(true)", "X:end(true)");
    }

    @Test
    void canBeScheduledAgain() {
        DeadlineGroup group = new DeadlineGroup(
                command("D").finishAfterExecutes(1), command("X"));
        scheduler.schedule(group);
        scheduler.run();
        log.clear();

        scheduler.schedule(group);
        scheduler.run();

        assertFalse(scheduler.isScheduled(group));
        assertLog("D:initialize", "X:initialize", "D:execute", "X:execute",
                "D:end(false)", "X:end(true)");
    }

    @Test
    void rejectsBadArguments() {
        RecordingCommand d = command("D");
        RecordingSubsystem arm = new RecordingSubsystem("arm", new ArrayList<String>());

        assertThrows(IllegalArgumentException.class, () -> new DeadlineGroup(null));
        assertThrows(IllegalArgumentException.class, () -> new DeadlineGroup(d, (Command[]) null));
        assertThrows(IllegalArgumentException.class, () -> new DeadlineGroup(d, d));
        assertThrows(IllegalArgumentException.class, () -> new DeadlineGroup(
                new RecordingCommand("A", log, arm),
                new RecordingCommand("B", log, arm)));
    }
}
