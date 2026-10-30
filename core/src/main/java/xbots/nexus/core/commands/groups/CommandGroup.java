package xbots.nexus.core.commands.groups;

import java.util.ArrayList;
import xbots.nexus.core.Command;
import xbots.nexus.core.Subsystem;

/**
 * Base class for commands that run other commands (sequential, and later parallel, race, deadline).
 * The scheduler doesnt see the group's children, only the group itself. Group has to be its own
 * "scheduler" for all the commands it orchestrates, which lets it control its own behavior.
 */
public abstract class CommandGroup extends Command {

    private final Command[] children;

    protected CommandGroup(Command... children) {
        // checkedRequirements also validates the children, so this line is what rejects bad input.
        // It is a static method because Java requires the super(...) call to come first, before
        // any of our own fields can be used.
        super(checkedRequirements(children));
        Command[] copy = new Command[children.length];
        System.arraycopy(children, 0, copy, 0, children.length);
        this.children = copy;
    }

    /** Number of children in this group. */
    protected final int childCount() {
        return children.length;
    }

    /** The child at the given position, in the order they were given to the constructor. */
    protected final Command child(int index) {
        return children[index];
    }

    private static Subsystem[] checkedRequirements(Command[] children) {
        if (children == null) {
            throw new IllegalArgumentException("children is null");
        }
        ArrayList<Subsystem> union = new ArrayList<>();
        for (int i = 0; i < children.length; i++) {
            Command child = children[i];
            if (child == null) {
                throw new IllegalArgumentException("Child " + i + " is null");
            }
            for (int j = 0; j < i; j++) {
                if (children[j] == child) {
                    throw new IllegalArgumentException(
                            "Child " + i + " is the same instance as child " + j);
                }
            }
            for (int r = 0; r < child.requirementCount(); r++) {
                Subsystem subsystem = child.requirement(r);
                // Subsystem does not override equals, so contains() compares by identity.
                if (!union.contains(subsystem)) {
                    union.add(subsystem);
                }
            }
        }
        return union.toArray(new Subsystem[0]);
    }
}
