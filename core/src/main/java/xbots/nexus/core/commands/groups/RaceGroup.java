package xbots.nexus.core.commands.groups;

import xbots.nexus.core.Command;

/**
 * Parallel group type that runs all its children at the same time and finishes
 * as soon as ANY child finishes. Children still
 * running at that point are ended with {@code end(true)}.
 */
public final class RaceGroup extends ParallelGroupBase {

    public RaceGroup(Command... children) {
        super(children);
    }

    @Override
    protected boolean isGroupDone(boolean someChildFinishedThisLoop) {
        return someChildFinishedThisLoop || childCount() == 0;
    }
}
