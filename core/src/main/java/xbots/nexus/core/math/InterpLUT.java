package xbots.nexus.core.math;

import java.util.Arrays;

/**
 * A look-up table that turns one or more inputs into an output by drawing smooth
 * curves through calibration points on a rectilinear grid. It still uses monotone
 * cubic (Fritsch-Carlson / PCHIP) interpolation but now it does it recursively
 * to support arbitrary number of dimensions.
 *
 * Each input has its own sorted breakpoints, and every combination of
 * breakpoints must have an output. Points may be added in any order.
 * Points DO NOT have to be a uniform distance apart ! !
 *
 * Example of single dimension usage:
 * <pre>{@code
 * InterpLUT speeds = InterpLUT.builder(1)
 *     .add(1.0, 3000)
 *     .add(2.0, 3600)
 *     .add(3.0, 4100)
 *     .build();
 * double speed = speeds.get(2.4);
 * }</pre>
 *
 * Two inputs (the last value passed to add is always the output):
 * <pre>{@code
 * InterpLUT speeds = InterpLUT.builder(2)
 *     .add(1.0, 0.0, 3000)
 *     .add(1.0, 90.0, 2500)
 *     .add(2.0, 0.0, 3600)
 *     .add(2.0, 90.0, 3100)
 *     .build();
 * double speed = speeds.get(1.5, 45.0);
 * }</pre>
 *
 * <p>Inputs outside the table are clamped to the nearest breakpoint on each
 * axis. A NaN coordinate gives NaN output.</p>
 *
 * <p>The table is evaluated by folding one-dimensional monotone cubic
 * interpolants, starting with the last axis. This passes exactly through every
 * grid point and never leaves the smallest/largest output in the table, but it
 * is not a formally monotone interpolant in more than one dimension. The fold
 * order is fixed, so swapping axes can change values slightly.</p>
 */
public final class InterpLUT {

    // The limit on how steep a slope may be, relative to the straight line between
    // its two neighbors. 3 is the value from the original method's proof of
    // monotonicity.
    private static final double SLOPE_LIMIT = 3.0;

    private final double[][] axes; // one sorted set of breakpoints per input
    private final int[] sizes; // number of breakpoints on each input
    private final double[] values; // outputs, last input changing fastest
    private final double[] lastAxisSlopes; // slopes along the last input

    // Scratch space for evaluating more than one input. Lookups are sequential
    // (Nexus is single-threaded), so one set of buffers per table is enough.
    private final double[] work;
    private final double[] scratchSlopes;
    private final double[] scratchLine;

    private InterpLUT(double[][] axes, int[] sizes, double[] values, double[] lastAxisSlopes) {
        this.axes = axes;
        this.sizes = sizes;
        this.values = values;
        this.lastAxisSlopes = lastAxisSlopes;

        int dimensions = axes.length;
        if (dimensions == 1) {
            work = null;
            scratchSlopes = null;
            scratchLine = null;
        } else {
            work = new double[values.length / sizes[dimensions - 1]];
            int largestAxis = 0;
            for (int size : sizes) {
                largestAxis = Math.max(largestAxis, size);
            }
            scratchSlopes = new double[largestAxis];
            scratchLine = new double[largestAxis - 1];
        }
    }

    /** Starts a table with a fixed number of inputs. */
    public static Builder builder(int dimensions) {
        return new Builder(dimensions);
    }

    /** Number of inputs. */
    public int dimensions() {
        return axes.length;
    }

    /** Total number of grid points. */
    public int size() {
        return values.length;
    }

    /** Number of breakpoints on one input. */
    public int size(int dimension) {
        checkDimension(dimension);
        return sizes[dimension];
    }

    /** The smallest input in a one-input table. */
    public double minInput() {
        checkSingleInput();
        return axes[0][0];
    }

    /** The largest input in a one-input table. */
    public double maxInput() {
        checkSingleInput();
        return axes[0][sizes[0] - 1];
    }

    /** The smallest input on one axis. */
    public double minInput(int dimension) {
        checkDimension(dimension);
        return axes[dimension][0];
    }

    /** The largest input on one axis. */
    public double maxInput(int dimension) {
        checkDimension(dimension);
        return axes[dimension][sizes[dimension] - 1];
    }

    /**
     * Looks up the output for a one-input table. Inputs outside the table are
     * clamped. A NaN input gives NaN output.
     */
    public double get(double input) {
        if (axes.length != 1) {
            throw new IllegalArgumentException(
                    "This table has " + axes.length + " inputs; call get(coordinates...)");
        }
        return interpolate1D(axes[0], values, 0, lastAxisSlopes, 0, values.length, input);
    }

    /**
     * Looks up the output for a table with any number of inputs. Coordinates
     * outside the table are clamped on each axis. A NaN coordinate gives NaN
     * output.
     *
     * <p>To avoid allocating an argument array inside a control loop, pass a
     * reused array instead of writing the coordinates separately.</p>
     */
    public double get(double... coordinates) {
        if (coordinates == null) {
            throw new NullPointerException("coordinates");
        }
        int dimensions = axes.length;
        if (coordinates.length != dimensions) {
            throw new IllegalArgumentException(
                    "Expected " + dimensions + " coordinates, but got " + coordinates.length);
        }
        if (dimensions == 1) {
            return get(coordinates[0]);
        }
        for (double coordinate : coordinates) {
            if (Double.isNaN(coordinate)) {
                return Double.NaN;
            }
        }

        // Fold the last input first: each line along that input becomes one
        // value. The remaining loop folds the other inputs until one value is left.
        int lineLength = sizes[dimensions - 1];
        int lineCount = values.length / lineLength;
        double query = clamp(coordinates[dimensions - 1], axes[dimensions - 1], lineLength);
        for (int line = 0; line < lineCount; line++) {
            int offset = line * lineLength;
            work[line] = interpolate1D(axes[dimensions - 1], values, offset,
                    lastAxisSlopes, offset, lineLength, query);
        }

        int activeLength = lineCount;
        for (int dimension = dimensions - 2; dimension >= 0; dimension--) {
            lineLength = sizes[dimension];
            lineCount = activeLength / lineLength;
            query = clamp(coordinates[dimension], axes[dimension], lineLength);
            for (int line = 0; line < lineCount; line++) {
                int offset = line * lineLength;
                computeSlopes1D(axes[dimension], work, offset,
                        scratchSlopes, 0, lineLength, scratchLine);
                // Safe in place: the line is read before its output is written,
                // and the output index is before the line's own start.
                work[line] = interpolate1D(axes[dimension], work, offset,
                        scratchSlopes, 0, lineLength, query);
            }
            activeLength = lineCount;
        }
        return work[0];
    }

    private void checkDimension(int dimension) {
        if (dimension < 0 || dimension >= axes.length) {
            throw new IllegalArgumentException(
                    "Dimension " + dimension + " is outside 0.." + (axes.length - 1));
        }
    }

    private void checkSingleInput() {
        if (axes.length != 1) {
            throw new IllegalStateException(
                    "This table has " + axes.length + " inputs; use minInput(dimension)/maxInput(dimension)");
        }
    }

    private static double clamp(double value, double[] axis, int length) {
        if (value <= axis[0]) {
            return axis[0];
        }
        if (value >= axis[length - 1]) {
            return axis[length - 1];
        }
        return value;
    }

    private static double interpolate1D(double[] xs, double[] ys, int ysOffset,
            double[] slopes, int slopesOffset, int length, double input) {
        if (Double.isNaN(input)) {
            return input;
        }
        int last = length - 1;
        if (input <= xs[0]) {
            return ys[ysOffset];
        }
        if (input >= xs[last]) {
            return ys[ysOffset + last];
        }

        int i = findSegment(xs, length, input);
        double width = xs[i + 1] - xs[i];
        double t = (input - xs[i]) / width;
        double t2 = t * t;
        double t3 = t2 * t;

        double leftPoint = 2 * t3 - 3 * t2 + 1;
        double leftSlope = t3 - 2 * t2 + t;
        double rightPoint = -2 * t3 + 3 * t2;
        double rightSlope = t3 - t2;

        return leftPoint * ys[ysOffset + i]
                + leftSlope * width * slopes[slopesOffset + i]
                + rightPoint * ys[ysOffset + i + 1]
                + rightSlope * width * slopes[slopesOffset + i + 1];
    }

    // Binary search for the stretch that contains the input: the largest i with
    // xs[i] <= input. Only called with xs[0] < input < xs[length - 1].
    private static int findSegment(double[] xs, int length, double input) {
        int low = 0;
        int high = length - 1;
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
        if (axes.length == 1) {
            StringBuilder text = new StringBuilder("[");
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append("(").append(axes[0][i]).append(", ").append(values[i]).append(")");
            }
            return text.append("]").toString();
        }

        StringBuilder text = new StringBuilder("InterpLUT[");
        text.append(axes.length).append(" inputs, sizes=").append(Arrays.toString(sizes));
        text.append(", axes=[");
        for (int i = 0; i < axes.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(Arrays.toString(axes[i]));
        }
        return text.append("]]").toString();
    }

    /**
     * Collects calibration points. Call {@link #build()} to get the finished
     * table.
     */
    public static final class Builder {

        private double[] points = new double[16];
        private final int dimensions;
        private int count = 0;

        private Builder(int dimensions) {
            if (dimensions < 1) {
                throw new IllegalArgumentException(
                        "A look-up table needs at least one input, but was given " + dimensions);
            }
            this.dimensions = dimensions;
        }

        /**
         * Adds a calibration point to a one-input table.
         *
         * @throws IllegalArgumentException if either value is NaN or infinite
         */
        public Builder add(double input, double output) {
            if (dimensions != 1) {
                throw new IllegalArgumentException(
                        "This builder expects " + dimensions
                                + " inputs; use add(coordinates..., output)");
            }
            append(new double[] { input, output });
            return this;
        }

        /**
         * Adds a calibration point. Every value except the last is a coordinate;
         * the last value is the output.
         *
         * @throws IllegalArgumentException if the point has the wrong number of
         *                                  values or any value is NaN or infinite
         */
        public Builder add(double... coordinatesAndOutput) {
            if (coordinatesAndOutput == null) {
                throw new NullPointerException("coordinatesAndOutput");
            }
            if (coordinatesAndOutput.length < 2) {
                throw new IllegalArgumentException(
                        "A calibration point needs at least one input and one output");
            }
            int pointDimensions = coordinatesAndOutput.length - 1;
            if (dimensions != pointDimensions) {
                throw new IllegalArgumentException(
                        "This builder expects " + dimensions + " inputs and one output, but got "
                                + coordinatesAndOutput.length + " values");
            }
            append(coordinatesAndOutput);
            return this;
        }

        private void append(double[] point) {
            for (int i = 0; i < dimensions; i++) {
                checkRealNumber("Input", point[i]);
            }
            checkRealNumber("Output", point[dimensions]);

            int stride = dimensions + 1;
            ensureCapacity(stride);
            int offset = count * stride;
            for (int i = 0; i < dimensions; i++) {
                // Normalize negative zero so it cannot create a second, equal axis value.
                double coordinate = point[i];
                points[offset + i] = coordinate == 0.0 ? 0.0 : coordinate;
            }
            points[offset + dimensions] = point[dimensions];
            count++;
        }

        private static void checkRealNumber(String kind, double value) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException(kind + " is not a real number: " + value);
            }
        }

        private void ensureCapacity(int stride) {
            long needed = ((long) count + 1) * stride;
            if (needed <= points.length) {
                return;
            }
            long capacity = points.length;
            while (capacity < needed) {
                if (capacity > Integer.MAX_VALUE / 2) {
                    capacity = Integer.MAX_VALUE;
                    break;
                }
                capacity *= 2;
            }
            if (capacity < needed) {
                throw new IllegalArgumentException("Too many calibration points");
            }
            points = Arrays.copyOf(points, (int) capacity);
        }

        /**
         * Builds the table. The builder is left unchanged, so more points can be
         * added and another table built, and earlier tables are not affected.
         *
         * @throws IllegalArgumentException if there are too few points, an input
         *                                  has fewer than two values, or the points
         *                                  do not form a complete rectilinear grid
         */
        public InterpLUT build() {
            if (count == 0) {
                throw new IllegalArgumentException(
                        "A look-up table needs at least one calibration point");
            }

            int inputCount = dimensions;
            int stride = inputCount + 1;
            int pointCount = count;
            long pointValues = (long) pointCount * stride;
            if (pointValues > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Too many calibration points");
            }
            double[] source = Arrays.copyOf(points, (int) pointValues);

            double[][] axes = new double[inputCount][];
            int[] sizes = new int[inputCount];
            for (int dimension = 0; dimension < inputCount; dimension++) {
                double[] coordinates = new double[pointCount];
                for (int i = 0; i < pointCount; i++) {
                    coordinates[i] = source[i * stride + dimension];
                }
                Arrays.sort(coordinates);
                int unique = dedupe(coordinates);
                if (unique < 2) {
                    throw new IllegalArgumentException(
                            "Input " + dimension + " has only one value: " + coordinates[0]
                                    + ". A table needs at least two values on every input.");
                }
                axes[dimension] = Arrays.copyOf(coordinates, unique);
                sizes[dimension] = unique;
            }

            long gridSize = 1;
            for (int size : sizes) {
                gridSize *= size;
                if (gridSize > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException(
                            "The table is too large: it has more than "
                                    + Integer.MAX_VALUE + " grid points");
                }
            }
            int totalSize = (int) gridSize;

            int[] strides = new int[inputCount];
            strides[inputCount - 1] = 1;
            for (int dimension = inputCount - 2; dimension >= 0; dimension--) {
                strides[dimension] = strides[dimension + 1] * sizes[dimension + 1];
            }

            double[] values = new double[totalSize];
            Arrays.fill(values, Double.NaN);
            for (int i = 0; i < pointCount; i++) {
                int index = 0;
                for (int dimension = 0; dimension < inputCount; dimension++) {
                    int position = Arrays.binarySearch(
                            axes[dimension], source[i * stride + dimension]);
                    if (position < 0) {
                        throw new IllegalStateException(
                                "Could not find coordinate "
                                        + source[i * stride + dimension]
                                        + " on input " + dimension);
                    }
                    index += position * strides[dimension];
                }
                if (!Double.isNaN(values[index])) {
                    throw new IllegalArgumentException(
                            "Two points have the same input values: "
                                    + pointText(source, i * stride, inputCount));
                }
                values[index] = source[i * stride + inputCount];
            }

            for (int index = 0; index < totalSize; index++) {
                if (Double.isNaN(values[index])) {
                    throw new IllegalArgumentException(
                            "Missing grid point: "
                                    + indexText(index, sizes, strides, axes));
                }
            }

            double[] lastAxisSlopes = new double[totalSize];
            int lastSize = sizes[inputCount - 1];
            int lineCount = totalSize / lastSize;
            double[] lineScratch = new double[lastSize - 1];
            for (int line = 0; line < lineCount; line++) {
                int offset = line * lastSize;
                computeSlopes1D(axes[inputCount - 1], values, offset,
                        lastAxisSlopes, offset, lastSize, lineScratch);
            }

            return new InterpLUT(axes, sizes, values, lastAxisSlopes);
        }

        private static int dedupe(double[] sorted) {
            int unique = 1;
            for (int i = 1; i < sorted.length; i++) {
                if (sorted[i] != sorted[unique - 1]) {
                    sorted[unique] = sorted[i];
                    unique++;
                }
            }
            return unique;
        }

        private static String pointText(double[] point, int offset, int dimensions) {
            StringBuilder text = new StringBuilder("(");
            for (int dimension = 0; dimension < dimensions; dimension++) {
                if (dimension > 0) {
                    text.append(", ");
                }
                text.append(point[offset + dimension]);
            }
            return text.append(") -> ").append(point[offset + dimensions]).toString();
        }

        private static String indexText(int index, int[] sizes, int[] strides, double[][] axes) {
            StringBuilder text = new StringBuilder("(");
            int remainder = index;
            for (int dimension = 0; dimension < sizes.length; dimension++) {
                int position = remainder / strides[dimension];
                remainder %= strides[dimension];
                if (dimension > 0) {
                    text.append(", ");
                }
                text.append(axes[dimension][position]);
            }
            return text.append(")").toString();
        }
    }

    private static void computeSlopes1D(double[] x, double[] y, int yOffset,
            double[] slopes, int slopesOffset, int length, double[] line) {
        int n = length;

        // Slope of the straight line between each pair of neighboring points.
        for (int i = 0; i < n - 1; i++) {
            line[i] = (y[yOffset + i + 1] - y[yOffset + i]) / (x[i + 1] - x[i]);
        }

        // The end points have only one neighbor, so they copy that line's slope.
        slopes[slopesOffset] = line[0];
        slopes[slopesOffset + n - 1] = line[n - 2];
        for (int i = 1; i < n - 1; i++) {
            if (line[i - 1] * line[i] <= 0) {
                // This means data changes direction. Heading straight through
                // would overshoot, so stay level.
                slopes[slopesOffset + i] = 0;
            } else {
                // If no direction change, head in the average of the two
                // neighboring lines.
                slopes[slopesOffset + i] = (line[i - 1] + line[i]) * 0.5;
            }
        }

        // Limit slopes that are too steep for their stretch, which is what
        // causes wobble.
        for (int i = 0; i < n - 1; i++) {
            if (line[i] == 0) {
                // A flat stretch must stay perfectly flat.
                slopes[slopesOffset + i] = 0;
                slopes[slopesOffset + i + 1] = 0;
            } else {
                // How steep each end is compared to the stretch's own line.
                double a = slopes[slopesOffset + i] / line[i];
                double b = slopes[slopesOffset + i + 1] / line[i];
                double steepness = Math.hypot(a, b);
                if (steepness > SLOPE_LIMIT) {
                    double scale = SLOPE_LIMIT / steepness;
                    slopes[slopesOffset + i] = scale * a * line[i];
                    slopes[slopesOffset + i + 1] = scale * b * line[i];
                }
            }
        }
    }
}
