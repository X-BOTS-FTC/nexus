package xbots.nexus.ftc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SystemClockTest {

    @Test
    void neverGoesBackward() {
        SystemClock clock = new SystemClock();

        long previous = clock.nanos();
        for (int i = 0; i < 100_000; i++) {
            long current = clock.nanos();
            assertTrue(current >= previous, "time went backward: " + previous + " -> " + current);
            previous = current;
        }
    }

    @Test
    void advancesAcrossASleep() throws InterruptedException {
        SystemClock clock = new SystemClock();

        long before = clock.nanos();
        Thread.sleep(1);
        long after = clock.nanos();

        assertTrue(after > before, "time did not advance across a sleep: " + before + " -> " + after);
    }
}
