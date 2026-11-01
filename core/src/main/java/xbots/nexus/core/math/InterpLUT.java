package xbots.nexus.core.math;

import java.util.Arrays;

/**
 * A LUT that turns an input into an output by drawing a smooth curve through
 * calibration points (uses monotonic cubic interpolation).  
 * uses fritz carlton method of cubic interpolation
 *
 * usage:
 * InterpLUT speeds = InterpLUT.builder()
 *     .add(1.0, 3000)
 *     .add(2.0, 3600)
 *     .add(3.0, 4100)
 *     .build();
 * double speed = speeds.get(2.4);
 * 
 *
 */
public final class InterpLUT {

    // The limit on how steep a slope may be, relative to the straight line between
    // its two
    // neighbors. 3 is the value from the original method's proof of monotonicity.
    private static final double SLOPE_LIMIT = 3.0;

    private final double[] xs; // inputs, must be increasing
    private final double[] ys; // outputs
    private final double[] slopes; // the direction the curve is heading at each point

    private InterpLUT(double[] xs, double[] ys, double[] slopes) {
        this.xs = xs;
        this.ys = ys;
        this.slopes = slopes;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Number of calibration points */
    public int size() {
        return xs.length;
    }

    /** The smallest input in the table. Smaller inputs give the first output */
    public double minInput() {
        return xs[0];
    }

    /** The largest input in the table. Larger inputs give the last output. */
    public double maxInput() {
        return xs[xs.length - 1];
    }

    /**
     * Looks up the output for an input. Inputs outside the table are clamped to the
     * first or last
     * output. A NaN input gives NaN output.
     */
    public double get(double input) {
        if (Double.isNaN(input)) {
            return input;
        }
        int last = xs.length - 1;
        if (input <= xs[0]) {
            return ys[0];
        }
        if (input >= xs[last]) {
            return ys[last];
        }

        int i = findSegment(input);
        double width = xs[i + 1] - xs[i];
        // t is how far through this stretch the input is: 0 at its left point, 1 at its
        // right point.
        double t = (input - xs[i]) / width;
        double t2 = t * t;
        double t3 = t2 * t;

        // Four weights that blend the left point, the left slope, the right point and
        // the right
        // slope. They are chosen so that at t = 0 the result is exactly the left point
        // heading in
        // the left slope's direction, and at t = 1 exactly the right point.
        double leftPoint = 2 * t3 - 3 * t2 + 1;
        double leftSlope = t3 - 2 * t2 + t;
        double rightPoint = -2 * t3 + 3 * t2;
        double rightSlope = t3 - t2;

        return leftPoint * ys[i]
                + leftSlope * width * slopes[i]
                + rightPoint * ys[i + 1]
                + rightSlope * width * slopes[i + 1];
    }

    // Binary search for the stretch that contains the input: the largest i with
    // xs[i] <= input.
    // Only called with xs[0] < input < xs[last], so the answer is always a valid
    // stretch.
    private int findSegment(double input) {
        int low = 0;
        int high = xs.length - 1;
        // Invariant: xs[low] <= input < xs[high].
        while (high - low > 1) {
            int mid = (low + high) >>> 1;
            if (xs[mid] <= input) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < xs.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append("(").append(xs[i]).append(", ").append(ys[i]).append(")");
        }
        return text.append("]").toString();
    }

    /**
     * Collects calibration points. Call {@link #build()} to get the finished table.
     */
    public static final class Builder {

        private double[] xs = new double[8];
        private double[] ys = new double[8];
        private int count = 0;

        private Builder() {
        }

        /**
         * Adds a calibration point.
         *
         * @throws IllegalArgumentException if either value is NaN or infinite
         */
        public Builder add(double input, double output) {
            if (Double.isNaN(input) || Double.isInfinite(input)) {
                throw new IllegalArgumentException("Input is not a real number: " + input);
            }
            if (Double.isNaN(output) || Double.isInfinite(output)) {
                throw new IllegalArgumentException("Output is not a real number: " + output);
            }
            if (count == xs.length) {
                xs = Arrays.copyOf(xs, count * 2);
                ys = Arrays.copyOf(ys, count * 2);
            }
            xs[count] = input;
            ys[count] = output;
            count++;
            return this;
        }

        /**
         * Builds the table. The builder is left unchanged, so more points can be added
         * and another
         * table built, and earlier tables are not affected.
         *
         * @throws IllegalArgumentException if there are fewer than two points or two
         *                                  points have the
         *                                  same input
         */
        public InterpLUT build() {
            if (count < 2) {
                throw new IllegalArgumentException(
                        "A look-up table needs at least two points, but has " + count);
            }
            double[] x = Arrays.copyOf(xs, count);
            double[] y = Arrays.copyOf(ys, count);
            sortByInput(x, y);
            for (int i = 1; i < count; i++) {
                if (x[i] == x[i - 1]) {
                    throw new IllegalArgumentException(
                            "Two points have the same input value: " + x[i]);
                }
            }
            return new InterpLUT(x, y, computeSlopes(x, y));
        }

        // Insertion sort that keeps each input paired with its output. Tables are
        // small, and this runs only one time when building, so no need for 
        // complicated algorithms
        private static void sortByInput(double[] x, double[] y) {
            for (int i = 1; i < x.length; i++) {
                double inputValue = x[i];
                double outputValue = y[i];
                int j = i - 1;
                while (j >= 0 && x[j] > inputValue) {
                    x[j + 1] = x[j];
                    y[j + 1] = y[j];
                    j--;
                }
                x[j + 1] = inputValue;
                y[j + 1] = outputValue;
            }
        }

        private static double[] computeSlopes(double[] x, double[] y) {
            int n = x.length;

            // Slope of the straight line between each pair of neighboring points.
            double[] line = new double[n - 1];
            for (int i = 0; i < n - 1; i++) {
                line[i] = (y[i + 1] - y[i]) / (x[i + 1] - x[i]);
            }

            double[] slope = new double[n];
            // The end points have only one neighbor, so they copy that line's slope.
            slope[0] = line[0];
            slope[n - 1] = line[n - 2];
            for (int i = 1; i < n - 1; i++) {
                if (line[i - 1] * line[i] <= 0) {
                    // This means data changes direction
                    // Heading straight through would overshoot, so stay level.
                    slope[i] = 0;
                } else {
                    // if no direction change head in the average of the two neighboring lines.
                    slope[i] = (line[i - 1] + line[i]) * 0.5;
                }
            }

            // Limit slopes that are too steep for their stretch, which is what causes
            // wobble.
            for (int i = 0; i < n - 1; i++) {
                if (line[i] == 0) {
                    // A flat stretch must stay perfectly flat.
                    slope[i] = 0;
                    slope[i + 1] = 0;
                } else {
                    // How steep each end is compared to the stretch's own line.
                    double a = slope[i] / line[i];
                    double b = slope[i + 1] / line[i];
                    double steepness = Math.hypot(a, b);
                    if (steepness > SLOPE_LIMIT) {
                        double scale = SLOPE_LIMIT / steepness;
                        slope[i] = scale * a * line[i];
                        slope[i + 1] = scale * b * line[i];
                    }
                }
            }
            return slope;
        }
    }
}
