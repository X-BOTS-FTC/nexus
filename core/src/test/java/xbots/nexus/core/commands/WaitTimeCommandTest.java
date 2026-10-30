package xbots.nexus.core.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import xbots.nexus.core.FakeClock;
import xbots.nexus.core.RecordingCommand;
import xbots.nexus.core.RecordingSubsystem;
import xbots.nexus.core.Scheduler;

class WaitTimeCommandTest {

    private final List<String> log = new ArrayList<>();
    private final FakeClock clock = new FakeClock();
    private final Scheduler scheduler = new Scheduler(clock);

    @Test
    void finishesOnceTheFullDurationHasPassed() {
        WaitTimeCommand wait = new WaitTimeCommand(clock, 100);
        scheduler.schedule(wait);
        scheduler.run();
        assertTrue(scheduler.isScheduled(wait));

        clock.advanceMillis(99);
        scheduler.run();
        assertTrue(scheduler.isScheduled(wait));

        clock.advanceMillis(1);
        scheduler.run();
        assertFalse(scheduler.isScheduled(wait));
    }

    @Test
    void exactlyTheDurationCountsAsFinished() {
        // The check is "elapsed >= duration", so the boundary itself finishes.
        WaitTimeCommand wait = new WaitTimeCommand(clock, 50);
        scheduler.schedule(wait);
        scheduler.run();
        clock.advanceMillis(50);
        scheduler.run();
        assertFalse(scheduler.isScheduled(wait));
    }

    @Test
    void zeroDurationFinishesOnTheFirstLoop() {
        WaitTimeCommand wait = new WaitTimeCommand(clock, 0);
        scheduler.schedule(wait);
        scheduler.run();
        assertFalse(scheduler.isScheduled(wait));
    }

    @Test
    void timeIsMeasuredFromStartNotFromConstruction() {
        WaitTimeCommand wait = new WaitTimeCommand(clock, 100);
        clock.advanceMillis(500); // time passes before it is scheduled
        scheduler.schedule(wait);
        scheduler.run();
        clock.advanceMillis(99);
        scheduler.run();
        assertTrue(scheduler.isScheduled(wait));
    }

    @Test
    void canBeScheduledAgainAndWaitsAgain() {
        WaitTimeCommand wait = new WaitTimeCommand(clock, 100);
        scheduler.schedule(wait);
        scheduler.run();
        clock.advanceMillis(100);
        scheduler.run();
        assertFalse(scheduler.isScheduled(wait));

        scheduler.schedule(wait);
        scheduler.run();
        clock.advanceMillis(99);
        scheduler.run();
        assertTrue(scheduler.isScheduled(wait));
        clock.advanceMillis(1);
        scheduler.run();
        assertFalse(scheduler.isScheduled(wait));
    }

    @Test
    void stillCorrectWhenTheNanosecondCounterWrapsAround() {
        clock.advanceNanos(Long.MAX_VALUE - 1_000_000L);
        WaitTimeCommand wait = new WaitTimeCommand(clock, 5);
        scheduler.schedule(wait);
        scheduler.run();
        clock.advanceNanosWrapping(4_000_000L);
        scheduler.run();
        assertTrue(scheduler.isScheduled(wait));
        clock.advanceNanosWrapping(1_000_000L);
        scheduler.run();
        assertFalse(scheduler.isScheduled(wait));
    }

    @Test
    void requiresNothingAndDoesNotInterruptOtherCommands() {
        RecordingSubsystem arm = new RecordingSubsystem("arm", log);
        RecordingCommand moveArm = new RecordingCommand("MOVE", log, arm);
        scheduler.schedule(moveArm);
        scheduler.run();

        WaitTimeCommand wait = new WaitTimeCommand(clock, 100);
        assertEquals(0, wait.requirementCount());
        scheduler.schedule(wait);
        scheduler.run();

        assertTrue(scheduler.isScheduled(moveArm));
        assertTrue(scheduler.isScheduled(wait));
    }

    @Test
    void cancelledWaitJustStops() {
        WaitTimeCommand wait = new WaitTimeCommand(clock, 100);
        scheduler.schedule(wait);
        scheduler.run();
        scheduler.cancel(wait);
        scheduler.run();
        assertFalse(scheduler.isScheduled(wait));
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> new WaitTimeCommand(null, 10));
        assertThrows(IllegalArgumentException.class, () -> new WaitTimeCommand(clock, -1));
        // Too big to convert to nanoseconds without overflowing.
        assertThrows(IllegalArgumentException.class, () -> new WaitTimeCommand(clock, Long.MAX_VALUE));
    }
}
