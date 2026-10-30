package xbots.nexus.core.commands;

import xbots.nexus.core.Command;
import xbots.nexus.core.Clock;

/**
 * Finishes once a fixed amount of time has passed since it started.
 *
 * The time is measured from {@link #initialize()}, not from construction, so a wait that is
 * created early and scheduled later still waits the full duration, and the same instance can be
 * scheduled again and wait again.
 *
 */
public final class WaitTimeCommand extends Command {

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final Clock clock;
    private final long durationNanos;
    private long startNanos;

    public WaitTimeCommand(Clock clock, long millis) {
        super();
        if (clock == null) {
            throw new IllegalArgumentException("clock is null");
        }
        if (millis < 0) {
            throw new IllegalArgumentException("Wait time cannot be negative: " + millis);
        }
        // Reject values that would overflow when converted to nanoseconds, instead of silently
        // wrapping to a negative duration that finishes immediately.
        if (millis > Long.MAX_VALUE / NANOS_PER_MILLI) {
            throw new IllegalArgumentException("Wait time is too large: " + millis);
        }
        this.clock = clock;
        this.durationNanos = millis * NANOS_PER_MILLI;
    }

    @Override
    public void initialize() {
        startNanos = clock.nanos();
    }

    @Override
    public boolean isFinished() {
        return clock.nanos() - startNanos >= durationNanos;
    }
}
