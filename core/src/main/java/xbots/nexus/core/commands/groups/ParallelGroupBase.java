package xbots.nexus.core.commands.groups;

import xbots.nexus.core.Command;

/**
 * Shared base class for groups that run all their children at the same time: {@link ParallelGroup},
 * {@link RaceGroup} and {@link DeadlineGroup}. They differ only in WHEN the group is done, which each
 * subclass supplies through {@link #isGroupDone(boolean)}.
 *
 * <p>Every loop, the group does these steps in order:
 * <ol>
 *   <li>executes every child that is still running, in the order they were given;</li>
 *   <li>asks every running child {@code isFinished}, once;</li>
 *   <li>ends the finished children with {@code end(false)}, in order;</li>
 *   <li>asks the subclass whether the group is done. If so, every child still running is ended with
 *   {@code end(true)} and the group finishes.</li>
 * </ol>
 */
public abstract class ParallelGroupBase extends CommandGroup {

    // running[i] is true from when child i's initialize returned until just before its end is called.
    private final boolean[] running;
    // Scratch space for step 2 of each loop. Reused every loop to avoid allocating.
    private final boolean[] finishedNow;
    private boolean done;

    protected ParallelGroupBase(Command... children) {
        super(children);
        this.running = new boolean[childCount()];
        this.finishedNow = new boolean[childCount()];
        checkNoSharedSubsystems();
    }

    /**
     * Called once per loop after the finished children have been ended.
     *
     * @param someChildFinishedThisLoop true if at least one child finished in this loop
     * @return true if the group should finish now. Children still running are then ended with
     *     {@code end(true)}.
     */
    protected abstract boolean isGroupDone(boolean someChildFinishedThisLoop);

    /** True if child {@code index} has started and not yet ended. */
    protected final boolean isChildRunning(int index) {
        return running[index];
    }

    /** True if at least one child has started and not yet ended. */
    protected final boolean anyChildRunning() {
        for (int i = 0; i < running.length; i++) {
            if (running[i]) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void initialize() {
        done = false;
        for (int i = 0; i < running.length; i++) {
            running[i] = false;
        }
        for (int i = 0; i < childCount(); i++) {
            try {
                child(i).initialize();
            } catch (RuntimeException error) {
                // Children 0..i-1 are started and the scheduler will not call our end, so end
                // them here. Child i threw in initialize, so it is not running and gets no end.
                throw endRunningChildren(error);
            }
            running[i] = true;
        }
    }

    @Override
    public void execute() {
        int count = childCount();

        // 1. Execute every running child.
        for (int i = 0; i < count; i++) {
            if (running[i]) {
                child(i).execute();
            }
        }

        // 2. Ask every running child whether it finished, once.
        boolean someFinished = false;
        for (int i = 0; i < count; i++) {
            finishedNow[i] = running[i] && child(i).isFinished();
            if (finishedNow[i]) {
                someFinished = true;
            }
        }

        // 3. End the finished children normally. Mark each as not running BEFORE its end call,
        // so a child whose end throws is never ended a second time.
        for (int i = 0; i < count; i++) {
            if (finishedNow[i]) {
                running[i] = false;
                child(i).end(false);
            }
        }

        // 4. Let the subclass decide whether the group is done.
        if (isGroupDone(someFinished)) {
            RuntimeException error = endRunningChildren(null);
            done = true;
            if (error != null) {
                throw error;
            }
        }
    }

    @Override
    public boolean isFinished() {
        return done;
    }

    @Override
    public void end(boolean interrupted) {
        // On a normal finish nothing is running any more. Otherwise the group is ending early
        // (cancelled, interrupted by a conflicting command, or failed), so whatever is still
        // running was interrupted by definition.
        RuntimeException error = endRunningChildren(null);
        if (error != null) {
            throw error;
        }
    }

    /**
     * Ends every running child with {@code end(true)}, in order, no matter what any of them throws.
     *
     * @param firstError an exception already being handled, or null
     * @return the first exception (with any later ones attached as suppressed), or null if there
     *     was none
     */
    private RuntimeException endRunningChildren(RuntimeException firstError) {
        RuntimeException error = firstError;
        for (int i = 0; i < running.length; i++) {
            if (running[i]) {
                running[i] = false;
                try {
                    child(i).end(true);
                } catch (RuntimeException thrown) {
                    if (error == null) {
                        error = thrown;
                    } else {
                        error.addSuppressed(thrown);
                    }
                }
            }
        }
        return error;
    }

    private void checkNoSharedSubsystems() {
        for (int i = 0; i < childCount(); i++) {
            Command a = child(i);
            for (int j = i + 1; j < childCount(); j++) {
                Command b = child(j);
                for (int x = 0; x < a.requirementCount(); x++) {
                    for (int y = 0; y < b.requirementCount(); y++) {
                        if (a.requirement(x) == b.requirement(y)) {
                            throw new IllegalArgumentException("Children " + i + " and " + j
                                    + " require the same subsystem, so they cannot run in parallel");
                        }
                    }
                }
            }
        }
    }
}
