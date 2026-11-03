package xbots.nexus.core.math;

import java.util.Arrays;

/**
 * A look-up table that turns one or more inputs into one or more outputs by
 * drawing smooth curves through calibration points on a rectilinear grid. It
 * uses monotone cubic (Fritsch-Carlson / PCHIP) interpolation.
 *
 * <p>Each input has its own sorted breakpoints, and every combination of
 * breakpoints must have values for every output. Points may be added in any
 * order. Points do not have to be uniformly spaced.</p>
 *
 * <p>Single input, single output:</p>
 * <pre>{@code
 * InterpLUT speeds = InterpLUT.builder(1)
 *     .add(1.0, 3000)
 *     .add(2.0, 3600)
 *     .add(3.0, 4100)
 *     .build();
 * double speed = speeds.get(2.4);
 * }</pre>
 *
 * <p>Two inputs, two outputs. The last values passed to add are the outputs:</p>
 * <pre>{@code
 * InterpLUT table = InterpLUT.builder(2, 2)
 *     .add(1.0, 0.0, 3000, 10.0)
 *     .add(1.0, 90.0, 2500, 20.0)
 *     .add(2.0, 0.0, 3600, 30.0)
 *     .add(2.0, 90.0, 3100, 40.0)
 *     .build();
 * double[] outputs = table.getOutputs(1.5, 45.0);
 * double second = table.getOutput(1, 1.5, 45.0);
 * }</pre>
 *
 * <p>Inputs outside the table are extrapolated linearly from the nearest
 * breakpoint's value and slope. Non-finite coordinates give NaN output
 * values.</p>
 *
 * <p>The table is evaluated by folding one-dimensional monotone cubic
 * interpolants, starting with the last axis. Each output is interpolated
 * independently. Inside the grid it stays within that output's smallest/largest
 * value; outside the grid the linear extrapolation can leave that range. This is
 * not a formally monotone interpolant in more than one dimension. The fold order
 * is fixed, so swapping axes can change values slightly.</p>
 */
public final class InterpLUT {

    // The limit on how steep a slope may be, relative to the straight line between
    // its two neighbors. 3 is the value from the original method's proof of
    // monotonicity.
    private static final double SLOPE_LIMIT = 3.0;

    private final double[][] axes; // one sorted set of breakpoints per input
    private final int[] sizes; // number of breakpoints on each input
    private final int gridSize; // number of points in the input grid
    private final int outputDimensions; // number of values produced per lookup
    private final double[] values; // outputs, output-major, last input fastest
    private final double[] lastAxisSlopes; // slopes along the last input

    // Scratch space for evaluating more than one input. Lookups are sequential
    // (Nexus is single-threaded), so one set of buffers per table is enough.
    private final double[] work;
    private final double[] scratchSlopes;
    private final double[] scratchLine;

    private InterpLUT(double[][] axes, int[] sizes, double[] values,
            double[] lastAxisSlopes, int outputDimensions) {
        this.axes = axes;
        this.sizes = sizes;
        this.values = values;
        this.lastAxisSlopes = lastAxisSlopes;
        this.outputDimensions = outputDimensions;
        this.gridSize = values.length / outputDimensions;

        int inputDimensions = axes.length;
        work = new double[outputDimensions * (gridSize / sizes[inputDimensions - 1])];
        if (inputDimensions == 1) {
            scratchSlopes = null;
            scratchLine = null;
        } else {
            int largestAxis = 0;
            for (int size : sizes) {
                largestAxis = Math.max(largestAxis, size);
            }
            scratchSlopes = new double[largestAxis];
            scratchLine = new double[largestAxis - 1];
        }
    }

    /** Starts a table with a fixed number of inputs and one output. */
    public static Builder builder(int inputDimensions) {
        return new Builder(inputDimensions, 1);
    }

    /** Starts a table with a fixed number of inputs and outputs. */
    public static Builder builder(int inputDimensions, int outputDimensions) {
        return new Builder(inputDimensions, outputDimensions);
    }

    /** Number of inputs. */
    public int inputDimensions() {
        return axes.length;
    }

    /** Number of outputs. */
    public int outputDimensions() {
        return outputDimensions;
    }

    /** Total number of grid points. */
    public int size() {
        return gridSize;
    }

    /** Number of breakpoints on one input. */
    public int size(int inputDimension) {
        checkDimension(inputDimension);
        return sizes[inputDimension];
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
    public double minInput(int inputDimension) {
        checkDimension(inputDimension);
        return axes[inputDimension][0];
    }

    /** The largest input on one axis. */
    public double maxInput(int inputDimension) {
        checkDimension(inputDimension);
        return axes[inputDimension][sizes[inputDimension] - 1];
    }

    /**
     * Looks up the single output for a one-input, one-output table. Inputs
     * outside the table are extrapolated linearly. A non-finite input gives NaN
     * output.
     */
    public double get(double input) {
        if (axes.length != 1 || outputDimensions != 1) {
            throw new IllegalArgumentException(
                    "This overload is only for a one-input, one-output table; use "
                            + "get(coordinates...) for a single output, or "
                            + "getOutput/getOutputs for multiple outputs");
        }
        return interpolate1D(axes[0], values, 0, lastAxisSlopes, 0, gridSize, input);
    }

    /**
     * Looks up the single output for a table with any number of inputs. This is
     * only valid for tables with exactly one output. Inputs outside the table
     * are extrapolated linearly. Non-finite coordinates give NaN.
     */
    public double get(double... coordinates) {
        if (outputDimensions != 1) {
            throw new IllegalArgumentException(
                    "This table has " + outputDimensions
                            + " outputs; use getOutput(output, coordinates...) or "
                            + "getOutputs(coordinates...)");
        }
        checkCoordinates(coordinates);
        if (hasNonFinite(coordinates)) {
            return Double.NaN;
        }
        return evaluateOutput(0, coordinates);
    }

    /**
     * Looks up one output for a table with any number of inputs and outputs.
     * Inputs outside the table are extrapolated linearly. Non-finite coordinates
     * give NaN. Pass a reused coordinate array to avoid allocating inside a
     * control loop.
     */
    public double getOutput(int output, double... coordinates) {
        checkOutput(output);
        checkCoordinates(coordinates);
        if (hasNonFinite(coordinates)) {
            return Double.NaN;
        }
        return evaluateOutput(output, coordinates);
    }

    /**
     * Looks up every output, returning a new array. Inputs outside the table are
     * extrapolated linearly. Non-finite coordinates give NaN. Pass a reused
     * coordinate array to avoid allocating the coordinates inside a control
     * loop.
     */
    public double[] getOutputs(double... coordinates) {
        checkCoordinates(coordinates);
        double[] outputs = new double[outputDimensions];
        if (hasNonFinite(coordinates)) {
            Arrays.fill(outputs, Double.NaN);
            return outputs;
        }
        evaluateAll(coordinates, outputs);
        return outputs;
    }

    /**
     * Looks up every output into a caller-provided array. Inputs outside the
     * table are extrapolated linearly. Non-finite coordinates give NaN. This
     * does not allocate when both arrays are reused.
     */
    public void getOutputs(double[] coordinates, double[] outputs) {
        checkCoordinates(coordinates);
        if (outputs == null) {
            throw new NullPointerException("outputs");
        }
        if (outputs.length != outputDimensions) {
            throw new IllegalArgumentException(
                    "Expected " + outputDimensions + " outputs, but got " + outputs.length);
        }
        if (hasNonFinite(coordinates)) {
            Arrays.fill(outputs, Double.NaN);
            return;
        }
        evaluateAll(coordinates, outputs);
    }

    private void checkDimension(int inputDimension) {
        if (inputDimension < 0 || inputDimension >= axes.length) {
            throw new IllegalArgumentException(
                    "Dimension " + inputDimension + " is outside 0.." + (axes.length - 1));
        }
    }

    private void checkSingleInput() {
        if (axes.length != 1) {
            throw new IllegalStateException(
                    "This table has " + axes.length
                            + " inputs; use minInput(dimension)/maxInput(dimension)");
        }
    }

    private void checkOutput(int output) {
        if (output < 0 || output >= outputDimensions) {
            throw new IllegalArgumentException(
                    "Output " + output + " is outside 0.." + (outputDimensions - 1));
        }
    }

    private void checkCoordinates(double[] coordinates) {
        if (coordinates == null) {
            throw new NullPointerException("coordinates");
        }
        if (coordinates.length != axes.length) {
            throw new IllegalArgumentException(
                    "Expected " + axes.length + " coordinates, but got " + coordinates.length);
        }
    }

    private static boolean hasNonFinite(double[] values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return true;
            }
        }
        return false;
    }

    // Folds one output through the grid. Used when only a single component is
    // needed, so there is no reason to compute the other outputs.
    private double evaluateOutput(int output, double[] coordinates) {
        int base = output * gridSize;
        int dimensions = axes.length;
        if (dimensions == 1) {
            return interpolate1D(axes[0], values, base, lastAxisSlopes, base, gridSize,
                    coordinates[0]);
        }

        int lineLength = sizes[dimensions - 1];
        int lineCount = gridSize / lineLength;
        double query = coordinates[dimensions - 1];
        for (int line = 0; line < lineCount; line++) {
            int offset = line * lineLength;
            work[line] = interpolate1D(axes[dimensions - 1], values, base + offset,
                    lastAxisSlopes, base + offset, lineLength, query);
        }

        int activeLength = lineCount;
        for (int dimension = dimensions - 2; dimension >= 0; dimension--) {
            lineLength = sizes[dimension];
            lineCount = activeLength / lineLength;
            query = coordinates[dimension];
            for (int line = 0; line < lineCount; line++) {
                int offset = line * lineLength;
                computeSlopes1D(axes[dimension], work, offset,
                        scratchSlopes, 0, lineLength, scratchLine);
                work[line] = interpolate1D(axes[dimension], work, offset,
                        scratchSlopes, 0, lineLength, query);
            }
            activeLength = lineCount;
        }
        return work[0];
    }

    // Folds every output through the grid at the same time. All lines on one
    // axis share the same query, so the segment and cubic weights are computed
    // once per stage instead of once per line and output.
    private void evaluateAll(double[] coordinates, double[] outputs) {
        int dimensions = axes.length;
        int activeLength = gridSize;
        double[] source = values;
        boolean firstStage = true;

        for (int dimension = dimensions - 1; dimension >= 0; dimension--) {
            int lineLength = sizes[dimension];
            int lineCount = activeLength / lineLength;
            double[] axis = axes[dimension];
            double query = coordinates[dimension];
            boolean below = query < axis[0];
            boolean above = query > axis[lineLength - 1];

            if (below || above) {
                int endpoint = below ? 0 : lineLength - 1;
                double delta = query - axis[endpoint];
                for (int output = 0; output < outputDimensions; output++) {
                    int sourceBase = output * activeLength;
                    int destinationBase = output * lineCount;
                    for (int line = 0; line < lineCount; line++) {
                        int sourceOffset = sourceBase + line * lineLength;
                        double slope;
                        if (firstStage) {
                            slope = lastAxisSlopes[
                                    output * gridSize + line * lineLength + endpoint];
                        } else {
                            computeSlopes1D(axis, source, sourceOffset,
                                    scratchSlopes, 0, lineLength, scratchLine);
                            slope = scratchSlopes[endpoint];
                        }
                        // Safe in place: the line is read before its output is
                        // written, and output indices stay below unread lines.
                        work[destinationBase + line] =
                                source[sourceOffset + endpoint] + delta * slope;
                    }
                }
            } else {
                int segment = findSegment(axis, lineLength, query);
                double width = axis[segment + 1] - axis[segment];
                double t = (query - axis[segment]) / width;
                double t2 = t * t;
                double t3 = t2 * t;
                double leftPoint = 2 * t3 - 3 * t2 + 1;
                double leftSlope = t3 - 2 * t2 + t;
                double rightPoint = -2 * t3 + 3 * t2;
                double rightSlope = t3 - t2;

                for (int output = 0; output < outputDimensions; output++) {
                    int sourceBase = output * activeLength;
                    int destinationBase = output * lineCount;
                    for (int line = 0; line < lineCount; line++) {
                        int sourceOffset = sourceBase + line * lineLength;
                        double[] slopeData;
                        int slopeOffset;
                        if (firstStage) {
                            slopeData = lastAxisSlopes;
                            slopeOffset = output * gridSize + line * lineLength;
                        } else {
                            computeSlopes1D(axis, source, sourceOffset,
                                    scratchSlopes, 0, lineLength, scratchLine);
                            slopeData = scratchSlopes;
                            slopeOffset = 0;
                        }
                        // Safe in place: the line is read before its output is
                        // written, and output indices stay below unread lines.
                        work[destinationBase + line] = interpolate1DFromWeights(
                                source, sourceOffset, slopeData, slopeOffset,
                                segment, width, leftPoint, leftSlope, rightPoint, rightSlope);
                    }
                }
            }

            source = work;
            activeLength = lineCount;
            firstStage = false;
        }

        for (int output = 0; output < outputDimensions; output++) {
            outputs[output] = work[output];
        }
    }

    private static double interpolate1DFromWeights(double[] ys, int ysOffset,
            double[] slopes, int slopesOffset, int segment, double width,
            double leftPoint, double leftSlope, double rightPoint, double rightSlope) {
        return leftPoint * ys[ysOffset + segment]
                + leftSlope * width * slopes[slopesOffset + segment]
                + rightPoint * ys[ysOffset + segment + 1]
                + rightSlope * width * slopes[slopesOffset + segment + 1];
    }

    private static double interpolate1D(double[] xs, double[] ys, int ysOffset,
            double[] slopes, int slopesOffset, int length, double input) {
        if (!Double.isFinite(input)) {
            return Double.NaN;
        }
        int last = length - 1;
        if (input < xs[0]) {
            return ys[ysOffset] + (input - xs[0]) * slopes[slopesOffset];
        }
        if (input > xs[last]) {
            return ys[ysOffset + last]
                    + (input - xs[last]) * slopes[slopesOffset + last];
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

        return interpolate1DFromWeights(ys, ysOffset, slopes, slopesOffset, i, width,
                leftPoint, leftSlope, rightPoint, rightSlope);
    }

    // Binary search for the stretch that contains the input: the largest i in
    // 0..length-2 with xs[i] <= input. Called for xs[0] <= input <= xs[length-1].
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
        if (axes.length == 1 && outputDimensions == 1) {
            StringBuilder text = new StringBuilder("[");
            for (int i = 0; i < gridSize; i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append("(").append(axes[0][i]).append(", ").append(values[i]).append(")");
            }
            return text.append("]").toString();
        }

        StringBuilder text = new StringBuilder("InterpLUT[");
        text.append(axes.length).append(" inputs, ");
        text.append(outputDimensions).append(outputDimensions == 1 ? " output" : " outputs");
        text.append(", sizes=").append(Arrays.toString(sizes));
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
        private final int inputDimensions;
        private final int outputDimensions;
        private int count = 0;

        private Builder(int inputDimensions, int outputDimensions) {
            if (inputDimensions < 1) {
                throw new IllegalArgumentException(
                        "A look-up table needs at least one input, but was given "
                                + inputDimensions);
            }
            if (outputDimensions < 1) {
                throw new IllegalArgumentException(
                        "A look-up table needs at least one output, but was given "
                                + outputDimensions);
            }
            this.inputDimensions = inputDimensions;
            this.outputDimensions = outputDimensions;
        }

        /**
         * Adds a calibration point to a one-input, one-output table.
         *
         * @throws IllegalArgumentException if either value is NaN or infinite
         */
        public Builder add(double input, double output) {
            if (inputDimensions != 1 || outputDimensions != 1) {
                throw new IllegalArgumentException(
                        "This builder expects " + inputDimensions + " inputs and "
                                + outputDimensions + " outputs; use add(coordinates..., outputs...)");
            }
            append(new double[] { input, output });
            return this;
        }

        /**
         * Adds a calibration point. The first values are coordinates and the
         * remaining values are outputs.
         *
         * @throws IllegalArgumentException if the point has the wrong number of
         *                                  values or any value is NaN or infinite
         */
        public Builder add(double... coordinatesAndOutputs) {
            if (coordinatesAndOutputs == null) {
                throw new NullPointerException("coordinatesAndOutputs");
            }
            int expected = inputDimensions + outputDimensions;
            if (coordinatesAndOutputs.length != expected) {
                throw new IllegalArgumentException(
                        "This builder expects " + inputDimensions + " inputs and "
                                + outputDimensions + " outputs (" + expected + " values), but got "
                                + coordinatesAndOutputs.length);
            }
            append(coordinatesAndOutputs);
            return this;
        }

        private void append(double[] point) {
            for (int i = 0; i < inputDimensions; i++) {
                checkRealNumber("Input", point[i]);
            }
            for (int i = 0; i < outputDimensions; i++) {
                checkRealNumber("Output", point[inputDimensions + i]);
            }

            int stride = inputDimensions + outputDimensions;
            ensureCapacity(stride);
            int offset = count * stride;
            for (int i = 0; i < inputDimensions; i++) {
                // Normalize negative zero so it cannot create a second, equal axis value.
                double coordinate = point[i];
                points[offset + i] = coordinate == 0.0 ? 0.0 : coordinate;
            }
            for (int i = 0; i < outputDimensions; i++) {
                points[offset + inputDimensions + i] = point[inputDimensions + i];
            }
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

            int stride = inputDimensions + outputDimensions;
            int pointCount = count;
            long pointValues = (long) pointCount * stride;
            if (pointValues > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Too many calibration points");
            }
            double[] source = Arrays.copyOf(points, (int) pointValues);

            double[][] axes = new double[inputDimensions][];
            int[] sizes = new int[inputDimensions];
            for (int dimension = 0; dimension < inputDimensions; dimension++) {
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

            long gridSizeLong = 1;
            for (int size : sizes) {
                gridSizeLong *= size;
                if (gridSizeLong > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException(
                            "The table is too large: it has more than "
                                    + Integer.MAX_VALUE + " grid points");
                }
            }
            int gridSize = (int) gridSizeLong;

            long totalValues = (long) gridSize * outputDimensions;
            if (totalValues > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "The table is too large: it has more than "
                                + Integer.MAX_VALUE + " output values");
            }

            int[] strides = new int[inputDimensions];
            strides[inputDimensions - 1] = 1;
            for (int dimension = inputDimensions - 2; dimension >= 0; dimension--) {
                strides[dimension] = strides[dimension + 1] * sizes[dimension + 1];
            }

            double[] values = new double[(int) totalValues];
            boolean[] filled = new boolean[gridSize];
            for (int i = 0; i < pointCount; i++) {
                int index = 0;
                for (int dimension = 0; dimension < inputDimensions; dimension++) {
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
                if (filled[index]) {
                    throw new IllegalArgumentException(
                            "Two points have the same input values: "
                                    + pointText(source, i * stride, inputDimensions, outputDimensions));
                }
                filled[index] = true;
                int sourceOutput = i * stride + inputDimensions;
                for (int output = 0; output < outputDimensions; output++) {
                    values[output * gridSize + index] = source[sourceOutput + output];
                }
            }

            for (int index = 0; index < gridSize; index++) {
                if (!filled[index]) {
                    throw new IllegalArgumentException(
                            "Missing grid point: "
                                    + indexText(index, sizes, strides, axes));
                }
            }

            double[] lastAxisSlopes = new double[(int) totalValues];
            int lastSize = sizes[inputDimensions - 1];
            int lineCount = gridSize / lastSize;
            double[] lineScratch = new double[lastSize - 1];
            for (int output = 0; output < outputDimensions; output++) {
                int base = output * gridSize;
                for (int line = 0; line < lineCount; line++) {
                    int offset = base + line * lastSize;
                    computeSlopes1D(axes[inputDimensions - 1], values, offset,
                            lastAxisSlopes, offset, lastSize, lineScratch);
                }
            }

            return new InterpLUT(axes, sizes, values, lastAxisSlopes, outputDimensions);
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

        private static String pointText(double[] point, int offset,
                int inputDimensions, int outputDimensions) {
            StringBuilder text = new StringBuilder("(");
            for (int dimension = 0; dimension < inputDimensions; dimension++) {
                if (dimension > 0) {
                    text.append(", ");
                }
                text.append(point[offset + dimension]);
            }
            text.append(") -> ");
            if (outputDimensions == 1) {
                text.append(point[offset + inputDimensions]);
            } else {
                text.append("[");
                for (int output = 0; output < outputDimensions; output++) {
                    if (output > 0) {
                        text.append(", ");
                    }
                    text.append(point[offset + inputDimensions + output]);
                }
                text.append("]");
            }
            return text.toString();
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
