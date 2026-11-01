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

class TriggerTest {

    private final List<String> log = new ArrayList<>();
    private final FakeClock clock = new FakeClock();
    private final Scheduler scheduler = new Scheduler(clock);

    // What the tests press and release.
    private boolean pressed = false;
    private boolean other = false;
    private boolean explode = false;
    private int reads = 0;

    private Trigger button() {
        return new Trigger(scheduler, () -> pressed);
    }

    // A command that never finishes by itself.
    private RecordingCommand endless(String name) {
        return new RecordingCommand(name, log);
    }

    // A command that runs once and finishes in the loop it starts.
    private RecordingCommand once(String name) {
        return new RecordingCommand(name, log).finishAfterExecutes(1);
    }

    private void assertLog(String... expected) {
        assertEquals(Arrays.asList(expected), log);
    }

    // ---------------------------------------------------- onTrue / onFalse

    @Test
    void onTrueSchedulesOnPressAndStartsInTheSameLoop() {
        button().onTrue(once("A"));
        scheduler.run(); // first poll: records "not pressed"
        assertLog();

        pressed = true;
        scheduler.run();

        assertLog("A:initialize", "A:execute", "A:end(false)");
    }

    @Test
    void onTrueFiresOncePerPressNotOnEveryLoopWhileHeld() {
        button().onTrue(once("A"));
        scheduler.run();

        pressed = true;
        scheduler.run();
        scheduler.run();
        scheduler.run();
        assertLog("A:initialize", "A:execute", "A:end(false)");

        pressed = false;
        scheduler.run();
        pressed = true;
        scheduler.run();
        assertLog("A:initialize", "A:execute", "A:end(false)",
                "A:initialize", "A:execute", "A:end(false)");
    }

    @Test
    void aButtonAlreadyHeldAtTheFirstPollDoesNotFire() {
        pressed = true;
        button().onTrue(once("A"));

        scheduler.run();
        scheduler.run();

        assertLog();
    }

    @Test
    void onFalseSchedulesOnRelease() {
        button().onFalse(once("A"));
        pressed = true;
        scheduler.run(); // first poll: records "pressed"
        scheduler.run();
        assertLog();

        pressed = false;
        scheduler.run();

        assertLog("A:initialize", "A:execute", "A:end(false)");
    }

    // ---------------------------------------------------- whileTrue

    @Test
    void whileTrueRunsWhileHeldAndIsCancelledOnRelease() {
        RecordingCommand command = endless("A");
        button().whileTrue(command);
        scheduler.run();

        pressed = true;
        scheduler.run();
        scheduler.run();
        assertTrue(scheduler.isScheduled(command));

        pressed = false;
        scheduler.run();

        assertFalse(scheduler.isScheduled(command));
        assertLog("A:initialize", "A:execute", "A:execute", "A:end(true)");
    }

    @Test
    void whileTrueDoesNotRestartACommandThatFinishedWhileHeld() {
        button().whileTrue(once("A"));
        scheduler.run();

        pressed = true;
        scheduler.run();
        scheduler.run();
        scheduler.run();
        pressed = false;
        scheduler.run();

        // It ran once, finished by itself, and the release had nothing left to cancel.
        assertLog("A:initialize", "A:execute", "A:end(false)");
    }

    // ---------------------------------------------------- toggleOnTrue

    @Test
    void toggleStartsOnTheFirstPressAndCancelsOnTheNext() {
        RecordingCommand command = endless("A");
        button().toggleOnTrue(command);
        scheduler.run();

        pressed = true;
        scheduler.run(); // press 1: start
        pressed = false;
        scheduler.run(); // release: nothing
        pressed = true;
        scheduler.run(); // press 2: cancel
        assertFalse(scheduler.isScheduled(command));
        pressed = false;
        scheduler.run();
        pressed = true;
        scheduler.run(); // press 3: start again

        assertLog("A:initialize", "A:execute",
                "A:execute",
                "A:end(true)",
                "A:initialize", "A:execute");
    }

    // ---------------------------------------------------- reading the condition

    @Test
    void theConditionIsReadOncePerLoopWhateverTheNumberOfBindings() {
        Trigger trigger = new Trigger(scheduler, () -> {
            reads++;
            return pressed;
        });
        trigger.onTrue(once("A")).onFalse(once("B")).whileTrue(endless("C"));

        scheduler.run();
        scheduler.run();
        scheduler.run();

        assertEquals(3, reads);
    }

    @Test
    void getReturnsTheValueFromTheLatestPoll() {
        Trigger trigger = button();
        assertFalse(trigger.get()); // before the first poll

        pressed = true;
        assertFalse(trigger.get()); // reading it does not read the condition again
        scheduler.run();
        assertTrue(trigger.get());

        pressed = false;
        assertTrue(trigger.get()); // still the value from the last poll
        scheduler.run();
        assertFalse(trigger.get());
    }

    // ---------------------------------------------------- combining triggers

    @Test
    void andIsTrueOnlyWhenBothAre() {
        Trigger a = button();
        Trigger b = new Trigger(scheduler, () -> other);
        a.and(b).onTrue(once("A"));
        scheduler.run();

        pressed = true;
        scheduler.run();
        assertLog();

        other = true;
        scheduler.run();
        assertLog("A:initialize", "A:execute", "A:end(false)");
    }

    @Test
    void orIsTrueWhenEitherIs() {
        Trigger a = button();
        Trigger b = new Trigger(scheduler, () -> other);
        a.or(b).onTrue(once("A"));
        scheduler.run();

        other = true;
        scheduler.run();

        assertLog("A:initialize", "A:execute", "A:end(false)");
    }

    @Test
    void negateFiresWhenTheOriginalGoesFalse() {
        button().negate().onTrue(once("A"));
        pressed = true;
        scheduler.run(); // baseline: original true, so negated false
        assertLog();

        pressed = false;
        scheduler.run();

        assertLog("A:initialize", "A:execute", "A:end(false)");
    }

    @Test
    void combinedTriggersDoNotReadTheOriginalConditionAgain() {
        Trigger a = new Trigger(scheduler, () -> {
            reads++;
            return pressed;
        });
        a.and(a.negate()).onTrue(once("NEVER"));

        scheduler.run();
        scheduler.run();

        // Two loops, one read each, even though two more triggers are built on it.
        assertEquals(2, reads);
        assertLog();
    }

    // ---------------------------------------------------- failures

    @Test
    void aThrowingConditionIsReportedAndNoPressIsInvented() {
        Trigger trigger = new Trigger(scheduler, () -> {
            if (explode) {
                throw new RuntimeException("sensor unplugged");
            }
            return pressed;
        });
        trigger.onTrue(once("A"));
        scheduler.run(); // baseline: not pressed

        explode = true;
        scheduler.run();
        assertEquals(1, scheduler.getErrorCount());
        assertEquals(Scheduler.Phase.POLL, scheduler.getLastErrorPhase());
        assertSame(trigger, scheduler.getLastErrorSource());
        assertLog();

        // After the failure the trigger still compares with the value from before it.
        explode = false;
        pressed = true;
        scheduler.run();
        assertLog("A:initialize", "A:execute", "A:end(false)");
    }

    // ---------------------------------------------------- construction

    @Test
    void rejectsBadArguments() {
        Trigger trigger = button();
        assertThrows(IllegalArgumentException.class, () -> new Trigger(null, () -> true));
        assertThrows(IllegalArgumentException.class, () -> new Trigger(scheduler, null));
        assertThrows(IllegalArgumentException.class, () -> trigger.onTrue(null));
        assertThrows(IllegalArgumentException.class, () -> trigger.onFalse(null));
        assertThrows(IllegalArgumentException.class, () -> trigger.whileTrue(null));
        assertThrows(IllegalArgumentException.class, () -> trigger.toggleOnTrue(null));
        assertThrows(IllegalArgumentException.class, () -> trigger.and(null));
        assertThrows(IllegalArgumentException.class, () -> trigger.or(null));
    }

    @Test
    void rejectsCombiningTriggersFromDifferentSchedulers() {
        Trigger mine = button();
        Trigger foreign = new Trigger(new Scheduler(clock), () -> true);
        assertThrows(IllegalArgumentException.class, () -> mine.and(foreign));
        assertThrows(IllegalArgumentException.class, () -> mine.or(foreign));
    }
}
