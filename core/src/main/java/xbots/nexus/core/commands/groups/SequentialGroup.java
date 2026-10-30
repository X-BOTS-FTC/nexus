package xbots.nexus.core.commands.groups;

import xbots.nexus.core.Command;

/**
 * Runs its children one after another. Each child starts when the previous one has finished, and
 * the group finishes when the last child does.
 */
public final class SequentialGroup extends CommandGroup {

    // Index of the next child that has not been started yet.
    private int nextIndex;
    // Index of the child that is initialized and not yet ended, or -1 if there is none.
    private int runningIndex = -1;

    public SequentialGroup(Command... children) {
        super(children);
    }

    @Override
    public void initialize() {
        nextIndex = 0;
        runningIndex = -1;
        startNext();
    }

    @Override
    public void execute() {
        if (runningIndex < 0) {
            return;
        }
        Command current = child(runningIndex);
        current.execute();
        if (current.isFinished()) {
            // Mark it as no longer running BEFORE calling end, so if end throws the group's own
            // end(true) does not end the same child a second time.
            runningIndex = -1;
            current.end(false);
            startNext();
        }
    }

    @Override
    public boolean isFinished() {
        return runningIndex < 0 && nextIndex >= childCount();
    }

    @Override
    public void end(boolean interrupted) {
        // If a child is still running here, the group is ending early (cancelled, interrupted by a
        // conflicting command, or failed), so that child was interrupted by definition.
        if (runningIndex >= 0) {
            Command running = child(runningIndex);
            runningIndex = -1;
            running.end(true);
        }
    }

    private void startNext() {
        if (nextIndex >= childCount()) {
            return;
        }
        Command next = child(nextIndex);
        next.initialize();
        // Only reached if initialize returned normally.
        runningIndex = nextIndex;
        nextIndex++;
    }
}
