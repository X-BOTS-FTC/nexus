package xbots.nexus.ftc;

import xbots.nexus.core.Clock;

public final class SystemClock implements Clock {
    @Override
    public long nanos() {
        return System.nanoTime();
    }
}
