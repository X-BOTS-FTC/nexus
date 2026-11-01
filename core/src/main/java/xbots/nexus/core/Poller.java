package xbots.nexus.core;

/**
 * Something the {@link Scheduler} calls once per loop, after every subsystem's {@code periodic()} and
 * before queued schedule and cancel requests are applied. Triggers are just one type of pollers.
 */
public interface Poller {

    void poll();
}
