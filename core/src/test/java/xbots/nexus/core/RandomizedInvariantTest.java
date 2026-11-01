package xbots.nexus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import xbots.nexus.core.commands.InstantCommand;
import xbots.nexus.core.commands.WaitTimeCommand;
import xbots.nexus.core.commands.groups.DeadlineGroup;
import xbots.nexus.core.commands.groups.ParallelGroup;
import xbots.nexus.core.commands.groups.RaceGroup;
import xbots.nexus.core.commands.groups.SequentialGroup;

/**
 * Builds random trees of commands, runs them under random scheduling, cancelling and (in half the
 * trials) random exceptions, and checks rules that must hold whatever happens. It finds the
 * combinations nobody thought to write a unit test for, such as a cancel arriving while a race
 * inside a sequence is ending.
 *
 * <p>THE RULES CHECKED
 * <ul>
 *   <li>A command that started successfully gets exactly one {@code end}. One whose
 *   {@code initialize} threw gets none.</li>
 *   <li>{@code execute}, {@code isFinished} and {@code end} are never called on a command that is not
 *   running, and {@code initialize} is never called on one that is already running.</li>
 *   <li>After {@code scheduler.stop()}, nothing is left running.</li>
 *   <li>Two commands running in the scheduler at once never share a subsystem.</li>
 *   <li>{@code scheduler.run()} never throws (the scheduler is not in strict mode).</li>
 *   <li>In the trials without injected exceptions, the scheduler reports zero errors.</li>
 * </ul>
 *
 * <p>DESIGN DECISIONS:
 * <ul>
 *   <li>The seed is fixed, so the test gives the same result on every run and on every machine.
 *   Each trial uses its own seed (SEED + trial number), and a failure message names that trial and
 *   seed, so one failing trial can be rerun alone. Change SEED to explore different cases.</li>
 *   <li>The checking is done by the leaf commands themselves ({@link CheckedCommand}), which track
 *   their own state, rather than by comparing logs. That is what lets the test stay correct for
 *   trees nobody predicted the output of.</li>
 *   <li>Trees are generated so that parallel, race and deadline children never share a subsystem
 *   (those groups reject that on purpose). The subsystems are split between the children. Sequential
 *   children and the top-level commands may share subsystems, so the scheduler's conflict handling
 *   gets exercised too.</li>
 *   <li>Every subsystem has a default command, so default-command starts, interruptions and the
 *   disable-after-failures rule are part of the mix.</li>
 *   <li>The test also checks it did real work (many initializes, and errors in the exception
 *   trials). Without that, a generator bug could make every trial trivially pass.</li>
 * </ul>
 */
class RandomizedInvariantTest {

    private static final long SEED = 20261031L;
    private static final int TRIALS = 500;
    private static final int LOOPS_PER_TRIAL = 200;
    private static final int MAX_DEPTH = 3;
    private static final int SUBSYSTEM_COUNT = 3;

    @Test
    void randomCommandTreesNeverBreakTheLifecycleRules() {
        int totalInitializes = 0;
        int totalChaosErrors = 0;

        for (int trial = 0; trial < TRIALS; trial++) {
            long trialSeed = SEED + trial;
            Trial t = new Trial(trialSeed);
            t.run();

            String where = "trial " + trial + " (seed " + trialSeed + ", exceptions "
                    + (t.chaos ? "on" : "off") + ")";
            assertTrue(t.context.violations.isEmpty(), where + ": " + t.context.summary());
            if (!t.chaos) {
                assertEquals(0, t.scheduler.getErrorCount(),
                        where + ": unexpected scheduler error: " + t.scheduler.getLastError());
            } else {
                totalChaosErrors += t.scheduler.getErrorCount();
            }
            totalInitializes += t.initializeCount();
        }

        assertTrue(totalInitializes > 2000,
                "The trials did too little work to mean anything: " + totalInitializes);
        assertTrue(totalChaosErrors > 0, "No exceptions were ever injected and reported");
    }

    // ---------------------------------------------------------------------------------------

    /** Collects rule violations, tagged with the loop number they happened in. */
    private static final class Context {
        final List<String> violations = new ArrayList<>();
        int loop;

        void report(String message) {
            violations.add("loop " + loop + ": " + message);
        }

        String summary() {
            return violations.subList(0, Math.min(10, violations.size())).toString();
        }
    }

    /**
     * A leaf command that checks the lifecycle rules on itself. It finishes after a set number of
     * executes, and can be told to throw from one phase every time that phase is called.
     */
    private static final class CheckedCommand extends Command {
        private final String name;
        private final Context context;
        private final int finishAfter;
        private final Scheduler.Phase throwPhase;

        private boolean running;
        private int executes;
        int initCount; // successful initializes
        int endCount; // ends that were allowed

        CheckedCommand(String name, Context context, int finishAfter,
                Scheduler.Phase throwPhase, Subsystem... requirements) {
            super(requirements);
            this.name = name;
            this.context = context;
            this.finishAfter = finishAfter;
            this.throwPhase = throwPhase;
        }

        @Override
        public void initialize() {
            if (running) {
                context.report(name + ": initialize while already running");
            }
            executes = 0;
            // A command whose initialize throws never became running, so it must get no end.
            maybeThrow(Scheduler.Phase.INITIALIZE);
            running = true;
            initCount++;
        }

        @Override
        public void execute() {
            if (!running) {
                context.report(name + ": execute while not running");
            }
            executes++;
            maybeThrow(Scheduler.Phase.EXECUTE);
        }

        @Override
        public boolean isFinished() {
            if (!running) {
                context.report(name + ": isFinished while not running");
            }
            maybeThrow(Scheduler.Phase.IS_FINISHED);
            return executes >= finishAfter;
        }

        @Override
        public void end(boolean interrupted) {
            if (running) {
                // Counts as ended even if this call throws, as the rules say.
                running = false;
                endCount++;
            } else {
                context.report(name + ": end while not running (double end, or end without a start)");
            }
            maybeThrow(Scheduler.Phase.END);
        }

        void checkAtRest() {
            if (running) {
                context.report(name + ": still running after scheduler.stop()");
            }
            if (initCount != endCount) {
                context.report(name + ": " + initCount + " successful initializes but " + endCount
                        + " ends");
            }
        }

        private void maybeThrow(Scheduler.Phase phase) {
            if (phase == throwPhase) {
                throw new RuntimeException(name + " threw on purpose in " + phase);
            }
        }
    }

    /** One random scenario: a random tree set, a random schedule of events, then a stop. */
    private static final class Trial {
        final Random rng;
        final boolean chaos;
        final FakeClock clock = new FakeClock();
        final Scheduler scheduler = new Scheduler(clock);
        final Context context = new Context();
        final List<CheckedCommand> leaves = new ArrayList<>();
        final List<Subsystem> subsystems = new ArrayList<>();
        final List<Command> roots = new ArrayList<>();

        Trial(long seed) {
            rng = new Random(seed);
            chaos = rng.nextBoolean();
        }

        int initializeCount() {
            int total = 0;
            for (CheckedCommand leaf : leaves) {
                total += leaf.initCount;
            }
            return total;
        }

        void run() {
            for (int i = 0; i < SUBSYSTEM_COUNT; i++) {
                subsystems.add(new Subsystem() { });
            }
            for (int i = 0; i < SUBSYSTEM_COUNT; i++) {
                Subsystem subsystem = subsystems.get(i);
                // A default command never finishes. In the exception trials some of them fail
                // sometimes, which exercises the "disabled after repeated failures" rule.
                Scheduler.Phase fails = (chaos && rng.nextInt(4) == 0) ? Scheduler.Phase.EXECUTE : null;
                scheduler.setDefaultCommand(subsystem,
                        newChecked("default" + i, Integer.MAX_VALUE, fails, subsystem));
            }

            int rootCount = 1 + rng.nextInt(4);
            for (int r = 0; r < rootCount; r++) {
                roots.add(build(MAX_DEPTH, subsystems, "r" + r));
            }

            for (int loop = 0; loop < LOOPS_PER_TRIAL; loop++) {
                context.loop = loop;
                if (loop == 0 || rng.nextInt(4) == 0) {
                    scheduler.schedule(pickRoot());
                }
                if (rng.nextInt(6) == 0) {
                    scheduler.cancel(pickRoot());
                }
                clock.advanceMillis(rng.nextInt(25));
                try {
                    scheduler.run();
                } catch (RuntimeException error) {
                    context.report("scheduler.run() threw: " + error);
                    return;
                }
                checkRunningCommandsShareNoSubsystem();
            }

            context.loop = LOOPS_PER_TRIAL;
            try {
                scheduler.stop();
            } catch (RuntimeException error) {
                context.report("scheduler.stop() threw: " + error);
                return;
            }
            for (CheckedCommand leaf : leaves) {
                leaf.checkAtRest();
            }
        }

        private Command pickRoot() {
            return roots.get(rng.nextInt(roots.size()));
        }

        private void checkRunningCommandsShareNoSubsystem() {
            int count = scheduler.runningCount();
            for (int i = 0; i < count; i++) {
                Command a = scheduler.runningCommand(i);
                for (int j = i + 1; j < count; j++) {
                    Command b = scheduler.runningCommand(j);
                    for (int x = 0; x < a.requirementCount(); x++) {
                        for (int y = 0; y < b.requirementCount(); y++) {
                            if (a.requirement(x) == b.requirement(y)) {
                                context.report("two running commands share a subsystem");
                            }
                        }
                    }
                }
            }
        }

        private CheckedCommand newChecked(String name, int finishAfter, Scheduler.Phase throwPhase,
                Subsystem... requirements) {
            CheckedCommand command =
                    new CheckedCommand(name, context, finishAfter, throwPhase, requirements);
            leaves.add(command);
            return command;
        }

        /**
         * Builds a random command. Parallel, race and deadline groups split the available
         * subsystems between their children so those children never overlap.
         */
        private Command build(int depth, List<Subsystem> available, String path) {
            if (depth == 0 || rng.nextInt(4) == 0) {
                return leaf(available, path);
            }

            int kind = rng.nextInt(4); // 0 sequential, 1 parallel, 2 race, 3 deadline
            int count = (rng.nextInt(8) == 0) ? 0 : 1 + rng.nextInt(3);
            if (kind == 3 && count == 0) {
                count = 1; // a deadline group needs a deadline
            }
            Command[] kids = new Command[count];

            if (kind == 0) {
                for (int i = 0; i < count; i++) {
                    kids[i] = build(depth - 1, available, path + "." + i);
                }
                return new SequentialGroup(kids);
            }

            List<List<Subsystem>> parts = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                parts.add(new ArrayList<Subsystem>());
            }
            for (Subsystem subsystem : available) {
                if (count > 0) {
                    parts.get(rng.nextInt(count)).add(subsystem);
                }
            }
            for (int i = 0; i < count; i++) {
                kids[i] = build(depth - 1, parts.get(i), path + "." + i);
            }

            if (kind == 1) {
                return new ParallelGroup(kids);
            }
            if (kind == 2) {
                return new RaceGroup(kids);
            }
            return new DeadlineGroup(kids[0], Arrays.copyOfRange(kids, 1, count));
        }

        private Command leaf(List<Subsystem> available, String path) {
            int pick = rng.nextInt(10);
            if (pick == 0) {
                return new WaitTimeCommand(clock, rng.nextInt(60));
            }
            if (pick == 1) {
                return new InstantCommand(() -> { }, pickRequirements(available));
            }

            int finishAfter = (rng.nextInt(8) == 0) ? Integer.MAX_VALUE : rng.nextInt(7);
            Scheduler.Phase throwPhase = null;
            if (chaos && rng.nextInt(6) == 0) {
                Scheduler.Phase[] phases = {
                    Scheduler.Phase.INITIALIZE, Scheduler.Phase.EXECUTE,
                    Scheduler.Phase.IS_FINISHED, Scheduler.Phase.END
                };
                throwPhase = phases[rng.nextInt(phases.length)];
            }
            return newChecked(path, finishAfter, throwPhase, pickRequirements(available));
        }

        private Subsystem[] pickRequirements(List<Subsystem> available) {
            int roll = rng.nextInt(10);
            int wanted = (roll < 6) ? 1 : (roll < 8 ? 0 : 2);
            List<Subsystem> pool = new ArrayList<>(available);
            Collections.shuffle(pool, rng);
            int count = Math.min(wanted, pool.size());
            return pool.subList(0, count).toArray(new Subsystem[0]);
        }
    }
}
