package xbots.nexus.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SystemClockTest {

    @Test
    void neverGoesBackwards() {
        // Declared as the interface type on purpose: this is how the Scheduler sees it.
        Clock clock = new SystemClock();

        long previous = clock.nanos();
        for (int i = 0; i < 100_000; i++) {
            long now = clock.nanos();
            // Subtraction form, as required by Clock's contract.
            assertTrue(now - previous >= 0, "clock went backwards on reading " + i);
            previous = now;
        }
    }

    @Test
    void advancesWithRealTime() throws InterruptedException {
        Clock clock = new SystemClock();

        long start = clock.nanos();
        Thread.sleep(50);
        long elapsed = clock.nanos() - start;

        // We asked for 50 ms but only require 25 ms. Sleep can return slightly early on some
        // platforms, and this test only needs to prove that the clock moves at roughly real
        // speed, not that sleep is precise.
        long requiredNanos = 25_000_000L;
        assertTrue(elapsed >= requiredNanos,
                "expected at least 25 ms to pass but measured " + elapsed + " ns");
    }
}
