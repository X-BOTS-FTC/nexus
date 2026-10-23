package xbots.nexus.core;

public final class SystemClock implements Clock {
    @Override
    public long nanos() {
        return System.nanoTime();
    }
}
