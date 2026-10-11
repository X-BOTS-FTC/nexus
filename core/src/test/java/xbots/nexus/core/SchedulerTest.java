package xbots.nexus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchedulerTest {

    // JUnit creates a fresh instance of this class for every test, so each test
    // gets its own log, clock and scheduler.
    private final List<String> log = new ArrayList<>();
    private final FakeClock clock = new FakeClock();
    private final Scheduler scheduler = new Scheduler(clock);

    private RecordingCommand command(String name, Subsystem... requirements) {
        return new RecordingCommand(name, log, requirements);
    }

    private RecordingSubsystem subsystem(String name) {
        return new RecordingSubsystem(name, log);
    }

    private void assertLog(String... expected) {
        assertEquals(Arrays.asList(expected), log);
    }

    // ---------------------------------------------------- start and finish

    @Test
    void scheduledCommandInitializesOnceThenExecutesEveryLoop() {
        scheduler.schedule(command("A"));
        scheduler.run();
        scheduler.run();
        scheduler.run();

        assertLog("A:initialize", "A:execute", "A:execute", "A:execute");
    }

    @Test
    void scheduleDoesNotStartCommandUntilTheNextRun() {
        scheduler.schedule(command("A"));

        assertLog();

        scheduler.run();

        assertLog("A:initialize", "A:execute");
    }

    @Test
    void finishedCommandEndsNaturallyInTheSameLoopAndIsRemoved() {
        scheduler.schedule(command("A").finishAfterExecutes(2));
        scheduler.run();
        scheduler.run();

        assertLog("A:initialize", "A:execute", "A:execute", "A:end(false)");

        scheduler.run();

        // Nothing new: the command was removed after it ended.
        assertLog("A:initialize", "A:execute", "A:execute", "A:end(false)");
    }

    @Test
    void commandThatIsFinishedImmediatelyStillExecutesOnceBeforeEnding() {
        scheduler.schedule(command("A").finishAfterExecutes(0));
        scheduler.run();

        assertLog("A:initialize", "A:execute", "A:end(false)");
    }

    // -------------------------------------------------------- interruption

    @Test
    void secondCommandOnTheSameSubsystemInterruptsTheFirst() {
        Subsystem s = new Subsystem() { };

        scheduler.schedule(command("A", s));
        scheduler.run();
        scheduler.schedule(command("B", s));
        scheduler.run();

        // A must finish its cleanup before B touches the hardware.
        assertLog("A:initialize", "A:execute",
                "A:end(true)", "B:initialize", "B:execute");
    }

    @Test
    void commandsWithNoRequirementsDoNotConflict() {
        scheduler.schedule(command("A"));
        scheduler.schedule(command("B"));
        scheduler.run();
        scheduler.run();

        assertLog("A:initialize", "B:initialize", "A:execute", "B:execute",
                "A:execute", "B:execute");
    }

    @Test
    void commandsOnDifferentSubsystemsDoNotConflict() {
        Subsystem s1 = new Subsystem() { };
        Subsystem s2 = new Subsystem() { };

        scheduler.schedule(command("A", s1));
        scheduler.schedule(command("B", s2));
        scheduler.run();
        scheduler.run();

        assertLog("A:initialize", "B:initialize", "A:execute", "B:execute",
                "A:execute", "B:execute");
    }

    @Test
    void inTheSameLoopTheLaterCommandOnASubsystemWins() {
        Subsystem s = new Subsystem() { };

        scheduler.schedule(command("A", s));
        scheduler.schedule(command("B", s));
        scheduler.run();

        assertLog("A:initialize", "A:end(true)", "B:initialize", "B:execute");
    }

    @Test
    void schedulingARunningCommandIsIgnored() {
        RecordingCommand a = command("A");

        scheduler.schedule(a);
        scheduler.schedule(a); // duplicate in the same loop
        scheduler.run();
        scheduler.schedule(a); // already running
        scheduler.run();

        assertLog("A:initialize", "A:execute", "A:execute");
    }

    // -------------------------------------------------------------- cancel

    @Test
    void cancelEndsTheCommandAtTheStartOfTheNextRun() {
        RecordingCommand a = command("A");

        scheduler.schedule(a);
        scheduler.run();
        scheduler.cancel(a);

        // Not immediate: the request is queued like schedule().
        assertLog("A:initialize", "A:execute");

        scheduler.run();

        assertLog("A:initialize", "A:execute", "A:end(true)");

        scheduler.run();

        assertLog("A:initialize", "A:execute", "A:end(true)");
    }

    @Test
    void cancellingACommandThatIsNotRunningDoesNothing() {
        RecordingCommand a = command("A");

        scheduler.cancel(a);
        scheduler.run();

        assertLog();
    }

    @Test
    void scheduleThenCancelInOneLoopStartsThenEndsTheCommand() {
        RecordingCommand a = command("A");

        scheduler.schedule(a);
        scheduler.cancel(a);
        scheduler.run();

        // Requests are applied in the order they were made.
        assertLog("A:initialize", "A:end(true)");
    }

    @Test
    void cancelThenScheduleInOneLoopRestartsTheCommand() {
        RecordingCommand a = command("A");

        scheduler.schedule(a);
        scheduler.run();
        scheduler.cancel(a);
        scheduler.schedule(a);
        scheduler.run();

        assertLog("A:initialize", "A:execute",
                "A:end(true)", "A:initialize", "A:execute");
    }

    // ------------------------------------------------------ reentrancy, facts

    @Test
    void commandScheduledFromInsideARunWaitsForTheNextRun() {
        RecordingCommand b = command("B");
        Command a = new Command() {
            @Override
            public void initialize() {
                log.add("A:initialize");
            }

            @Override
            public void execute() {
                log.add("A:execute");
                scheduler.schedule(b);
            }
        };

        scheduler.schedule(a);
        scheduler.run();

        assertLog("A:initialize", "A:execute");

        scheduler.run();

        assertLog("A:initialize", "A:execute",
                "B:initialize", "A:execute", "B:execute");
    }

    @Test
    void isScheduledCoversQueuedAndRunningCommands() {
        RecordingCommand finishing = command("F").finishAfterExecutes(1);
        RecordingCommand lasting = command("L");

        assertFalse(scheduler.isScheduled(finishing));
        scheduler.schedule(finishing);
        assertTrue(scheduler.isScheduled(finishing)); // queued
        scheduler.run();
        assertFalse(scheduler.isScheduled(finishing)); // finished and removed

        scheduler.schedule(lasting);
        scheduler.run();
        assertTrue(scheduler.isScheduled(lasting)); // running
    }

    @Test
    void runningCommandsAreExposedForTelemetry() {
        RecordingCommand a = command("A");
        RecordingCommand b = command("B");
        RecordingCommand c = command("C").finishAfterExecutes(1);

        assertEquals(0, scheduler.runningCount());

        scheduler.schedule(a);
        scheduler.schedule(b);
        scheduler.schedule(c);
        scheduler.run();

        assertEquals(2, scheduler.runningCount());
        assertSame(a, scheduler.runningCommand(0));
        assertSame(b, scheduler.runningCommand(1));
    }

    @Test
    void loopTimeIsMeasuredWithTheClock() {
        Command slow = new Command() {
            @Override
            public void execute() {
                clock.advanceMillis(5);
            }
        };

        assertEquals(0L, scheduler.lastLoopNanos());

        scheduler.schedule(slow);
        scheduler.run();

        assertEquals(5_000_000L, scheduler.lastLoopNanos());
    }

    // ------------------------------------------------------------ periodic

    @Test
    void periodicRunsBeforeCommandsEveryLoop() {
        RecordingSubsystem s = subsystem("S");

        scheduler.schedule(command("A", s));
        scheduler.run();
        scheduler.run();

        assertLog("S:periodic", "A:initialize", "A:execute",
                "S:periodic", "A:execute");
    }

    @Test
    void periodicRunsInRegistrationOrder() {
        RecordingSubsystem s1 = subsystem("S1");
        RecordingSubsystem s2 = subsystem("S2");

        // S2 is mentioned first, so it is registered first.
        scheduler.schedule(command("B", s2));
        scheduler.schedule(command("A", s1));
        scheduler.run();

        assertLog("S2:periodic", "S1:periodic",
                "B:initialize", "A:initialize", "B:execute", "A:execute");
    }

    // ---------------------------------------------------- default commands

    @Test
    void defaultCommandStartsWhenItsSubsystemIsFree() {
        RecordingSubsystem s = subsystem("S");

        scheduler.setDefaultCommand(s, command("D", s));
        scheduler.run();
        scheduler.run();

        assertLog("S:periodic", "D:initialize", "D:execute",
                "S:periodic", "D:execute");
    }

    @Test
    void commandInterruptsTheDefaultAndTheDefaultReturnsTheLoopAfterItFinishes() {
        RecordingSubsystem s = subsystem("S");

        scheduler.setDefaultCommand(s, command("D", s));
        scheduler.run();
        scheduler.schedule(command("A", s).finishAfterExecutes(1));
        scheduler.run();
        scheduler.run();

        assertLog(
                "S:periodic", "D:initialize", "D:execute",
                "S:periodic", "D:end(true)", "A:initialize", "A:execute", "A:end(false)",
                "S:periodic", "D:initialize", "D:execute");
    }

    @Test
    void disabledDefaultCommandsDoNotStart() {
        RecordingSubsystem s = subsystem("S");

        scheduler.setDefaultCommandsEnabled(false);
        scheduler.setDefaultCommand(s, command("D", s));
        scheduler.run();

        assertLog("S:periodic");
    }

    @Test
    void cancellingADefaultCommandRestartsItImmediately() {
        Subsystem s = new Subsystem() { };
        RecordingCommand d = command("D", s);

        scheduler.setDefaultCommand(s, d);
        scheduler.run();
        scheduler.cancel(d);
        scheduler.run();

        assertLog("D:initialize", "D:execute",
                "D:end(true)", "D:initialize", "D:execute");
    }

    @Test
    void defaultCommandMustRequireExactlyItsSubsystem() {
        Subsystem s1 = new Subsystem() { };
        Subsystem s2 = new Subsystem() { };

        assertThrows(IllegalArgumentException.class,
                () -> scheduler.setDefaultCommand(s1, command("none")));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.setDefaultCommand(s1, command("other", s2)));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.setDefaultCommand(s1, command("both", s1, s2)));
    }

    @Test
    void aSubsystemCanHaveOnlyOneDefaultCommand() {
        Subsystem s = new Subsystem() { };

        scheduler.setDefaultCommand(s, command("D1", s));

        assertThrows(IllegalStateException.class,
                () -> scheduler.setDefaultCommand(s, command("D2", s)));
    }

    // ------------------------------------------------------------ failures

    @Test
    void executeFailureCancelsOnlyTheFailingCommand() {
        RecordingCommand a = command("A").throwOn(Scheduler.Phase.EXECUTE);

        scheduler.schedule(a);
        scheduler.schedule(command("B"));
        scheduler.run();

        assertLog("A:initialize", "B:initialize", "A:execute", "B:execute", "A:end(true)");
        assertEquals(1, scheduler.getErrorCount());
        assertSame(a, scheduler.getLastErrorSource());
        assertEquals(Scheduler.Phase.EXECUTE, scheduler.getLastErrorPhase());
        assertEquals("boom", scheduler.getLastError().getMessage());

        scheduler.run();

        // A is gone, B carries on.
        assertLog("A:initialize", "B:initialize", "A:execute", "B:execute", "A:end(true)",
                "B:execute");
    }

    @Test
    void initializeFailureMeansTheCommandNeverStartedSoItGetsNoEnd() {
        scheduler.schedule(command("A").throwOn(Scheduler.Phase.INITIALIZE));
        scheduler.run();
        scheduler.run();

        assertLog("A:initialize");
        assertEquals(1, scheduler.getErrorCount());
        assertEquals(Scheduler.Phase.INITIALIZE, scheduler.getLastErrorPhase());
        assertEquals(0, scheduler.runningCount());
    }

    @Test
    void isFinishedFailureCancelsTheCommand() {
        scheduler.schedule(command("A").throwOn(Scheduler.Phase.IS_FINISHED));
        scheduler.run();

        assertLog("A:initialize", "A:execute", "A:end(true)");
        assertEquals(Scheduler.Phase.IS_FINISHED, scheduler.getLastErrorPhase());
        assertEquals(0, scheduler.runningCount());
    }

    @Test
    void endFailureStillRemovesTheCommandAndLeavesTheListIntact() {
        RecordingCommand c = command("C");

        // A's end() throws; B finishes cleanly after it; C keeps running.
        scheduler.schedule(command("A").finishAfterExecutes(1).throwOn(Scheduler.Phase.END));
        scheduler.schedule(command("B").finishAfterExecutes(1));
        scheduler.schedule(c);
        scheduler.run();

        assertLog("A:initialize", "B:initialize", "C:initialize",
                "A:execute", "B:execute", "C:execute",
                "A:end(false)", "B:end(false)");
        assertEquals(1, scheduler.getErrorCount());
        assertEquals(Scheduler.Phase.END, scheduler.getLastErrorPhase());
        assertEquals(1, scheduler.runningCount());
        assertSame(c, scheduler.runningCommand(0));

        scheduler.run();

        // No duplicates, and A is not ended a second time.
        assertLog("A:initialize", "B:initialize", "C:initialize",
                "A:execute", "B:execute", "C:execute",
                "A:end(false)", "B:end(false)",
                "C:execute");
    }

    @Test
    void strictModeRethrowsAfterFinishingItsBookkeeping() {
        scheduler.setStrict(true);
        scheduler.schedule(command("A").throwOn(Scheduler.Phase.EXECUTE));
        scheduler.schedule(command("B"));

        RuntimeException thrown = assertThrows(RuntimeException.class, scheduler::run);

        assertEquals("boom", thrown.getMessage());
        // Everything an isolating run would do was still done.
        assertLog("A:initialize", "B:initialize", "A:execute", "B:execute", "A:end(true)");

        scheduler.run(); // no new failure, so no throw

        assertLog("A:initialize", "B:initialize", "A:execute", "B:execute", "A:end(true)",
                "B:execute");
    }

    @Test
    void periodicFailureIsReportedAndPeriodicKeepsRunning() {
        RecordingSubsystem s = subsystem("S").throwInPeriodic();

        scheduler.schedule(command("A", s));
        scheduler.run();
        scheduler.run();

        assertLog("S:periodic", "A:initialize", "A:execute",
                "S:periodic", "A:execute");
        assertEquals(2, scheduler.getErrorCount());
        assertSame(s, scheduler.getLastErrorSource());
        assertEquals(Scheduler.Phase.PERIODIC, scheduler.getLastErrorPhase());
    }

    @Test
    void defaultCommandIsDisabledAfterThreeConsecutiveFailures() {
        Subsystem s = new Subsystem() { };

        scheduler.setDefaultCommand(s, command("D", s).throwOn(Scheduler.Phase.EXECUTE));
        scheduler.run();
        scheduler.run();

        assertFalse(scheduler.isDefaultCommandDisabled(s));

        scheduler.run();

        assertTrue(scheduler.isDefaultCommandDisabled(s));
        assertEquals(3, scheduler.getErrorCount());

        scheduler.run(); // disabled: it does not start again

        assertLog("D:initialize", "D:execute", "D:end(true)",
                "D:initialize", "D:execute", "D:end(true)",
                "D:initialize", "D:execute", "D:end(true)");
    }

    @Test
    void defaultCommandThatFailsOnlyTwiceStaysEnabled() {
        Subsystem s = new Subsystem() { };

        scheduler.setDefaultCommand(s,
                command("D", s).throwOn(Scheduler.Phase.EXECUTE, 2));
        scheduler.run();
        scheduler.run();
        scheduler.run();
        scheduler.run();

        assertFalse(scheduler.isDefaultCommandDisabled(s));
        assertLog("D:initialize", "D:execute", "D:end(true)",
                "D:initialize", "D:execute", "D:end(true)",
                "D:initialize", "D:execute",
                "D:execute");
    }

    // ---------------------------------------------------------------- stop

    @Test
    void stopEndsRunningCommandsThenPutsSubsystemsInSafeState() {
        RecordingSubsystem s1 = subsystem("S1");
        RecordingSubsystem s2 = subsystem("S2");

        scheduler.schedule(command("A", s1));
        scheduler.schedule(command("B", s2));
        scheduler.run();
        scheduler.schedule(command("C", s1)); // queued, never started
        scheduler.stop();

        assertLog("S1:periodic", "S2:periodic",
                "A:initialize", "B:initialize", "A:execute", "B:execute",
                "A:end(true)", "B:end(true)", "S1:safe", "S2:safe");
        assertEquals(0, scheduler.runningCount());
    }

    @Test
    void stopContinuesCleanupWhenAnEndThrows() {
        RecordingSubsystem s1 = subsystem("S1");
        RecordingSubsystem s2 = subsystem("S2");

        scheduler.schedule(command("A", s1).throwOn(Scheduler.Phase.END));
        scheduler.schedule(command("B", s2));
        scheduler.run();
        scheduler.stop();

        assertLog("S1:periodic", "S2:periodic",
                "A:initialize", "B:initialize", "A:execute", "B:execute",
                "A:end(true)", "B:end(true)", "S1:safe", "S2:safe");
        assertEquals(1, scheduler.getErrorCount());
        assertEquals(Scheduler.Phase.END, scheduler.getLastErrorPhase());
    }

    @Test
    void nothingHappensAfterStop() {
        scheduler.schedule(command("A"));
        scheduler.run();
        scheduler.stop();

        RecordingCommand b = command("B");
        scheduler.schedule(b);
        scheduler.run();

        assertLog("A:initialize", "A:execute", "A:end(true)");
        assertFalse(scheduler.isScheduled(b));
    }
}
