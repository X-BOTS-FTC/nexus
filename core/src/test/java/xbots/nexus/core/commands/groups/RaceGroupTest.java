package xbots.nexus.core.commands.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import xbots.nexus.core.commands.WaitTimeCommand;
import xbots.nexus.core.commands.groups.RaceGroup;

class RaceGroupTest {

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
    void firstChildToFinishEndsTheRaceAndTheLoserIsInterrupted() {
        RaceGroup group = new RaceGroup(command("A").finishAfterExecutes(3), command("B"));

        scheduler.schedule(group);
        scheduler.run();
        scheduler.run();
        assertTrue(scheduler.isScheduled(group));
        scheduler.run();

        assertFalse(scheduler.isScheduled(group));
        // The loser still executes in the loop where the winner finishes.
        assertLog("A:initialize", "B:initialize",
                "A:execute", "B:execute",
                "A:execute", "B:execute",
                "A:execute", "B:execute", "A:end(false)", "B:end(true)");
    }

    @Test
    void childrenFinishingInTheSameLoopAreAllEndedNormally() {
        RaceGroup group = new RaceGroup(
                command("A").finishAfterExecutes(1),
                command("B").finishAfterExecutes(1),
                command("C"));

        scheduler.schedule(group);
        scheduler.run();

        assertFalse(scheduler.isScheduled(group));
        assertLog("A:initialize", "B:initialize", "C:initialize",
                "A:execute", "B:execute", "C:execute",
                "A:end(false)", "B:end(false)", "C:end(true)");
    }

    @Test
    void emptyRaceFinishesOnTheFirstLoop() {
        RaceGroup group = new RaceGroup();
        scheduler.schedule(group);
        scheduler.run();
        assertFalse(scheduler.isScheduled(group));
    }

    @Test
    void racingAgainstAWaitActsAsATimeout() {
        RaceGroup group = new RaceGroup(command("LONG"), new WaitTimeCommand(clock, 100));

        scheduler.schedule(group);
        scheduler.run();
        assertTrue(scheduler.isScheduled(group));

        clock.advanceMillis(100);
        scheduler.run();

        assertFalse(scheduler.isScheduled(group));
        assertLog("LONG:initialize", "LONG:execute", "LONG:execute", "LONG:end(true)");
    }

    @Test
    void cancelledRaceEndsEveryChildAsInterrupted() {
        RaceGroup group = new RaceGroup(command("A"), command("B"));
        scheduler.schedule(group);
        scheduler.run();

        scheduler.cancel(group);
        scheduler.run();

        assertLog("A:initialize", "B:initialize", "A:execute", "B:execute",
                "A:end(true)", "B:end(true)");
    }

    @Test
    void rejectsChildrenThatShareASubsystem() {
        RecordingSubsystem arm = new RecordingSubsystem("arm", new ArrayList<String>());
        assertThrows(IllegalArgumentException.class, () -> new RaceGroup(
                new RecordingCommand("A", log, arm),
                new RecordingCommand("B", log, arm)));
    }
}
