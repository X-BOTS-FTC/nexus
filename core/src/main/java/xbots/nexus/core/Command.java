package xbots.nexus.core;

/**
 * A unit of robot behavior with a lifecycle run by the Scheduler.
 *
 * Lifecycle, as guaranteed by the scheduler (see NEXUS.md):
 *   initialize()  once, when the command starts
 *   execute()     once per loop; runs at least once before isFinished() is checked
 *   isFinished()  checked after each execute(); true means the command is done
 *   end(boolean)  exactly once for every initialize(), however the command stops;
 *                 true if it was interrupted or cancelled, false if isFinished()
 *                 returned true. A command that never started never gets end().
 *
 * Commands are reusable: the same instance may be scheduled again after it
 * ends. Reset ALL state in initialize(), never rely on field initializers or
 * the constructor for per-run state.
 *
 * All methods run on the OpMode loop thread and must return quickly. Never
 * sleep or block inside a command.
 */
public abstract class Command {

    private static final Subsystem[] NO_REQUIREMENTS = new Subsystem[0];

    private final Subsystem[] requirements;

    /**
     * @param requirements the subsystems this command needs exclusive use of.
     *                     Fixed for the life of the command.
     */
    protected Command(Subsystem... requirements) {
        if (requirements == null || requirements.length == 0) {
            this.requirements = NO_REQUIREMENTS;
            return;
        }
        // Copy so the caller cannot change our requirements by keeping their array.
        Subsystem[] copy = new Subsystem[requirements.length];
        for (int i = 0; i < requirements.length; i++) {
            if (requirements[i] == null) {
                throw new IllegalArgumentException("Requirement " + i + " is null");
            }
            copy[i] = requirements[i];
        }
        this.requirements = copy;
    }

    /** Called once when the command starts. Reset all per-run state here. */
    public void initialize() {
    }

    /** Called once per loop while the command runs. */
    public void execute() {
    }

    /**
     * Returns true when the command has finished its job. The default is false,
     * so a command that does not override this runs until cancelled or interrupted.
     */
    public boolean isFinished() {
        return false;
    }

    /**
     * Called exactly once after initialize(), when the command stops. Put
     * mechanisms in a safe state here.
     *
     * @param interrupted true if the command was stopped before isFinished()
     *                    returned true (interrupted, cancelled, or OpMode stop)
     */
    public void end(boolean interrupted) {
    }

    /** Number of required subsystems. */
    public final int requirementCount() {
        return requirements.length;
    }

    /**
     * The i-th required subsystem. Index access instead of returning the array
     * or a collection, so the scheduler can read requirements every loop without
     * allocating and nobody can modify them.
     */
    public final Subsystem requirement(int index) {
        return requirements[index];
    }
}
