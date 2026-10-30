package xbots.nexus.core.commands;

import xbots.nexus.core.Command;
import xbots.nexus.core.Subsystem;

/**
 * Runs a bit of code once and finishes immediately. Useful for oneshot actions
 * such as resetting encoders or closing claws.
 * THis command always finishes in a single loop.
 */
public final class InstantCommand extends Command {

    private final Runnable action;

    public InstantCommand(Runnable action, Subsystem... requirements) {
        super(requirements);
        if (action == null) {
            throw new IllegalArgumentException("action is null");
        }
        this.action = action;
    }

    @Override
    public void initialize() {
        action.run();
    }

    @Override
    public boolean isFinished() {
        return true;
    }
}
