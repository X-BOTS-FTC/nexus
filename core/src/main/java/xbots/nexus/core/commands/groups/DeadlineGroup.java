package xbots.nexus.core.commands.groups;

import xbots.nexus.core.Command;

/**
 * Parallel group type that Runs all its children at the same time, and finishes
 * when the SELECTED deadline child finishes. The
 * deadline is the first constructor argument. The other children run alongside
 * it: any that finish
 * earlier are ended normally and stay finished, and any still running when the
 * deadline finishes
 * are ended with {@code end(true)}.
 *
 * <p>
 * Example: {@code new DeadlineGroup(drive, intake, claw)} ends when
 * {@code drive} does, however
 * the intake and claw are doing.
 *
 * <p>
 * If the deadline and another child finish in the same loop, both get
 * {@code end(false)}. The
 * deadline is child 0 internally, so it is also the first one ended.
 */
public final class DeadlineGroup extends ParallelGroupBase {

    private static final int DEADLINE_INDEX = 0;

    public DeadlineGroup(Command deadline, Command... others) {
        super(deadlineFirst(deadline, others));
    }

    @Override
    protected boolean isGroupDone(boolean someChildFinishedThisLoop) {
        // The deadline can only stop running by finishing: any failure propagates and
        // fails the whole group before this is asked.
        return !isChildRunning(DEADLINE_INDEX);
    }

    private static Command[] deadlineFirst(Command deadline, Command[] others) {
        if (deadline == null) {
            throw new IllegalArgumentException("deadline is null");
        }
        if (others == null) {
            throw new IllegalArgumentException("others is null");
        }
        Command[] all = new Command[others.length + 1];
        all[DEADLINE_INDEX] = deadline;
        System.arraycopy(others, 0, all, 1, others.length);
        return all;
    }
}
