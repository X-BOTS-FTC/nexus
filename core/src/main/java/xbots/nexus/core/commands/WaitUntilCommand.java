package xbots.nexus.core.commands;

import java.util.function.BooleanSupplier;
import xbots.nexus.core.Command;

/**
 * Finishes as soon as a condition becomes true.
 */
public final class WaitUntilCommand extends Command {

    private final BooleanSupplier condition;

    public WaitUntilCommand(BooleanSupplier condition) {
        super();
        if (condition == null) {
            throw new IllegalArgumentException("condition is null");
        }
        this.condition = condition;
    }

    @Override
    public boolean isFinished() {
        return condition.getAsBoolean();
    }
}
