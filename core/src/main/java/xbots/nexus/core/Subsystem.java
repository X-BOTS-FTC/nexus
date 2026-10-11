package xbots.nexus.core;

/**
 * A mechanism on the robot (drivetrain, arm, intake). Commands declare which
 * subsystems they require, and the scheduler uses that to decide which commands
 * conflict.
 *
 * Deliberately small. Default commands are registered with the scheduler, not
 * stored here (see NEXUS.md), so this class does not depend on the scheduler.
 *
 * Both hooks do nothing by default. Override only what the mechanism needs.
 */
public abstract class Subsystem {

    /**
     * Called once per loop, before any command runs (loop order step 2).
     * Use it for sensor reads and state updates. Must return quickly and must
     * not block.
     */
    public void periodic() {
    }

    /**
     * Called when the OpMode stops, after every running command has been ended.
     * Put the mechanism in a safe state here (motors to zero, and so on).
     * Must not throw if the hardware is already in that state.
     */
    public void enterSafeState() {
    }
}
