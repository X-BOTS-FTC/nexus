package xbots.nexus.core.commands.groups;

import xbots.nexus.core.Command;

/**
 * Runs all its children at the same time and finishes when ALL of them have
 * finished.
 */

public final class ParallelGroup extends ParallelGroupBase {

    public ParallelGroup(Command... children) {
        super(children);
    }

    @Override
    protected boolean isGroupDone(boolean someChildFinishedThisLoop) {
        return !anyChildRunning();
    }
}
