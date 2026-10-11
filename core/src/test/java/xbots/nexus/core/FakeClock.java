package xbots.nexus.core;

/**
 * A clock that only moves when a test says so. Starts at zero.
 *
 * Time never goes backward: negative advances are rejected, because a real
 * monotonic clock cannot do that and a test that relies on it would pass here
 * and fail on the robot.
 */
public final class FakeClock implements Clock {

    private long now = 0L;

    @Override
    public long nanos() {
        return now;
    }

    /** Moves time forward by the given number of nanoseconds. */
    public void advanceNanos(long nanos) {
        if (nanos < 0) {
            throw new IllegalArgumentException("Time cannot go backward: " + nanos);
        }
        now = Math.addExact(now, nanos);
    }

    /** Moves time forward by the given number of milliseconds. */
    public void advanceMillis(long millis) {
        advanceNanos(Math.multiplyExact(millis, 1_000_000L));
    }
}
