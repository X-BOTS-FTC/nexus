package xbots.nexus.core;

import java.util.ArrayList;
import java.util.function.BooleanSupplier;

/**
 * Turns a true/false condition into commands: "when this becomes true,
 * run that".
 * can be a gamepad button or any other condition that must be true
 *
 * usage:
 * new Trigger(scheduler, () -&gt; gamepad1.a).onTrue(new RaiseLift(lift));
 * new Trigger(scheduler, () -&gt; gamepad1.right_bumper).whileTrue(new RunIntake(intake));
 *
 * <p>The vocabulary:
 * <ul>
 *   <li>{@link #onTrue}: schedule the command when the condition goes from false to true (a press).</li>
 *   <li>{@link #onFalse}: schedule it when the condition goes from true to false (a release).</li>
 *   <li>{@link #whileTrue}: schedule it on a press and cancel it on a release (while held).</li>
 *   <li>{@link #toggleOnTrue}: on each press, cancel the command if it is running, otherwise schedule it.</li>
 * </ul>
 */
public final class Trigger implements Poller {

    private enum Kind { ON_TRUE, ON_FALSE, WHILE_TRUE, TOGGLE_ON_TRUE }

    private static final class Binding {
        final Kind kind;
        final Command command;

        Binding(Kind kind, Command command) {
            this.kind = kind;
            this.command = command;
        }
    }

    private final Scheduler scheduler;
    private final BooleanSupplier condition;
    private final ArrayList<Binding> bindings = new ArrayList<>();

    private boolean initialized;
    private boolean current;

    public Trigger(Scheduler scheduler, BooleanSupplier condition) {
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler is null");
        }
        if (condition == null) {
            throw new IllegalArgumentException("condition is null");
        }
        this.scheduler = scheduler;
        this.condition = condition;
        // Last, after the fields are set: from here on the scheduler may call poll().
        scheduler.addPoller(this);
    }

    // ------------------------------------------------------------- bindings

    /** Schedules the command each time the condition goes from false to true. */
    public Trigger onTrue(Command command) {
        return bind(Kind.ON_TRUE, command);
    }

    /** Schedules the command each time the condition goes from true to false. */
    public Trigger onFalse(Command command) {
        return bind(Kind.ON_FALSE, command);
    }

    /**
     * Schedules the command when the condition goes true and cancels it when it goes false. If the
     * command finishes by itself while the condition is still true, it is not started again until
     * the next time the condition goes true.
     */
    public Trigger whileTrue(Command command) {
        return bind(Kind.WHILE_TRUE, command);
    }

    /** On each false-to-true change, cancels the command if it is scheduled, otherwise schedules it. */
    public Trigger toggleOnTrue(Command command) {
        return bind(Kind.TOGGLE_ON_TRUE, command);
    }

    private Trigger bind(Kind kind, Command command) {
        if (command == null) {
            throw new IllegalArgumentException("command is null");
        }
        bindings.add(new Binding(kind, command));
        return this;
    }

    // ---------------------------------------------------------- combinations

    /**
     * The value from the most recent poll. False before the first poll. Reading it does not read the
     * underlying condition again.
     */
    public boolean get() {
        return current;
    }

    /** A trigger that is true while both this and the other are true. */
    public Trigger and(Trigger other) {
        checkSameScheduler(other);
        return new Trigger(scheduler, () -> this.get() && other.get());
    }

    /** A trigger that is true while either this or the other is true. */
    public Trigger or(Trigger other) {
        checkSameScheduler(other);
        return new Trigger(scheduler, () -> this.get() || other.get());
    }

    /** A trigger that is true while this one is false. */
    public Trigger negate() {
        return new Trigger(scheduler, () -> !this.get());
    }

    private void checkSameScheduler(Trigger other) {
        if (other == null) {
            throw new IllegalArgumentException("other is null");
        }
        if (other.scheduler != scheduler) {
            throw new IllegalArgumentException("Triggers belong to different schedulers");
        }
    }

    // ---------------------------------------------------------------- polling

    @Override
    public void poll() {
        // If this throws, current and initialized are untouched, so no edge is invented.
        boolean now = condition.getAsBoolean();
        if (!initialized) {
            initialized = true;
            current = now;
            return;
        }
        boolean previous = current;
        current = now;
        if (now && !previous) {
            onRisingEdge();
        } else if (!now && previous) {
            onFallingEdge();
        }
    }

    private void onRisingEdge() {
        for (int i = 0; i < bindings.size(); i++) {
            Binding binding = bindings.get(i);
            switch (binding.kind) {
                case ON_TRUE:
                case WHILE_TRUE:
                    scheduler.schedule(binding.command);
                    break;
                case TOGGLE_ON_TRUE:
                    if (scheduler.isScheduled(binding.command)) {
                        scheduler.cancel(binding.command);
                    } else {
                        scheduler.schedule(binding.command);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private void onFallingEdge() {
        for (int i = 0; i < bindings.size(); i++) {
            Binding binding = bindings.get(i);
            switch (binding.kind) {
                case ON_FALSE:
                    scheduler.schedule(binding.command);
                    break;
                case WHILE_TRUE:
                    scheduler.cancel(binding.command);
                    break;
                default:
                    break;
            }
        }
    }
}
