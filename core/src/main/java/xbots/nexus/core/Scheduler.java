package xbots.nexus.core;

import java.util.ArrayList;

/**
 * Runs commands. One instance per OpMode, no static state (NEXUS.md, hard rule
 * 3).
 *
 * Built test by test. So far: scheduling, the per-loop run, and natural
 * finishing. Interruption, defaults, failure handling, and stop come next.
 *
 * Single-threaded. Call schedule() and run() only from the OpMode loop thread.
 */
public final class Scheduler {
    /**
     * the inital space for commands the array starts with (can grow
     * if needed, but we will rarely actually have 33 commands at the
     * same time)
     */
    private static final int INITIAL_CAPACITY = 32;

    // Held for time-based features (loop timing, waits). Unused until a test needs
    // it.
    private final Clock clock;

    // Commands waiting to start on the next run().
    private final ArrayList<Command> pending = new ArrayList<>(INITIAL_CAPACITY);

    // Commands that have been initialized and now execute every run().
    private final ArrayList<Command> running = new ArrayList<>(INITIAL_CAPACITY);

    public Scheduler(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock is null");
        }
        this.clock = clock;
    }

    /**
     * Requests that a command start. It is not initialized now; it starts at the
     * beginning of the next run(), so command code only ever runs at one
     * predictable point in the loop.
     */
    public void schedule(Command command) {
        if (command == null) {
            throw new IllegalArgumentException("command is null");
        }
        pending.add(command);
    }

    /**
     * Runs one loop: starts pending commands, executes every running command,
     * then ends and removes the ones that report finished (NEXUS.md loop order,
     * steps 4 and 5).
     */
    public void run() {
        startPending();
        // Index loop, not an iterator, so nothing is allocated each loop.
        for (int i = 0; i < running.size(); i++) {
            running.get(i).execute();
        }
        endFinished();
    }

    private void endFinished() {
        // Compact the list in place: finished commands are ended and dropped,
        // the rest are shifted down. No removal from the middle, no allocation.
        int count = running.size();
        int kept = 0;
        for (int i = 0; i < count; i++) {
            Command command = running.get(i);
            if (command.isFinished()) {
                command.end(false);
            } else {
                running.set(kept, command);
                kept++;
            }
        }
        for (int i = count - 1; i >= kept; i--) {
            running.remove(i);
        }
    }

    private void startPending() {
        // Snapshot the count: anything scheduled during initialize() waits for
        // the next run() instead of starting in the middle of this one.
        int count = pending.size();
        for (int i = 0; i < count; i++) {
            Command command = pending.get(i);
            command.initialize();
            running.add(command);
        }

        // Remove the started commands from the front, keeping any added meanwhile.
        int remaining = pending.size() - count;
        for (int i = 0; i < remaining; i++) {
            pending.set(i, pending.get(count + i));
        }
        for (int i = pending.size() - 1; i >= remaining; i--) {
            pending.remove(i);
        }
    }
}
