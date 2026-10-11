package xbots.nexus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FakeClockTest {

    @Test
    void startsAtZero() {
        FakeClock clock = new FakeClock();

        assertEquals(0L, clock.nanos());
    }
}
