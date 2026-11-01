package xbots.nexus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchedulerPollerTest {

    private final List<String> log = new ArrayList<>();
    private final FakeClock clock = new FakeClock();
    private final Scheduler scheduler = new Scheduler(clock);

    private boolean scheduledOnce = false;

    private void assertLog(String... expected) {
        assertEquals(Arrays.asList(expected), log);
    }

    @Test
    void pollersRunAfterSubsystemPeriodicAndBeforeCommandsStart() {
        RecordingSubsystem subsystem = new RecordingSubsystem("S", log);
        RecordingCommand command = new RecordingCommand("C", log, subsystem);
        scheduler.addPoller(() -> log.add("POLL"));

        scheduler.schedule(command);
        scheduler.run();

        assertLog("S:periodic", "POLL", "C:initialize", "C:execute");
    }

    @Test
    void pollersRunInTheOrderTheyWereAdded() {
        scheduler.addPoller(() -> log.add("P1"));
        scheduler.addPoller(() -> log.add("P2"));
        scheduler.addPoller(() -> log.add("P3"));

        scheduler.run();

        assertLog("P1", "P2", "P3");
    }

    @Test
    void pollersRunEveryLoop() {
        scheduler.addPoller(() -> log.add("POLL"));
        scheduler.run();
        scheduler.run();
        scheduler.run();
        assertLog("POLL", "POLL", "POLL");
    }

    @Test
    void aCommandScheduledByAPollerStartsInTheSameLoop() {
        RecordingCommand command = new RecordingCommand("C", log);
        scheduler.addPoller(() -> {
            if (!scheduledOnce) {
                scheduledOnce = true;
                scheduler.schedule(command);
            }
        });

        scheduler.run();

        assertLog("C:initialize", "C:execute");
    }

    @Test
    void aThrowingPollerIsReportedAndTheOthersStillRun() {
        RuntimeException boom = new RuntimeException("bad poll");
        Poller bad = () -> {
            throw boom;
        };
        scheduler.addPoller(() -> log.add("BEFORE"));
        scheduler.addPoller(bad);
        scheduler.addPoller(() -> log.add("AFTER"));

        scheduler.run();

        assertLog("BEFORE", "AFTER");
        assertEquals(1, scheduler.getErrorCount());
        assertSame(boom, scheduler.getLastError());
        assertSame(bad, scheduler.getLastErrorSource());
        assertEquals(Scheduler.Phase.POLL, scheduler.getLastErrorPhase());
    }

    @Test
    void aThrowingPollerIsCalledAgainNextLoop() {
        Poller bad = () -> {
            throw new RuntimeException("bad poll");
        };
        scheduler.addPoller(bad);

        scheduler.run();
        scheduler.run();

        assertEquals(2, scheduler.getErrorCount());
    }

    @Test
    void strictModeRethrowsAPollerFailureAfterTheLoopFinishes() {
        scheduler.setStrict(true);
        scheduler.addPoller(() -> {
            throw new RuntimeException("bad poll");
        });
        scheduler.addPoller(() -> log.add("AFTER"));

        assertThrows(RuntimeException.class, scheduler::run);

        // The rest of the loop still ran before the failure was rethrown.
        assertLog("AFTER");
    }

    @Test
    void nothingIsPolledAfterStop() {
        scheduler.addPoller(() -> log.add("POLL"));
        scheduler.stop();
        scheduler.run();
        assertLog();
    }

    @Test
    void rejectsANullPoller() {
        assertThrows(IllegalArgumentException.class, () -> scheduler.addPoller(null));
    }
}
